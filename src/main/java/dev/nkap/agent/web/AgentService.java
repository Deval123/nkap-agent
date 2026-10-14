package dev.nkap.agent.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.nkap.agent.live.LiveBudget;
import dev.nkap.agent.model.AgentModel;
import dev.nkap.agent.model.NebiusModel;
import dev.nkap.agent.scenario.Catalogue;
import dev.nkap.agent.scenario.ScenarioCase;
import dev.nkap.agent.trace.TraceReplay;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The agent over HTTP, with one page. The JDK's own server, no framework. Nothing but the routes below exists:
 *
 * <pre>
 * GET  /  /app.js  /app.css            the page, from this service, with no external request
 * GET  /api/status                      whether live is on, and how many live runs are left today
 * GET  /api/recordings                  the published traces
 * GET  /api/recordings/{id}             one, replayed (the id is looked up in the list, never made into a path)
 * POST /api/runs                        {"case": ..., "mode": "scripted" | "live"}
 * POST /api/runs/{runId}/approve        the approver's key decides the run's proposal
 * POST /api/runs/{runId}/self-approve   the agent's own key tries, and Nkap is expected to refuse
 * </pre>
 *
 * <p>Every response carries the security headers; a request with an {@code Origin} that is not the {@code Host} is
 * refused; a POST needs {@code application/json} and at most 1 KB; at most two runs go at once. The log holds the
 * method, the route shape and the status, and nothing else: no address, no body, no id, no model text.
 */
public final class AgentService implements AutoCloseable {

    static final String CSP = "default-src 'none'; script-src 'self'; style-src 'self'; connect-src 'self'; base-uri 'none'; "
            + "form-action 'none'; frame-ancestors 'none'";
    static final int MAX_BODY = 1024;
    private static final System.Logger LOG = System.getLogger("dev.nkap.agent.web");
    private static final ObjectMapper JSON = new ObjectMapper();

    /** A route: a method, a path pattern, the shape logged for it, and what answers it. */
    private record Route(String method, Pattern path, String shape, Function<Call, Reply> handler) {
    }

    /** What a handler is given: the path's groups, the body, and who the visitor is. */
    record Call(Matcher path, byte[] body, String visitor) {
    }

    private final HttpServer server;
    private final ExecutorService executor;
    private final WebConfig config;
    private final RunService runs;
    private final List<Route> routes = new ArrayList<>();

    private AgentService(WebConfig config, Supplier<AgentModel> liveModel, Clock clock) throws IOException {
        this.config = config;
        LiveBudget live = new LiveBudget(config.stateDir().resolve("live-budget.json"), config.liveDailyCap(), config.liveCooldown(), config.salt(), clock);
        LiveBudget scripted = new LiveBudget(config.stateDir().resolve("scripted-budget.json"), config.scriptedDailyCap(), config.scriptedCooldown(),
                config.salt(), clock);
        this.runs = new RunService(config, liveModel, live, scripted, clock);
        byte[] page = resource("index.html");
        byte[] script = resource("app.js");
        byte[] style = resource("app.css");
        route("GET", "/", "/", c -> Reply.asset(page, "text/html; charset=utf-8"));
        route("GET", "/app.js", "/app.js", c -> Reply.asset(script, "application/javascript; charset=utf-8"));
        route("GET", "/app.css", "/app.css", c -> Reply.asset(style, "text/css; charset=utf-8"));
        route("GET", "/api/status", "/api/status", c -> status());
        route("GET", "/api/recordings", "/api/recordings", c -> recordings());
        route("GET", "/api/recordings/([a-z0-9-]{1,64})", "/api/recordings/{id}", c -> recording(c.path().group(1)));
        route("POST", "/api/runs", "/api/runs", this::startRun);
        route("POST", "/api/runs/([0-9a-f]{32})/approve", "/api/runs/{runId}/approve", c -> decision(c.path().group(1), false));
        route("POST", "/api/runs/([0-9a-f]{32})/self-approve", "/api/runs/{runId}/self-approve", c -> decision(c.path().group(1), true));
        this.executor = Executors.newFixedThreadPool(16);
        this.server = HttpServer.create(new InetSocketAddress(config.bind(), config.port()), 0);
        this.server.setExecutor(executor);
        this.server.createContext("/", this::handle);
        this.server.start();
    }

    /** Starts the service. Live runs use the Nebius model, and only if the configuration turns live on. */
    public static AgentService start(WebConfig config) throws IOException {
        Supplier<AgentModel> live = config.liveEnabled()
                ? () -> new NebiusModel(config.nebiusBaseUrl(), config.nebiusKey(), config.nebiusModel(), 6000, Duration.ofSeconds(60))
                : null;
        return start(config, live, Clock.systemUTC());
    }

    /** For tests: the model and the clock are given. */
    static AgentService start(WebConfig config, Supplier<AgentModel> liveModel, Clock clock) throws IOException {
        return new AgentService(config, liveModel, clock);
    }

    public int port() {
        return server.getAddress().getPort();
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }

    public static void main(String[] args) throws IOException {
        WebConfig config;
        try {
            config = WebConfig.from(System.getenv());
        } catch (IllegalArgumentException e) {
            System.err.println("agent service: " + e.getMessage());
            System.exit(2);
            return;
        }
        AgentService service = start(config);
        System.out.println("agent service: " + config + " listening on port " + service.port());
        Runtime.getRuntime().addShutdownHook(new Thread(service::close));
    }

    private void route(String method, String path, String shape, Function<Call, Reply> handler) {
        routes.add(new Route(method, Pattern.compile(path), shape, handler));
    }

    private static byte[] resource(String name) throws IOException {
        try (InputStream in = AgentService.class.getResourceAsStream("/web/" + name)) {
            if (in == null) {
                throw new IOException("the page is missing from the build: " + name);
            }
            return in.readAllBytes();
        }
    }

    // --- one request

    private void handle(HttpExchange exchange) throws IOException {
        String shape = "unknown";
        Reply reply;
        try {
            String path = exchange.getRequestURI().getRawPath();
            String method = exchange.getRequestMethod();
            List<Route> matching = new ArrayList<>();
            Route chosen = null;
            Matcher chosenMatch = null;
            for (Route route : routes) {
                Matcher m = route.path().matcher(path);
                if (m.matches()) {
                    matching.add(route);
                    if (route.method().equals(method)) {
                        chosen = route;
                        chosenMatch = m;
                    }
                }
            }
            if (!originMatchesHost(exchange)) {
                reply = Reply.error(403, "forbidden-origin", "This request comes from another origin.");
            } else if (matching.isEmpty()) {
                reply = Reply.error(404, "not-found", "Not found.");
            } else if (chosen == null) {
                shape = matching.get(0).shape();
                reply = Reply.error(405, "method-not-allowed", "That method is not allowed here.")
                        .with("Allow", String.join(", ", matching.stream().map(Route::method).toList()));
            } else {
                shape = chosen.shape();
                reply = answer(exchange, chosen, chosenMatch);
            }
        } catch (RuntimeException | IOException e) {
            LOG.log(System.Logger.Level.WARNING, "request failed ({0})", e.getClass().getSimpleName());
            reply = Reply.error(500, "internal", "Something went wrong on the server.");
        }
        send(exchange, reply, shape);
    }

    private Reply answer(HttpExchange exchange, Route route, Matcher match) throws IOException {
        byte[] body = new byte[0];
        if (route.method().equals("POST")) {
            String type = exchange.getRequestHeaders().getFirst("Content-Type");
            if (type == null || !type.matches("(?i)\\s*application/json\\s*(;.*)?")) {
                return Reply.error(415, "unsupported-media-type", "Send application/json.");
            }
            String length = exchange.getRequestHeaders().getFirst("Content-Length");
            if (length != null && length.matches("[0-9]{1,18}") && Long.parseLong(length) > MAX_BODY) {
                return Reply.error(413, "too-large", "The request is too large.");
            }
            body = exchange.getRequestBody().readNBytes(MAX_BODY + 1);
            if (body.length > MAX_BODY) {
                return Reply.error(413, "too-large", "The request is too large.");
            }
        }
        String peer = exchange.getRemoteAddress().getAddress().getHostAddress();
        String visitor = VisitorKey.of(peer, exchange.getRequestHeaders().get("X-Forwarded-For"), config.trustedProxy());
        return route.handler().apply(new Call(match, body, visitor));
    }

    private static boolean originMatchesHost(HttpExchange exchange) {
        String origin = exchange.getRequestHeaders().getFirst("Origin");
        if (origin == null) {
            return true;
        }
        String host = exchange.getRequestHeaders().getFirst("Host");
        try {
            URI uri = URI.create(origin);
            return host != null && uri.getRawAuthority() != null && uri.getRawAuthority().equalsIgnoreCase(host) && uri.getRawPath().isEmpty();
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private void send(HttpExchange exchange, Reply reply, String shape) throws IOException {
        var headers = exchange.getResponseHeaders();
        headers.set("Content-Security-Policy", CSP);
        headers.set("X-Content-Type-Options", "nosniff");
        headers.set("Referrer-Policy", "no-referrer");
        headers.set("Cache-Control", "no-store");
        headers.set("Content-Type", reply.contentType());
        reply.headers().forEach(headers::set);
        byte[] body = reply.body();
        exchange.sendResponseHeaders(reply.status(), body.length == 0 ? -1 : body.length);
        if (body.length > 0) {
            exchange.getResponseBody().write(body);
        }
        exchange.close();
        LOG.log(System.Logger.Level.INFO, "{0} {1} {2}", exchange.getRequestMethod().replaceAll("[^A-Z]", "?"), shape, reply.status());
    }

    // --- the handlers

    private Reply status() {
        ObjectNode node = JSON.createObjectNode();
        node.put("liveEnabled", runs.liveEnabled());
        Optional<Integer> left = runs.liveRunsLeft();
        if (left.isPresent()) {
            node.put("liveRunsLeftToday", left.get());
        } else {
            node.putNull("liveRunsLeftToday");
        }
        return Reply.json(200, node);
    }

    /** The published traces, by file name; an id is only ever matched against these names. */
    private List<Path> traces() {
        try (var files = Files.list(config.tracesDir())) {
            return files.filter(p -> p.getFileName().toString().matches("[a-z0-9-]{1,64}\\.json")).sorted().toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    private Reply recordings() {
        ArrayNode list = JSON.createArrayNode();
        for (Path file : traces()) {
            try {
                JsonNode trace = JSON.readTree(file.toFile());
                List<TraceReplay.Step> steps = TraceReplay.steps(trace);
                ObjectNode item = list.addObject();
                item.put("id", file.getFileName().toString().replaceAll("\\.json$", ""));
                item.put("case", trace.path("provenance").path("case").asText());
                item.put("title", trace.path("provenance").path("title").asText());
                item.put("outcome", steps.get(steps.size() - 1).data().path("kind").asText());
                item.put("matched", trace.path("provenance").path("matched").asBoolean());
            } catch (IOException | RuntimeException skipped) {
                // a file that is not a trace is not listed
            }
        }
        return Reply.json(200, list);
    }

    private Reply recording(String id) {
        for (Path file : traces()) {
            if (file.getFileName().toString().equals(id + ".json")) {
                try {
                    JsonNode trace = JSON.readTree(file.toFile());
                    TraceReplay.steps(trace);
                    ObjectNode body = JSON.createObjectNode();
                    body.put("id", id);
                    body.put("mode", "recording");
                    body.set("provenance", trace.get("provenance"));
                    body.set("steps", StepViews.of(trace.get("steps"), "recording", true));
                    return Reply.json(200, body);
                } catch (IOException | RuntimeException e) {
                    break;
                }
            }
        }
        return Reply.error(404, "not-found", "Not found.");
    }

    private Reply startRun(Call call) {
        String caseId;
        String mode;
        try {
            JsonNode request = JSON.readTree(call.body());
            if (request == null || !request.isObject() || request.size() != 2 || !request.path("case").isTextual() || !request.path("mode").isTextual()) {
                return badRequest();
            }
            caseId = request.get("case").asText();
            mode = request.get("mode").asText();
        } catch (IOException | RuntimeException e) {
            return badRequest();
        }
        if (Catalogue.all().stream().map(ScenarioCase::id).noneMatch(caseId::equals) || !(mode.equals("scripted") || mode.equals("live"))) {
            return badRequest();
        }
        return toReply(runs.start(caseId, mode, call.visitor()));
    }

    private Reply decision(String runId, boolean self) {
        return toReply(runs.decide(runId, self));
    }

    private static Reply badRequest() {
        return Reply.error(400, "bad-request", "Send a case from the list and a mode, scripted or live, and nothing else.");
    }

    private static Reply toReply(RunService.Result result) {
        if (result.refusal() == null) {
            return Reply.json(200, result.body());
        }
        RunService.Refusal refusal = result.refusal();
        ObjectNode node = JSON.createObjectNode();
        node.put("error", refusal.code());
        node.put("text", refusal.text());
        if (refusal.retryAfterSeconds() > 0) {
            node.put("retryAfterSeconds", refusal.retryAfterSeconds());
        }
        return Reply.json(refusal.status(), node);
    }
}

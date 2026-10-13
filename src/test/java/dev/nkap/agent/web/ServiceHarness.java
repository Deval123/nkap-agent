package dev.nkap.agent.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.nkap.agent.approval.FakeNkap;
import dev.nkap.agent.loop.RunCli;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;
import dev.nkap.agent.model.AgentModel;

/** The service, started against a fake Nkap (gateway and adjustment routes) and the fake data of the catalogue. */
final class ServiceHarness implements AutoCloseable {

    static final String AGENT_KEY = "TEST-ONLY-AGENT-KEY-aa11";
    static final String APPROVER_KEY = "TEST-ONLY-APPROVER-KEY-bb22";
    static final String SALT = "TEST-ONLY-SALT-cc33";
    static final String MODEL_KEY = "TEST-ONLY-MODEL-KEY-dd44";
    static final ObjectMapper JSON = new ObjectMapper();

    record Reply(int status, String body, Map<String, java.util.List<String>> headers) {
        JsonNode json() {
            try {
                return JSON.readTree(body);
            } catch (IOException e) {
                throw new AssertionError("not JSON: " + body);
            }
        }

        String header(String name) {
            return headers.entrySet().stream().filter(e -> e.getKey().equalsIgnoreCase(name)).map(e -> e.getValue().get(0)).findFirst().orElse(null);
        }
    }

    final Path dir;
    final FakeNkap nkap;
    final AgentService service;
    final AtomicInteger ledgerEntries = new AtomicInteger();
    final Map<String, String> proposedBy = new ConcurrentHashMap<>();
    final Map<String, Boolean> decided = new ConcurrentHashMap<>();
    final java.util.List<String> proposalBodies = new java.util.concurrent.CopyOnWriteArrayList<>();
    /** The bearer of every approve or reject POST that reached the fake Nkap, in order. */
    final java.util.List<String> decisionPosts = new java.util.concurrent.CopyOnWriteArrayList<>();
    /** Adjustments the fake has "lost", as after an operator's reset: a read of one is a 404. */
    final java.util.Set<String> gone = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /** While true, a read of an adjustment is a 500. */
    volatile boolean readsFail;
    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

    ServiceHarness(Path dir, Consumer<Map<String, String>> tweak, Supplier<AgentModel> liveModel, Clock clock) throws Exception {
        this.dir = dir;
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        int code = RunCli.run(new String[] {"fake-data", dir.toString()}, Map.of(), new PrintStream(sink), new PrintStream(sink));
        if (code != 0) {
            throw new AssertionError("fake data failed");
        }
        nkap = new FakeNkap();
        nkap.handler = this::nkap;
        Map<String, String> env = new HashMap<>();
        env.put("NKAP_AGENT_PORT", "0");
        env.put("NKAP_AGENT_MANIFEST", dir.resolve("manifest.json").toString());
        env.put("NKAP_GATEWAY_URL", nkap.url());
        env.put("NKAP_AGENT_KEY", AGENT_KEY);
        env.put("NKAP_APPROVER_KEY", APPROVER_KEY);
        env.put("NKAP_AGENT_SALT", SALT);
        env.put("NKAP_AGENT_STATE_DIR", dir.resolve("state").toString());
        env.put("NKAP_AGENT_TRACES_DIR", "demo/traces");
        env.put("NKAP_AGENT_SCRIPTED_COOLDOWN_SECONDS", "0");
        env.put("NKAP_AGENT_LIVE_COOLDOWN_SECONDS", "0");
        tweak.accept(env);
        service = AgentService.start(WebConfig.from(env), liveModel, clock);
    }

    private FakeNkap.Answer nkap(FakeNkap.Seen r) {
        String bearer = r.headers().get("authorization").get(0).replace("Bearer ", "");
        String path = r.path();
        try {
            if (r.method().equals("GET") && (path.startsWith("/payments/") || path.startsWith("/statements/imports/"))) {
                Path file = dir.resolve("gateway" + path);
                return Files.exists(file) ? new FakeNkap.Answer(200, Files.readString(file)) : new FakeNkap.Answer(404, "{}");
            }
        } catch (IOException e) {
            return new FakeNkap.Answer(500, "{}");
        }
        if (r.method().equals("POST") && path.equals("/adjustments")) {
            proposalBodies.add(r.body());
            String id = UUID.randomUUID().toString();
            proposedBy.put(id, bearer);
            return new FakeNkap.Answer(201, "{\"id\":\"" + id + "\",\"status\":\"pending\",\"hypothesis\":\"unrecorded_fee\"}");
        }
        java.util.regex.Matcher one = java.util.regex.Pattern.compile("/adjustments/([0-9a-f-]{36})").matcher(path);
        if (r.method().equals("GET") && one.matches()) {
            String id = one.group(1);
            if (readsFail) {
                return new FakeNkap.Answer(500, "{}");
            }
            if (!proposedBy.containsKey(id) || gone.contains(id)) {
                return new FakeNkap.Answer(404, problem("adjustment-not-found"));
            }
            boolean approved = decided.containsKey(id);
            return new FakeNkap.Answer(200, "{\"id\":\"" + id + "\",\"status\":\"" + (approved ? "approved" : "pending") + "\",\"hypothesis\":\"unrecorded_fee\""
                    + (approved ? ",\"ledgerEntryId\":\"adjustment:" + id + "\"" : "") + "}");
        }
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("/adjustments/([0-9a-f-]{36})/(approve|reject)").matcher(path);
        if (r.method().equals("POST") && m.matches()) {
            decisionPosts.add(bearer);
            String id = m.group(1);
            if (!proposedBy.containsKey(id)) {
                return new FakeNkap.Answer(404, problem("adjustment-not-found"));
            }
            if (proposedBy.get(id).equals(bearer)) {
                return new FakeNkap.Answer(403, problem("adjustment-self-approval") .replace("}", ",\"detail\":\"SERVER-DETAIL\"}"));
            }
            if (decided.putIfAbsent(id, true) != null) {
                return new FakeNkap.Answer(409, problem("adjustment-already-decided"));
            }
            ledgerEntries.incrementAndGet();
            return new FakeNkap.Answer(200, "{\"id\":\"" + id + "\",\"status\":\"approved\",\"hypothesis\":\"unrecorded_fee\",\"ledgerEntryId\":\"adjustment:" + id + "\"}");
        }
        return new FakeNkap.Answer(404, "{}");
    }

    private static String problem(String type) {
        return "{\"type\":\"https://nkap.dev/problems/" + type + "\"}";
    }

    int port() {
        return service.port();
    }

    Reply call(String method, String path, String body, String... headers) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port() + path)).timeout(Duration.ofSeconds(20));
        for (int i = 0; i + 1 < headers.length; i += 2) {
            b.header(headers[i], headers[i + 1]);
        }
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return new Reply(r.statusCode(), r.body(), r.headers().map());
    }

    Reply get(String path, String... headers) throws Exception {
        return call("GET", path, null, headers);
    }

    Reply post(String path, String json, String... headers) throws Exception {
        String[] all = new String[headers.length + 2];
        all[0] = "Content-Type";
        all[1] = "application/json";
        System.arraycopy(headers, 0, all, 2, headers.length);
        return call("POST", path, json, all);
    }

    Reply run(String caseId, String mode, String... headers) throws Exception {
        return post("/api/runs", "{\"case\":\"" + caseId + "\",\"mode\":\"" + mode + "\"}", headers);
    }

    @Override
    public void close() {
        service.close();
        nkap.close();
    }
}

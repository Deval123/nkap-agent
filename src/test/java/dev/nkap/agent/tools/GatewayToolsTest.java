package dev.nkap.agent.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class GatewayToolsTest {

    /** Obviously not a key; the tests only need a value that must never appear anywhere. */
    private static final String KEY = "TEST-ONLY-KEY-VALUE-7f3a";
    private static final String REF = "0985e8df-93aa-4949-b7aa-185e19ea41d0";
    private static final String IMPORT = "16de24bf-b9fe-425d-a28e-b0d4d57912b4";

    private record Seen(String method, String path, Map<String, List<String>> headers, int bodyLength) {
    }

    private HttpServer server;
    private final List<Seen> seen = new CopyOnWriteArrayList<>();
    private volatile int status = 200;
    private volatile String body = "{}";
    private GatewayClient client;
    private Trace trace;
    private AgentTools tools;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            byte[] in = exchange.getRequestBody().readAllBytes();
            Map<String, List<String>> headers = new java.util.TreeMap<>();
            exchange.getRequestHeaders().forEach((k, v) -> headers.put(k.toLowerCase(Locale.ROOT), v));
            seen.add(new Seen(exchange.getRequestMethod(), exchange.getRequestURI().getPath(), headers, in.length));
            byte[] out = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        server.start();
        client = new GatewayClient("http://127.0.0.1:" + server.getAddress().getPort(), () -> KEY);
        trace = new Trace();
        tools = new AgentTools(client, DocsIndex.bundled(), trace);
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    @DisplayName("every request any tool makes is a GET with no body, and carries only the agent key as a credential")
    void getOnly() {
        body = "{\"state\":\"UNKNOWN\"}";
        tools.getPayment(REF);
        tools.getStatementFindings(IMPORT);
        status = 404;
        body = "{\"type\":\"https://nkap.dev/problems/payment-not-found\",\"status\":404}";
        tools.getPayment(REF);

        assertThat(seen).hasSize(3);
        Set<String> allowed = Set.of("authorization", "host", "user-agent", "accept");
        for (Seen s : seen) {
            assertThat(s.method()).isEqualTo("GET");
            assertThat(s.bodyLength()).isZero();
            assertThat(s.headers().keySet()).isSubsetOf(allowed);
            assertThat(s.headers().get("authorization")).containsExactly("Bearer " + KEY);
        }
        assertThat(seen.get(0).path()).isEqualTo("/payments/" + REF);
        assertThat(seen.get(1).path()).isEqualTo("/statements/imports/" + IMPORT);
    }

    @Test
    @DisplayName("the client type has one public instance method that reaches the gateway, and it is get")
    void clientHasOnlyGet() {
        List<String> names = java.util.Arrays.stream(GatewayClient.class.getDeclaredMethods())
                .filter(m -> java.lang.reflect.Modifier.isPublic(m.getModifiers()))
                .filter(m -> !java.lang.reflect.Modifier.isStatic(m.getModifiers()))
                .map(java.lang.reflect.Method::getName).sorted().toList();
        assertThat(names).containsExactly("get", "toString");
    }

    @Test
    @DisplayName("an answer becomes a result: status, and the fields masked and truncated, recorded in the trace")
    void answerBecomesResult() {
        body = "{\"state\":\"UNKNOWN\",\"counterpartyMsisdn\":\"447700900004\",\"history\":[{\"operatorCode\":\""
                + "x".repeat(1110) + "\"}]}";
        ToolResult r = tools.getPayment(REF);
        assertThat(r.callId()).isEqualTo("c1");
        assertThat(r.tool()).isEqualTo("get_payment");
        assertThat(r.arguments()).containsEntry("reference", REF);
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.fields().at("/counterpartyMsisdn").asText()).isEqualTo("4477******04");
        assertThat(r.fields().at("/history/0/operatorCode").asText()).endsWith("…[truncated 610 characters]");
        assertThat(trace.results()).containsExactly(r);
        assertThat(trace.verify(new EvidenceRef("c1", "/state", "UNKNOWN")).ok()).isTrue();
    }

    @Test
    @DisplayName("a refusal by the gateway is a result with its problem type, not an exception")
    void refusalIsAResult() {
        status = 403;
        body = "{\"type\":\"https://nkap.dev/problems/admin-key-required\",\"title\":\"An admin key is required\",\"status\":403}";
        ToolResult r = tools.getStatementFindings(IMPORT);
        assertThat(r.status()).isEqualTo(403);
        assertThat(r.fields().get("type").asText()).endsWith("/admin-key-required");

        status = 404;
        body = "{\"type\":\"https://nkap.dev/problems/payment-not-found\",\"detail\":\"No payment exists for reference 447700900004.\"}";
        ToolResult missing = tools.getPayment(REF);
        assertThat(missing.status()).isEqualTo(404);
        assertThat(missing.fields().get("detail").asText()).isEqualTo("No payment exists for reference 4477******04.");
    }

    @Test
    @DisplayName("an answer that is not JSON becomes an error result holding its masked, truncated text")
    void notJson() {
        status = 502;
        body = "<html>bad gateway 447700900004 " + "y".repeat(900) + "</html>";
        ToolResult r = tools.getPayment(REF);
        assertThat(r.status()).isEqualTo(502);
        assertThat(r.fields().get("error").asText()).isEqualTo("not-json");
        assertThat(r.fields().get("body").asText()).contains("4477******04").endsWith("characters]");
    }

    @Test
    @DisplayName("a gateway that cannot be reached is an error result with status 0, and no exception reaches the caller")
    void unreachable() throws IOException {
        int closed;
        try (ServerSocket s = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            closed = s.getLocalPort();
        }
        AgentTools dead = new AgentTools(new GatewayClient("http://127.0.0.1:" + closed, () -> KEY), DocsIndex.bundled(), new Trace());
        ToolResult r = dead.getPayment(REF);
        assertThat(r.status()).isZero();
        assertThat(r.fields().get("error").asText()).isEqualTo("gateway-unreachable");
        assertThat(r.toJson()).doesNotContain(KEY);
    }

    @Test
    @DisplayName("an argument that is not a UUID is refused before any request, so it cannot reach another route")
    void invalidArguments() {
        for (String bad : List.of("../adjustments", "not-a-uuid", "", " ", REF + "/../x", REF + "?a=b", "1-2-3-4-5",
                "0985e8df-93aa-4949-b7aa-185e19ea41d0%2f")) {
            ToolResult r = tools.getPayment(bad);
            assertThat(r.status()).as(bad).isZero();
            assertThat(r.fields().get("error").asText()).as(bad).isEqualTo("invalid-argument");
            assertThat(tools.getStatementFindings(bad).fields().get("error").asText()).isEqualTo("invalid-argument");
        }
        assertThat(tools.getPayment(null).fields().get("error").asText()).isEqualTo("invalid-argument");
        assertThat(seen).isEmpty();
        assertThat(trace.results()).hasSize(17);
    }

    @Test
    @DisplayName("an upper-case UUID is accepted")
    void upperCase() {
        tools.getPayment(REF.toUpperCase(Locale.ROOT));
        assertThat(seen).hasSize(1);
    }

    @Test
    @DisplayName("with no agent key the result says so, nothing is sent, and the missing value is not invented")
    void keyMissing() {
        AgentTools none = new AgentTools(new GatewayClient("http://127.0.0.1:" + server.getAddress().getPort(), () -> ""),
                DocsIndex.bundled(), new Trace());
        ToolResult r = none.getPayment(REF);
        assertThat(r.status()).isZero();
        assertThat(r.fields().get("error").asText()).isEqualTo("agent-key-missing");
        assertThat(seen).isEmpty();
    }

    @Test
    @DisplayName("the key appears in no log line, no exception message and no toString(), whether the call works or fails")
    void keyHygiene() throws Exception {
        List<String> logged = new ArrayList<>();
        Logger root = Logger.getLogger("");
        Handler capture = new Handler() {
            @Override
            public void publish(LogRecord record) {
                logged.add(record.getLoggerName() + " " + record.getLevel() + " " + record.getMessage()
                        + " " + java.util.Arrays.toString(record.getParameters())
                        + (record.getThrown() == null ? "" : " " + record.getThrown()));
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        Level before = root.getLevel();
        root.setLevel(Level.ALL);
        root.addHandler(capture);
        capture.setLevel(Level.ALL);
        List<String> everything = new ArrayList<>();
        try {
            body = "{\"state\":\"SUCCEEDED\"}";
            everything.add(tools.getPayment(REF).toString());
            status = 403;
            everything.add(tools.getStatementFindings(IMPORT).toJson());
            int closed;
            try (ServerSocket s = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
                closed = s.getLocalPort();
            }
            GatewayClient dead = new GatewayClient("http://127.0.0.1:" + closed, () -> KEY);
            everything.add(dead.toString());
            assertThatThrownBy(() -> dead.get("/payments/" + REF)).isInstanceOf(GatewayUnavailableException.class)
                    .satisfies(e -> {
                        for (Throwable t = e; t != null; t = t.getCause()) {
                            everything.add(String.valueOf(t.getMessage()));
                            everything.add(t.toString());
                        }
                    });
            assertThatThrownBy(() -> new GatewayClient("http://127.0.0.1:1", () -> "").get("/x"))
                    .satisfies(e -> everything.add(String.valueOf(e.getMessage())));
            assertThatThrownBy(() -> client.get("not-a-path")).satisfies(e -> everything.add(String.valueOf(e.getMessage())));
        } finally {
            root.removeHandler(capture);
            root.setLevel(before);
        }
        everything.add(client.toString());
        everything.add(trace.results().toString());
        everything.addAll(logged);
        assertThat(logged).isNotEmpty();
        assertThat(String.join("\n", everything)).doesNotContain(KEY).doesNotContain("Bearer");
    }

    @Test
    @DisplayName("each call is logged as one line with the call id, the tool and the status, and nothing else")
    void logLine() {
        List<String> logged = new ArrayList<>();
        Logger logger = Logger.getLogger("dev.nkap.agent.tools");
        Handler capture = new Handler() {
            @Override
            public void publish(LogRecord record) {
                logged.add(String.format(record.getMessage().replace("{0}", "%s").replace("{1}", "%s").replace("{2}", "%s"),
                        record.getParameters() == null ? new Object[0] : record.getParameters()));
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        logger.setLevel(Level.ALL);
        logger.addHandler(capture);
        try {
            tools.getPayment(REF);
        } finally {
            logger.removeHandler(capture);
        }
        assertThat(logged).containsExactly("tool call c1 get_payment status 200");
    }

    @Test
    @DisplayName("nothing in the tools package can reach the approver's key")
    void approverKeyUnreachable() throws IOException {
        try (Stream<Path> files = Files.walk(Path.of("src/main/java/dev/nkap/agent/tools"))) {
            List<Path> java = files.filter(p -> p.toString().endsWith(".java")).toList();
            assertThat(java).isNotEmpty();
            for (Path p : java) {
                assertThat(Files.readString(p).toLowerCase(Locale.ROOT)).as(p.toString()).doesNotContain("approver");
            }
        }
    }

    @Test
    @DisplayName("the client reads the key from the environment variable NKAP_AGENT_KEY and from no other")
    void environmentVariable() throws IOException {
        String source = Files.readString(Path.of("src/main/java/dev/nkap/agent/tools/GatewayClient.java"));
        List<String> getenvs = java.util.regex.Pattern.compile("getenv\\(\"([A-Z_]+)\"\\)").matcher(source).results()
                .map(m -> m.group(1)).collect(Collectors.toList());
        assertThat(getenvs).containsExactly("NKAP_AGENT_KEY");
    }
}

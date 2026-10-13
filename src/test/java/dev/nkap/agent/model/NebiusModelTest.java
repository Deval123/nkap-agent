package dev.nkap.agent.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class NebiusModelTest {

    /** Obviously not a key; the tests only need a value that must never appear anywhere. */
    private static final String KEY = "TEST-ONLY-KEY-VALUE-9c1e";
    private static final String MODEL = "test-vendor/test-model";
    private static final String REASONING = "REASONING-TEXT-MUST-NEVER-SURFACE";
    private static final String PROMPT = "PROMPT-TEXT-NOT-FOR-THE-LOG";
    private static final String UUID = "0985e8df-93aa-4949-b7aa-185e19ea41d0";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String CALL = "{\"action\":\"call_tool\",\"tool\":\"get_payment\",\"argument\":\"" + UUID
            + "\",\"outcome\":null,\"hypothesis\":null,\"postings\":null,\"evidence\":null,\"explanation\":null}";

    private record Seen(String method, String path, Map<String, List<String>> headers, String body) {
    }

    private HttpServer server;
    private ExecutorService executor;
    private final List<Seen> seen = new CopyOnWriteArrayList<>();
    private final List<String> logs = new CopyOnWriteArrayList<>();
    private Handler logHandler;
    private final Logger logger = Logger.getLogger("dev.nkap.agent.model");

    private volatile int status = 200;
    private volatile String responseBody;
    private volatile long delayBeforeHeadersMs;
    private volatile long stallBodyMs;
    private volatile String redirectTo;

    private final List<Message> messages = List.of(
            new Message(Message.Role.SYSTEM, "You are a test. " + PROMPT),
            new Message(Message.Role.USER, "Look at " + UUID),
            new Message(Message.Role.ASSISTANT, "{\"earlier\":true}"),
            new Message(Message.Role.TOOL_RESULT, "<untrusted>{\"state\":\"UNKNOWN\"}</untrusted>"));

    @BeforeEach
    void start() throws IOException {
        responseBody = completion(CALL, "stop", 100, 189);
        executor = Executors.newCachedThreadPool();
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setExecutor(executor);
        server.createContext("/", exchange -> {
            byte[] in = exchange.getRequestBody().readAllBytes();
            Map<String, List<String>> headers = new TreeMap<>();
            exchange.getRequestHeaders().forEach((k, v) -> headers.put(k.toLowerCase(Locale.ROOT), v));
            seen.add(new Seen(exchange.getRequestMethod(), exchange.getRequestURI().getPath(), headers,
                    new String(in, StandardCharsets.UTF_8)));
            try {
                if (delayBeforeHeadersMs > 0) {
                    Thread.sleep(delayBeforeHeadersMs);
                }
                if (redirectTo != null) {
                    exchange.getResponseHeaders().add("Location", redirectTo);
                    exchange.sendResponseHeaders(302, -1);
                    exchange.close();
                    return;
                }
                byte[] out = responseBody.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, out.length);
                OutputStream body = exchange.getResponseBody();
                if (stallBodyMs > 0) {
                    body.write(out, 0, 1);
                    body.flush();
                    Thread.sleep(stallBodyMs);
                    body.write(out, 1, out.length - 1);
                } else {
                    body.write(out);
                }
                exchange.close();
            } catch (InterruptedException | IOException e) {
                exchange.close();
            }
        });
        server.start();
        logHandler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                logs.add(record.getLevel() + " " + new java.util.logging.SimpleFormatter().formatMessage(record)
                        + (record.getThrown() == null ? "" : " " + record.getThrown()));
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        logger.setLevel(Level.ALL);
        logger.addHandler(logHandler);
    }

    @AfterEach
    void stop() {
        logger.removeHandler(logHandler);
        server.stop(0);
        executor.shutdownNow();
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/";
    }

    private NebiusModel model() {
        return new NebiusModel(baseUrl(), () -> KEY, MODEL, 4000, Duration.ofSeconds(5));
    }

    private static String completion(String content, String finish, long prompt, long completion) {
        return completion(content, finish, "\"usage\":{\"prompt_tokens\":" + prompt + ",\"completion_tokens\":"
                + completion + ",\"total_tokens\":" + (prompt + completion) + "}");
    }

    private static String completion(String content, String finish, String usageMember) {
        String contentJson = content == null ? "null" : JSON.valueToTree(content).toString();
        return "{\"id\":\"cmpl-1\",\"object\":\"chat.completion\",\"choices\":[{\"index\":0,\"message\":{\"role\":"
                + "\"assistant\",\"content\":" + contentJson + ",\"reasoning\":\"" + REASONING
                + "\",\"reasoning_content\":\"" + REASONING + "\"},\"finish_reason\":\"" + finish + "\","
                + "\"prompt_token_ids\":null}]" + (usageMember.isEmpty() ? "" : "," + usageMember) + "}";
    }

    private ModelResult.Failed failed(ModelResult result) {
        assertThat(result).isInstanceOf(ModelResult.Failed.class);
        return (ModelResult.Failed) result;
    }

    // --- the request

    @Test
    @DisplayName("the request is one POST carrying the strict schema, temperature 0, the token bound, and the bearer key as its only credential")
    void theRequest() throws Exception {
        ModelResult result = model().next(messages);

        assertThat(result).isInstanceOf(ModelResult.Answered.class);
        assertThat(seen).hasSize(1);
        Seen request = seen.get(0);
        assertThat(request.method()).isEqualTo("POST");
        assertThat(request.path()).isEqualTo("/v1/chat/completions");
        assertThat(request.headers().get("authorization")).containsExactly("Bearer " + KEY);
        assertThat(request.headers().keySet()).doesNotContain("cookie", "x-api-key", "api-key", "proxy-authorization");
        assertThat(request.headers().get("content-type")).first().asString().startsWith("application/json");

        JsonNode body = JSON.readTree(request.body());
        assertThat(body.get("model").asText()).isEqualTo(MODEL);
        assertThat(body.get("temperature").asInt(-1)).isZero();
        assertThat(body.get("max_tokens").asInt()).isEqualTo(4000);
        assertThat(body.has("stream")).isFalse();
        assertThat(body.has("tools")).isFalse();
        JsonNode format = body.get("response_format");
        assertThat(format.get("type").asText()).isEqualTo("json_schema");
        assertThat(format.get("json_schema").get("strict").asBoolean()).isTrue();
        assertThat(format.get("json_schema").get("name").asText()).isEqualTo(AnswerSchema.NAME);
        assertThat(format.get("json_schema").get("schema")).isEqualTo(AnswerSchema.schema());

        JsonNode sent = body.get("messages");
        assertThat(sent).hasSize(4);
        assertThat(sent.get(0).get("role").asText()).isEqualTo("system");
        assertThat(sent.get(1).get("role").asText()).isEqualTo("user");
        assertThat(sent.get(2).get("role").asText()).isEqualTo("assistant");
        assertThat(sent.get(3).get("role").asText()).as("a tool result is data, sent as a user message").isEqualTo("user");
        assertThat(sent.get(3).get("content").asText()).isEqualTo("<untrusted>{\"state\":\"UNKNOWN\"}</untrusted>");
    }

    @Test
    @DisplayName("the base URL may or may not end with a slash")
    void baseUrlSlash() {
        new NebiusModel(baseUrl().replaceAll("/$", ""), () -> KEY, MODEL, 4000, Duration.ofSeconds(5)).next(messages);
        model().next(messages);
        assertThat(seen).extracting(Seen::path).containsExactly("/v1/chat/completions", "/v1/chat/completions");
    }

    @Test
    @DisplayName("the token bound is the configured one, and a bound of zero, a negative one or an absurd one is refused")
    void tokenBound() throws Exception {
        new NebiusModel(baseUrl(), () -> KEY, MODEL, 1234, Duration.ofSeconds(5)).next(messages);
        assertThat(JSON.readTree(seen.get(0).body()).get("max_tokens").asInt()).isEqualTo(1234);
        assertThat(NebiusModel.DEFAULT_MAX_TOKENS).isEqualTo(4000);
        for (int bad : new int[] {0, -1, NebiusModel.MAX_TOKENS_LIMIT + 1}) {
            assertThatThrownBy(() -> new NebiusModel(baseUrl(), () -> KEY, MODEL, bad, Duration.ofSeconds(5)))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> new NebiusModel(baseUrl(), () -> KEY, MODEL, 100, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("no key means no request: the failure is a value and nothing is sent")
    void noKey() {
        for (Supplier<String> none : List.<Supplier<String>>of(() -> null, () -> "", () -> "  ",
                () -> {
                    throw new IllegalStateException("boom");
                })) {
            ModelResult.Failed failure = failed(new NebiusModel(baseUrl(), none, MODEL, 100, Duration.ofSeconds(5))
                    .next(messages));
            assertThat(failure.reason()).isEqualTo(ModelResult.Reason.NO_KEY);
        }
        assertThat(seen).isEmpty();
    }

    // --- the answer

    @Test
    @DisplayName("a conforming answer comes back parsed, with the usage the provider reported")
    void answered() {
        ModelResult result = model().next(messages);
        assertThat(result).isInstanceOf(ModelResult.Answered.class);
        ModelResult.Answered answered = (ModelResult.Answered) result;
        assertThat(answered.answer()).isEqualTo(new ModelAnswer.CallTool("get_payment", UUID));
        assertThat(answered.usage()).isEqualTo(new Usage(100, 189));
    }

    @Test
    @DisplayName("the reasoning text never reaches the result, the log or any toString")
    void reasoningIsNeverUsed() {
        ModelResult result = model().next(messages);
        assertThat(result.toString()).doesNotContain(REASONING);
        assertThat(String.join("\n", logs)).doesNotContain(REASONING);

        responseBody = completion("not an answer", "stop", 5, 5);
        ModelResult.Failed failure = failed(model().next(messages));
        assertThat(failure.toString()).doesNotContain(REASONING);
        assertThat(failure.detail()).doesNotContain(REASONING);
        assertThat(String.join("\n", logs)).doesNotContain(REASONING);
    }

    @Test
    @DisplayName("a reasoning field alone is not an answer: with no content it is a failure, not a fallback")
    void reasoningIsNotAFallback() {
        responseBody = completion(null, "stop", 5, 5);
        ModelResult.Failed failure = failed(model().next(messages));
        assertThat(failure.reason()).isEqualTo(ModelResult.Reason.EMPTY);
        assertThat(failure.toString()).doesNotContain(REASONING);
    }

    // --- every failure is a value

    @Test
    @DisplayName("a 4xx and a 5xx status are failures that carry the status and not the body")
    void httpStatus() {
        for (int code : new int[] {400, 401, 403, 404, 429, 500, 503}) {
            status = code;
            responseBody = "{\"error\":\"" + PROMPT + " " + KEY + "\"}";
            ModelResult.Failed failure = failed(model().next(messages));
            assertThat(failure.reason()).isEqualTo(ModelResult.Reason.HTTP_STATUS);
            assertThat(failure.detail()).isEqualTo("HTTP " + code);
            assertThat(failure.toString()).doesNotContain(PROMPT).doesNotContain(KEY);
        }
    }

    @Test
    @DisplayName("there is no retry: one failed call is one request")
    void noRetry() {
        status = 503;
        failed(model().next(messages));
        assertThat(seen).hasSize(1);
    }

    @Test
    @DisplayName("a redirect is not followed: the key is not sent anywhere else")
    void noRedirect() throws Exception {
        try (ServerSocket other = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            redirectTo = "http://127.0.0.1:" + other.getLocalPort() + "/elsewhere";
            other.setSoTimeout(500);
            ModelResult.Failed failure = failed(model().next(messages));
            assertThat(failure.reason()).isEqualTo(ModelResult.Reason.HTTP_STATUS);
            assertThat(failure.detail()).isEqualTo("HTTP 302");
            assertThatThrownBy(other::accept).isInstanceOf(java.net.SocketTimeoutException.class);
        }
    }

    @Test
    @DisplayName("a body that is not JSON, and JSON that is not a chat completion, are failures")
    void notAChatCompletion() {
        responseBody = "<html>gateway error</html>";
        assertThat(failed(model().next(messages)).reason()).isEqualTo(ModelResult.Reason.NOT_JSON);
        for (String body : new String[] {"[]", "{}", "{\"choices\":[]}", "{\"choices\":[{}]}",
                "{\"choices\":[{\"message\":{},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1}}"}) {
            responseBody = body;
            assertThat(failed(model().next(messages)).reason()).as(body).isIn(
                    ModelResult.Reason.MALFORMED_RESPONSE, ModelResult.Reason.EMPTY);
        }
    }

    @Test
    @DisplayName("an answer that is not JSON, or does not match the schema, is a schema failure naming the field")
    void schemaFailures() {
        responseBody = completion("Sure! The fee was 75.", "stop", 5, 5);
        ModelResult.Failed prose = failed(model().next(messages));
        assertThat(prose.reason()).isEqualTo(ModelResult.Reason.SCHEMA);
        assertThat(prose.detail()).contains("not JSON").doesNotContain("Sure");

        responseBody = completion(CALL.replace("get_payment", "get_balance"), "stop", 5, 5);
        ModelResult.Failed unknownTool = failed(model().next(messages));
        assertThat(unknownTool.reason()).isEqualTo(ModelResult.Reason.SCHEMA);
        assertThat(unknownTool.detail()).contains("tool").doesNotContain("get_balance");
        assertThat(unknownTool.usage()).as("the tokens were spent all the same").isEqualTo(new Usage(5, 5));
    }

    @Test
    @DisplayName("an answer cut off by the token bound is not an answer, even if what came back parses")
    void truncated() {
        responseBody = completion(CALL, "length", 100, 4000);
        ModelResult.Failed failure = failed(model().next(messages));
        assertThat(failure.reason()).isEqualTo(ModelResult.Reason.TRUNCATED);
        assertThat(failure.usage()).isEqualTo(new Usage(100, 4000));
    }

    @Test
    @DisplayName("a refusal, and a content filter, are failures")
    void refusal() {
        responseBody = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":null,"
                + "\"refusal\":\"I cannot help with that " + PROMPT + "\"},\"finish_reason\":\"stop\"}],"
                + "\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":4}}";
        ModelResult.Failed refused = failed(model().next(messages));
        assertThat(refused.reason()).isEqualTo(ModelResult.Reason.REFUSAL);
        assertThat(refused.toString()).doesNotContain(PROMPT);

        responseBody = completion(CALL, "content_filter", 3, 4);
        assertThat(failed(model().next(messages)).reason()).isEqualTo(ModelResult.Reason.REFUSAL);
    }

    @Test
    @DisplayName("any other finish reason is a failure too")
    void otherFinishReasons() {
        responseBody = completion(CALL, "tool_calls", 3, 4);
        assertThat(failed(model().next(messages)).reason()).isEqualTo(ModelResult.Reason.MALFORMED_RESPONSE);
    }

    @Test
    @DisplayName("usage that is missing, negative or not a number is a failure: the count cannot be trusted")
    void usageIsChecked() {
        for (String usage : new String[] {"", "\"usage\":null", "\"usage\":{}",
                "\"usage\":{\"prompt_tokens\":-1,\"completion_tokens\":5}",
                "\"usage\":{\"prompt_tokens\":5,\"completion_tokens\":-1}",
                "\"usage\":{\"prompt_tokens\":\"5\",\"completion_tokens\":5}",
                "\"usage\":{\"prompt_tokens\":5.5,\"completion_tokens\":5}",
                "\"usage\":{\"prompt_tokens\":5}"}) {
            responseBody = completion(CALL, "stop", usage);
            ModelResult.Failed failure = failed(model().next(messages));
            assertThat(failure.reason()).as(usage).isEqualTo(ModelResult.Reason.BAD_USAGE);
            assertThat(failure.usage()).as(usage).isNull();
        }
    }

    @Test
    @DisplayName("a response larger than the cap is not read past it, and is a failure")
    void sizeCap() {
        assertThat(NebiusModel.MAX_RESPONSE_BYTES).isEqualTo(256 * 1024);
        responseBody = completion(CALL, "stop", 1, 1).replace("\"id\":\"cmpl-1\"",
                "\"id\":\"" + "x".repeat(NebiusModel.MAX_RESPONSE_BYTES) + "\"");
        ModelResult.Failed failure = failed(model().next(messages));
        assertThat(failure.reason()).isEqualTo(ModelResult.Reason.TOO_LARGE);
    }

    @Test
    @DisplayName("a response that is slow to start, and one that starts and stalls, are timeouts")
    void timeouts() {
        NebiusModel quick = new NebiusModel(baseUrl(), () -> KEY, MODEL, 100, Duration.ofMillis(400));
        delayBeforeHeadersMs = 1500;
        assertThat(failed(quick.next(messages)).reason()).isEqualTo(ModelResult.Reason.TIMEOUT);

        delayBeforeHeadersMs = 0;
        stallBodyMs = 1500;
        long begun = System.nanoTime();
        assertThat(failed(quick.next(messages)).reason()).isEqualTo(ModelResult.Reason.TIMEOUT);
        assertThat(Duration.ofNanos(System.nanoTime() - begun)).isLessThan(Duration.ofMillis(1400));
    }

    @Test
    @DisplayName("a server that is not there is a failure, not an exception")
    void unreachable() throws IOException {
        int port;
        try (ServerSocket free = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            port = free.getLocalPort();
        }
        ModelResult.Failed failure = failed(new NebiusModel("http://127.0.0.1:" + port + "/v1/", () -> KEY, MODEL, 100,
                Duration.ofSeconds(2)).next(messages));
        assertThat(failure.reason()).isEqualTo(ModelResult.Reason.NETWORK);
    }

    @Test
    @DisplayName("a call with no messages is a failure, not a request")
    void noMessages() {
        assertThat(failed(model().next(List.of())).reason()).isEqualTo(ModelResult.Reason.INVALID_REQUEST);
        assertThat(seen).isEmpty();
    }

    // --- the key, and the log

    @Test
    @DisplayName("the key is in no log line, no failure, and no toString, whatever goes wrong")
    void keyHygiene() {
        List<ModelResult> results = new ArrayList<>();
        results.add(model().next(messages));
        for (int code : new int[] {401, 500}) {
            status = code;
            results.add(model().next(messages));
        }
        status = 200;
        responseBody = "KEY " + KEY + " not json";
        results.add(model().next(messages));
        responseBody = completion(KEY, "stop", 1, 1);
        results.add(model().next(messages));
        results.add(new NebiusModel("http://127.0.0.1:1/v1/", () -> KEY, MODEL, 100, Duration.ofSeconds(1)).next(messages));

        for (ModelResult result : results) {
            assertThat(result.toString()).doesNotContain(KEY);
            if (result instanceof ModelResult.Failed failed) {
                assertThat(failed.detail()).doesNotContain(KEY);
            }
        }
        assertThat(String.join("\n", logs)).doesNotContain(KEY);
        assertThat(model().toString()).doesNotContain(KEY).contains(MODEL);
    }

    @Test
    @DisplayName("each call logs one line with the model, the status, the latency and the token counts, and nothing of the prompt or the answer")
    void logLine() {
        model().next(messages);
        List<String> lines = logs.stream().filter(l -> l.contains(MODEL)).toList();
        assertThat(lines).hasSize(1);
        assertThat(lines.get(0)).contains("status=200", "promptTokens=100", "completionTokens=189", "ms=");
        String all = String.join("\n", logs);
        assertThat(all).doesNotContain(PROMPT).doesNotContain(UUID).doesNotContain("get_payment");
    }
}

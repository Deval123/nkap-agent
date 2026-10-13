package dev.nkap.agent.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * The model over an OpenAI-compatible chat endpoint (Nebius Token Factory). One request per call: no retry,
 * no redirect, a bounded {@code max_tokens}, one timeout for the whole exchange, and no more than
 * {@link #MAX_RESPONSE_BYTES} read from the response.
 *
 * <p>The key comes from a {@link Supplier} for each call and is held in no field, so it is in no
 * {@code toString()}; it is sent as the bearer credential and appears nowhere else, not in a message, not in a
 * log line. At the composition point the supplier reads {@code NEBIUS_API_KEY}.
 *
 * <p>The model's reasoning text, which the provider returns in {@code reasoning} and
 * {@code reasoning_content}, is never read, stored or passed on. Only {@code content}, {@code finish_reason},
 * {@code refusal} and {@code usage} are looked at. Its tokens are billed as completion tokens and are
 * counted in {@link Usage}.
 */
public final class NebiusModel implements AgentModel {

    public static final int DEFAULT_MAX_TOKENS = 4000;
    public static final int MAX_TOKENS_LIMIT = 32_768;
    public static final int MAX_RESPONSE_BYTES = 256 * 1024;

    private static final System.Logger LOG = System.getLogger("dev.nkap.agent.model");
    private static final ObjectMapper JSON = new ObjectMapper();

    private final String baseUrl;
    private final Supplier<String> key;
    private final String model;
    private final int maxTokens;
    private final Duration timeout;
    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    public NebiusModel(String baseUrl, Supplier<String> key, String model, int maxTokens, Duration timeout) {
        if (maxTokens < 1 || maxTokens > MAX_TOKENS_LIMIT) {
            throw new IllegalArgumentException("max_tokens is between 1 and " + MAX_TOKENS_LIMIT);
        }
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("the timeout is positive");
        }
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl : baseUrl + "/";
        this.key = key;
        this.model = model;
        this.maxTokens = maxTokens;
        this.timeout = timeout;
    }

    /** The supplier the application uses: the value of {@code NEBIUS_API_KEY}, read when a call is made. */
    public static Supplier<String> keyFromEnvironment() {
        return () -> System.getenv("NEBIUS_API_KEY");
    }

    @Override
    public ModelResult next(List<Message> messages) {
        long begun = System.nanoTime();
        if (messages == null || messages.isEmpty()) {
            return new ModelResult.Failed(ModelResult.Reason.INVALID_REQUEST, "there are no messages to send", null);
        }
        String credential = credential();
        if (credential == null) {
            return new ModelResult.Failed(ModelResult.Reason.NO_KEY, "the model key is not set", null);
        }
        Exchange exchange = exchange(messages, credential, begun);
        int status = exchange.status();
        ModelResult result = exchange.failure() != null ? exchange.failure() : interpret(exchange.body());
        Usage usage = null;
        if (result instanceof ModelResult.Answered a) {
            usage = a.usage();
        } else if (result instanceof ModelResult.Failed f) {
            usage = f.usage();
        }
        log(status, begun, usage, result);
        return result;
    }

    private String credential() {
        try {
            String value = key.get();
            return value == null || value.isBlank() ? null : value;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private record Exchange(int status, String body, ModelResult.Failed failure) {
    }

    private Exchange exchange(List<Message> messages, String credential, long begun) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "chat/completions"))
                .timeout(timeout)
                .header("Authorization", "Bearer " + credential)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body(messages)))
                .build();
        long deadline = begun + timeout.toNanos();
        InputStream stream = null;
        try {
            HttpResponse<InputStream> response = http.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream())
                    .get(timeout.toNanos(), TimeUnit.NANOSECONDS);
            stream = response.body();
            int status = response.statusCode();
            if (status != 200) {
                return new Exchange(status, null, new ModelResult.Failed(
                        ModelResult.Reason.HTTP_STATUS, "HTTP " + status, null));
            }
            InputStream toRead = stream;
            CompletableFuture<byte[]> read = CompletableFuture.supplyAsync(() -> {
                try {
                    return toRead.readNBytes(MAX_RESPONSE_BYTES + 1);
                } catch (IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            });
            byte[] bytes = read.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            if (bytes.length > MAX_RESPONSE_BYTES) {
                return new Exchange(status, null, new ModelResult.Failed(ModelResult.Reason.TOO_LARGE,
                        "the response is larger than " + MAX_RESPONSE_BYTES + " bytes", null));
            }
            return new Exchange(status, new String(bytes, StandardCharsets.UTF_8), null);
        } catch (TimeoutException e) {
            return failure(ModelResult.Reason.TIMEOUT, "the model did not answer within " + timeout.toMillis() + " ms");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof HttpTimeoutException) {
                return failure(ModelResult.Reason.TIMEOUT, "the model did not answer within " + timeout.toMillis() + " ms");
            }
            return failure(ModelResult.Reason.NETWORK, "the model could not be reached");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return failure(ModelResult.Reason.NETWORK, "the call was interrupted");
        } finally {
            if (stream != null) {
                try {
                    stream.close();
                } catch (IOException ignored) {
                    // nothing to do
                }
            }
        }
    }

    private static Exchange failure(ModelResult.Reason reason, String detail) {
        return new Exchange(0, null, new ModelResult.Failed(reason, detail, null));
    }

    private String body(List<Message> messages) {
        ObjectNode body = JSON.createObjectNode();
        body.put("model", model);
        body.put("temperature", 0);
        body.put("max_tokens", maxTokens);
        ArrayNode sent = body.putArray("messages");
        for (Message message : messages) {
            ObjectNode node = sent.addObject();
            node.put("role", message.wireRole());
            node.put("content", message.content());
        }
        body.set("response_format", AnswerSchema.responseFormat());
        return body.toString();
    }

    private ModelResult interpret(String body) {
        JsonNode root;
        try {
            root = JSON.readTree(body);
        } catch (IOException | RuntimeException e) {
            return fail(ModelResult.Reason.NOT_JSON, "the response is not JSON", null);
        }
        if (root == null || !root.isObject()) {
            return fail(ModelResult.Reason.MALFORMED_RESPONSE, "the response is not a chat completion", null);
        }
        Usage usage = usage(root.get("usage"));
        JsonNode choices = root.get("choices");
        if (choices == null || !choices.isArray() || choices.isEmpty() || !choices.get(0).isObject()) {
            return fail(ModelResult.Reason.MALFORMED_RESPONSE, "the response has no choice", usage);
        }
        JsonNode choice = choices.get(0);
        JsonNode message = choice.get("message");
        JsonNode finish = choice.get("finish_reason");
        if (message == null || !message.isObject() || finish == null || !finish.isTextual()) {
            return fail(ModelResult.Reason.MALFORMED_RESPONSE, "the choice has no message or no finish reason", usage);
        }
        JsonNode refusal = message.get("refusal");
        if (refusal != null && refusal.isTextual() && !refusal.asText().isEmpty()
                || finish.asText().equals("content_filter")) {
            return fail(ModelResult.Reason.REFUSAL, "the model refused", usage);
        }
        if (finish.asText().equals("length")) {
            return fail(ModelResult.Reason.TRUNCATED, "the model was cut off at the token bound", usage);
        }
        if (!finish.asText().equals("stop")) {
            return fail(ModelResult.Reason.MALFORMED_RESPONSE, "the finish reason is not stop", usage);
        }
        JsonNode content = message.get("content");
        if (content == null || !content.isTextual() || content.asText().isBlank()) {
            return fail(ModelResult.Reason.EMPTY, "the model wrote no answer", usage);
        }
        if (usage == null) {
            return fail(ModelResult.Reason.BAD_USAGE, "the usage is missing or not a count", null);
        }
        try {
            return new ModelResult.Answered(AnswerSchema.parse(content.asText()), usage);
        } catch (SchemaViolation e) {
            return fail(ModelResult.Reason.SCHEMA, e.getMessage(), usage);
        }
    }

    private static ModelResult.Failed fail(ModelResult.Reason reason, String detail, Usage usage) {
        return new ModelResult.Failed(reason, detail, usage);
    }

    /** The usage if both counts are whole, non-negative numbers; otherwise null. */
    private static Usage usage(JsonNode node) {
        if (node == null || !node.isObject()) {
            return null;
        }
        JsonNode prompt = node.get("prompt_tokens");
        JsonNode completion = node.get("completion_tokens");
        if (prompt == null || completion == null || !prompt.isIntegralNumber() || !completion.isIntegralNumber()
                || !prompt.canConvertToLong() || !completion.canConvertToLong()
                || prompt.asLong() < 0 || completion.asLong() < 0) {
            return null;
        }
        return new Usage(prompt.asLong(), completion.asLong());
    }

    private void log(int status, long begun, Usage usage, ModelResult result) {
        long ms = Duration.ofNanos(System.nanoTime() - begun).toMillis();
        String outcome = result instanceof ModelResult.Failed f ? "failed:" + f.reason() : "answered";
        LOG.log(System.Logger.Level.INFO, "model call model={0} status={1} ms={2} promptTokens={3} completionTokens={4} result={5}",
                model, status, ms, usage == null ? "none" : usage.promptTokens(),
                usage == null ? "none" : usage.completionTokens(), outcome);
    }

    @Override
    public String toString() {
        return "NebiusModel[" + baseUrl + ", " + model + ", maxTokens=" + maxTokens + "]";
    }
}

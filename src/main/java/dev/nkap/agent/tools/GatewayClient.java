package dev.nkap.agent.tools;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.function.Supplier;

/**
 * Reads from the gateway, and can do nothing else: the one method that reaches it is {@link #get}. The
 * request carries the agent's key as its only credential and has no body.
 *
 * <p>The key is asked for from a {@link Supplier} for each request and is held in no field, so it is in no
 * {@code toString()}. It is never put in a message: an exception names the kind of failure, not the
 * request. At the composition point the supplier reads {@code NKAP_AGENT_KEY} from the environment.
 */
public final class GatewayClient {

    /** The gateway's answer: the HTTP status and the body as text. */
    public record Response(int status, String body) {
    }

    private final String baseUrl;
    private final Supplier<String> agentKey;
    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(3))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    public GatewayClient(String baseUrl, Supplier<String> agentKey) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.agentKey = agentKey;
    }

    /** The supplier the application uses: the value of {@code NKAP_AGENT_KEY}, read when a request is made. */
    public static Supplier<String> agentKeyFromEnvironment() {
        return () -> System.getenv("NKAP_AGENT_KEY");
    }

    public Response get(String path) throws GatewayUnavailableException {
        if (path == null || !path.startsWith("/") || path.chars().anyMatch(c -> c <= 0x20 || c == 0x7f)) {
            throw new IllegalArgumentException("a path starts with / and holds no space or control character");
        }
        String key = agentKey.get();
        if (key == null || key.isBlank()) {
            throw new IllegalStateException("the agent key is not set");
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(10))
                .header("Authorization", "Bearer " + key)
                .header("Accept", "application/json")
                .GET()
                .build();
        try {
            HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
            return new Response(response.statusCode(), new String(response.body(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new GatewayUnavailableException("the gateway did not answer (" + e.getClass().getSimpleName() + ")");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GatewayUnavailableException("the request was interrupted");
        }
    }

    @Override
    public String toString() {
        return "GatewayClient[" + baseUrl + "]";
    }
}

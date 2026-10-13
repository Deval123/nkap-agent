package dev.nkap.agent.approval;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiPredicate;
import java.util.function.Supplier;

/**
 * What both clients share: HTTP/1.1, no redirect, one timeout for the whole exchange, a response read to at most
 * 64 KB, the key asked for on every request and held in no field, https or loopback only, and no retry. A
 * request whose method and path are not allowed is refused before anything is sent.
 *
 * <p>Nothing here logs more than the method, the shape of the route and the status.
 */
final class NkapHttp {

    static final int MAX_RESPONSE_BYTES = 64 * 1024;
    private static final System.Logger LOG = System.getLogger("dev.nkap.agent.approval");

    /** The answer, or the reason there is none. Exactly one of {@code failure} and a status above zero. */
    record Response(int status, String body, Refusal failure) {
    }

    private final String baseUrl;
    private final Supplier<String> key;
    private final BiPredicate<String, String> allowed;
    private final Duration timeout;
    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(3))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    NkapHttp(String baseUrl, Supplier<String> key, BiPredicate<String, String> allowed, Duration timeout) {
        if (baseUrl == null || !(baseUrl.startsWith("https://") || baseUrl.matches("http://(127\\.0\\.0\\.1|localhost)(:\\d+)?/?"))) {
            throw new IllegalArgumentException("the base URL is https, or a loopback address: the key is not sent in clear");
        }
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.key = key;
        this.allowed = allowed;
        this.timeout = timeout;
    }

    Response send(String method, String path, String body) {
        if (path == null || path.chars().anyMatch(c -> c <= 0x20 || c == 0x7f) || !allowed.test(method, path)) {
            throw new IllegalArgumentException("that method and path are not allowed for this client");
        }
        String credential = credential();
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(timeout)
                .header("Authorization", "Bearer " + credential)
                .header("Accept", "application/json");
        if (body == null) {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            builder.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(body));
        }
        Response response = exchange(builder.build());
        LOG.log(System.Logger.Level.INFO, "nkap call {0} {1} status {2}", method,
                path.replaceAll("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}", "{id}"),
                response.failure() != null ? response.failure().code() : Integer.toString(response.status()));
        return response;
    }

    private String credential() {
        String value;
        try {
            value = key.get();
        } catch (RuntimeException e) {
            throw new IllegalStateException("the key is not available");
        }
        if (value == null || value.isBlank() || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
            throw new IllegalStateException("the key is not set or cannot be used");
        }
        return value;
    }

    private Response exchange(HttpRequest request) {
        long deadline = System.nanoTime() + timeout.toNanos();
        InputStream stream = null;
        try {
            HttpResponse<InputStream> response = http.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream())
                    .get(timeout.toNanos(), TimeUnit.NANOSECONDS);
            stream = response.body();
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
                return new Response(0, null, new Refusal("unexpected", "Nkap's answer was too large to read."));
            }
            return new Response(response.statusCode(), new String(bytes, StandardCharsets.UTF_8), null);
        } catch (TimeoutException e) {
            return unreachable("Nkap did not answer in time.");
        } catch (ExecutionException e) {
            return e.getCause() instanceof HttpTimeoutException ? unreachable("Nkap did not answer in time.")
                    : unreachable("Nkap could not be reached.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return unreachable("The call was interrupted.");
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

    private static Response unreachable(String text) {
        return new Response(0, null, new Refusal("unreachable", text));
    }

    @Override
    public String toString() {
        return "NkapHttp[" + baseUrl + "]";
    }
}

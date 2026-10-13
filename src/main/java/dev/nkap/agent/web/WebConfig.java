package dev.nkap.agent.web;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.function.Supplier;

/**
 * The service's configuration, read from the environment, strictly: a value that does not parse, a required one
 * that is missing, a key given both as a variable and as a file, or a key file that cannot be read, stops the
 * service at start. The message names the variable and never the value.
 *
 * <p>A key is read from its variable or its file each time it is used, not once, and is held in no field here;
 * {@code toString()} shows no key. Live runs are on only if a model key is configured and the model's base URL is
 * https or a loopback address; otherwise they are off, which is not an error.
 *
 * <p>Variables: {@code NKAP_AGENT_PORT} (8088), {@code NKAP_AGENT_BIND} (127.0.0.1), {@code NKAP_GATEWAY_URL}
 * (http://127.0.0.1:8080), {@code NKAP_AGENT_MANIFEST} (required), {@code NKAP_STATEMENTS_DIR} (statements beside the
 * manifest), {@code NKAP_AGENT_TRACES_DIR} (demo/traces), {@code NKAP_AGENT_STATE_DIR} (state),
 * {@code NKAP_AGENT_KEY}[{@code _FILE}], {@code NKAP_APPROVER_KEY}[{@code _FILE}] and {@code NKAP_AGENT_SALT}[{@code _FILE}]
 * (all required), {@code NEBIUS_API_KEY}[{@code _FILE}] (optional), {@code NEBIUS_BASE_URL}, {@code NEBIUS_MODEL},
 * {@code NKAP_AGENT_TRUSTED_PROXY}, {@code NKAP_AGENT_LIVE_DAILY_CAP} (40), {@code NKAP_AGENT_LIVE_COOLDOWN_SECONDS} (300),
 * {@code NKAP_AGENT_SCRIPTED_DAILY_CAP} (200), {@code NKAP_AGENT_SCRIPTED_COOLDOWN_SECONDS} (20).
 */
public final class WebConfig {

    private static final String LOOPBACK_OR_HTTPS = "https://.+|http://(127\\.0\\.0\\.1|localhost)(:\\d+)?(/.*)?";

    private final Map<String, String> env;
    private final int port;
    private final String bind;
    private final String gatewayUrl;
    private final Path manifest;
    private final Path statementsDir;
    private final Path tracesDir;
    private final Path stateDir;
    private final String trustedProxy;
    private final int liveDailyCap;
    private final Duration liveCooldown;
    private final int scriptedDailyCap;
    private final Duration scriptedCooldown;
    private final boolean liveEnabled;
    private final String nebiusBaseUrl;
    private final String nebiusModel;

    private WebConfig(Map<String, String> env) {
        this.env = Map.copyOf(env);
        this.port = integer("NKAP_AGENT_PORT", 8088, 0, 65535);
        this.bind = text("NKAP_AGENT_BIND", "127.0.0.1");
        this.gatewayUrl = text("NKAP_GATEWAY_URL", "http://127.0.0.1:8080");
        if (!gatewayUrl.matches(LOOPBACK_OR_HTTPS)) {
            throw bad("NKAP_GATEWAY_URL");
        }
        String manifestValue = env.get("NKAP_AGENT_MANIFEST");
        if (manifestValue == null || manifestValue.isBlank()) {
            throw new IllegalArgumentException("NKAP_AGENT_MANIFEST is required");
        }
        this.manifest = Path.of(manifestValue);
        Path parent = manifest.toAbsolutePath().getParent();
        this.statementsDir = env.containsKey("NKAP_STATEMENTS_DIR") ? Path.of(text("NKAP_STATEMENTS_DIR", ""))
                : (parent == null ? Path.of(".") : parent).resolve("statements");
        this.tracesDir = Path.of(text("NKAP_AGENT_TRACES_DIR", "demo/traces"));
        this.stateDir = Path.of(text("NKAP_AGENT_STATE_DIR", "state"));
        String proxy = env.get("NKAP_AGENT_TRUSTED_PROXY");
        this.trustedProxy = proxy == null || proxy.isBlank() ? null : proxy.strip();
        this.liveDailyCap = integer("NKAP_AGENT_LIVE_DAILY_CAP", 40, 1, 100_000);
        this.liveCooldown = Duration.ofSeconds(integer("NKAP_AGENT_LIVE_COOLDOWN_SECONDS", 300, 0, 86_400));
        this.scriptedDailyCap = integer("NKAP_AGENT_SCRIPTED_DAILY_CAP", 200, 1, 100_000);
        this.scriptedCooldown = Duration.ofSeconds(integer("NKAP_AGENT_SCRIPTED_COOLDOWN_SECONDS", 20, 0, 86_400));
        requireKey("NKAP_AGENT_KEY");
        requireKey("NKAP_APPROVER_KEY");
        requireKey("NKAP_AGENT_SALT");
        boolean nebiusKey = configured("NEBIUS_API_KEY");
        if (nebiusKey) {
            requireKey("NEBIUS_API_KEY");
        }
        this.nebiusBaseUrl = text("NEBIUS_BASE_URL", "https://api.tokenfactory.nebius.com/v1/");
        this.nebiusModel = text("NEBIUS_MODEL", dev.nkap.agent.loop.RunCli.DEFAULT_MODEL);
        this.liveEnabled = nebiusKey && nebiusBaseUrl.matches(LOOPBACK_OR_HTTPS);
    }

    public static WebConfig from(Map<String, String> env) {
        return new WebConfig(env);
    }

    private IllegalArgumentException bad(String name) {
        return new IllegalArgumentException("the value of " + name + " is not valid");
    }

    private String text(String name, String fallback) {
        String value = env.get(name);
        return value == null || value.isBlank() ? fallback : value.strip();
    }

    private int integer(String name, int fallback, int min, int max) {
        String value = env.get(name);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        if (!value.strip().matches("[0-9]{1,9}")) {
            throw bad(name);
        }
        int n = Integer.parseInt(value.strip());
        if (n < min || n > max) {
            throw bad(name);
        }
        return n;
    }

    private boolean configured(String name) {
        return (env.get(name) != null && !env.get(name).isBlank()) || (env.get(name + "_FILE") != null && !env.get(name + "_FILE").isBlank());
    }

    private void requireKey(String name) {
        boolean variable = env.get(name) != null && !env.get(name).isBlank();
        boolean file = env.get(name + "_FILE") != null && !env.get(name + "_FILE").isBlank();
        if (variable && file) {
            throw new IllegalArgumentException(name + " is given both as a variable and as " + name + "_FILE: give one");
        }
        if (!variable && !file) {
            throw new IllegalArgumentException(name + " (or " + name + "_FILE) is required");
        }
        if (read(name).isBlank()) {
            throw new IllegalArgumentException(variable ? name + " is blank" : name + "_FILE cannot be read or is empty");
        }
    }

    /** The secret behind a name, read now: from the variable, or from the file it names. Empty if it cannot be read. */
    private String read(String name) {
        String variable = env.get(name);
        if (variable != null && !variable.isBlank()) {
            return variable.strip();
        }
        String file = env.get(name + "_FILE");
        if (file == null || file.isBlank()) {
            return "";
        }
        try {
            return Files.readString(Path.of(file.strip()), StandardCharsets.UTF_8).strip();
        } catch (IOException | RuntimeException e) {
            return "";
        }
    }

    private Supplier<String> source(String name) {
        return () -> read(name);
    }

    public int port() {
        return port;
    }

    public String bind() {
        return bind;
    }

    public String gatewayUrl() {
        return gatewayUrl;
    }

    public Path manifest() {
        return manifest;
    }

    public Path statementsDir() {
        return statementsDir;
    }

    public Path tracesDir() {
        return tracesDir;
    }

    public Path stateDir() {
        return stateDir;
    }

    /** The one peer allowed to name a visitor through {@code X-Forwarded-For}, or null. */
    public String trustedProxy() {
        return trustedProxy;
    }

    public int liveDailyCap() {
        return liveDailyCap;
    }

    public Duration liveCooldown() {
        return liveCooldown;
    }

    public int scriptedDailyCap() {
        return scriptedDailyCap;
    }

    public Duration scriptedCooldown() {
        return scriptedCooldown;
    }

    public boolean liveEnabled() {
        return liveEnabled;
    }

    public String nebiusBaseUrl() {
        return nebiusBaseUrl;
    }

    public String nebiusModel() {
        return nebiusModel;
    }

    public Supplier<String> agentKey() {
        return source("NKAP_AGENT_KEY");
    }

    public Supplier<String> approverKey() {
        return source("NKAP_APPROVER_KEY");
    }

    public Supplier<String> salt() {
        return source("NKAP_AGENT_SALT");
    }

    public Supplier<String> nebiusKey() {
        return source("NEBIUS_API_KEY");
    }

    @Override
    public String toString() {
        return "WebConfig[bind=" + bind + ", port=" + port + ", live=" + (liveEnabled ? "on" : "off") + "]";
    }
}

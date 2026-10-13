package dev.nkap.agent.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WebConfigTest {

    private static Map<String, String> minimal() {
        Map<String, String> env = new HashMap<>();
        env.put("NKAP_AGENT_MANIFEST", "/data/seed-manifest.json");
        env.put("NKAP_AGENT_KEY", "TEST-ONLY-AGENT-KEY");
        env.put("NKAP_APPROVER_KEY", "TEST-ONLY-APPROVER-KEY");
        env.put("NKAP_AGENT_SALT", "TEST-ONLY-SALT");
        return env;
    }

    @Test
    @DisplayName("the defaults: loopback, port 8088, the documented caps and cooldowns, live off")
    void defaults() {
        WebConfig config = WebConfig.from(minimal());
        assertThat(config.bind()).isEqualTo("127.0.0.1");
        assertThat(config.port()).isEqualTo(8088);
        assertThat(config.gatewayUrl()).isEqualTo("http://127.0.0.1:8080");
        assertThat(config.liveDailyCap()).isEqualTo(40);
        assertThat(config.liveCooldown()).isEqualTo(Duration.ofMinutes(5));
        assertThat(config.scriptedDailyCap()).isEqualTo(200);
        assertThat(config.scriptedCooldown()).isEqualTo(Duration.ofSeconds(20));
        assertThat(config.liveEnabled()).isFalse();
        assertThat(config.trustedProxy()).isNull();
        assertThat(config.statementsDir()).isEqualTo(Path.of("/data/statements"));
        assertThat(config.tracesDir()).isEqualTo(Path.of("demo/traces"));
    }

    @Test
    @DisplayName("a bad or missing value fails at start, and names the variable and never the value")
    void failsAtStart() {
        Map<String, String> badValues = new HashMap<>();
        badValues.put("NKAP_AGENT_PORT", "eighty");
        badValues.put("NKAP_AGENT_LIVE_DAILY_CAP", "0");
        badValues.put("NKAP_AGENT_LIVE_COOLDOWN_SECONDS", "-1");
        badValues.put("NKAP_AGENT_SCRIPTED_DAILY_CAP", "x");
        badValues.put("NKAP_AGENT_SCRIPTED_COOLDOWN_SECONDS", "1.5");
        badValues.put("NKAP_AGENT_PORT ", "1");
        badValues.put("NKAP_GATEWAY_URL", "http://example.invalid");
        for (Map.Entry<String, String> bad : badValues.entrySet()) {
            if (bad.getKey().endsWith(" ")) {
                continue;
            }
            Map<String, String> env = minimal();
            env.put(bad.getKey(), bad.getValue());
            assertThatThrownBy(() -> WebConfig.from(env)).as(bad.getKey()).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(bad.getKey()).hasMessageNotContaining(bad.getValue() + "x");
        }
        for (String missing : new String[] {"NKAP_AGENT_MANIFEST", "NKAP_AGENT_KEY", "NKAP_APPROVER_KEY", "NKAP_AGENT_SALT"}) {
            Map<String, String> env = minimal();
            env.remove(missing);
            assertThatThrownBy(() -> WebConfig.from(env)).as(missing).isInstanceOf(IllegalArgumentException.class).hasMessageContaining(missing);
        }
        Map<String, String> port = minimal();
        port.put("NKAP_AGENT_PORT", "70000");
        assertThatThrownBy(() -> WebConfig.from(port)).hasMessageContaining("NKAP_AGENT_PORT");
    }

    @Test
    @DisplayName("a key comes from a variable or a file, not both, and a file is read each time it is used")
    void keysAreReadPerUse(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("agent.key");
        Files.writeString(file, "FIRST-TEST-KEY\n");
        Map<String, String> env = minimal();
        env.remove("NKAP_AGENT_KEY");
        env.put("NKAP_AGENT_KEY_FILE", file.toString());
        WebConfig config = WebConfig.from(env);
        assertThat(config.agentKey().get()).isEqualTo("FIRST-TEST-KEY");
        Files.writeString(file, "SECOND-TEST-KEY\n");
        assertThat(config.agentKey().get()).isEqualTo("SECOND-TEST-KEY");

        Map<String, String> both = minimal();
        both.put("NKAP_AGENT_KEY_FILE", file.toString());
        assertThatThrownBy(() -> WebConfig.from(both)).hasMessageContaining("NKAP_AGENT_KEY");

        Map<String, String> unreadable = minimal();
        unreadable.remove("NKAP_APPROVER_KEY");
        unreadable.put("NKAP_APPROVER_KEY_FILE", dir.resolve("none").toString());
        assertThatThrownBy(() -> WebConfig.from(unreadable)).hasMessageContaining("NKAP_APPROVER_KEY_FILE");
    }

    @Test
    @DisplayName("live is on only with a model key configured and a base URL that is https or loopback; otherwise off, not an error")
    void liveConditions() {
        Map<String, String> env = minimal();
        env.put("NEBIUS_API_KEY", "TEST-ONLY-MODEL-KEY");
        assertThat(WebConfig.from(env).liveEnabled()).as("default URL is https").isTrue();
        env.put("NEBIUS_BASE_URL", "http://127.0.0.1:9999/v1/");
        assertThat(WebConfig.from(env).liveEnabled()).isTrue();
        env.put("NEBIUS_BASE_URL", "http://example.invalid/v1/");
        assertThat(WebConfig.from(env).liveEnabled()).isFalse();
        env.put("NEBIUS_BASE_URL", "https://api.example/v1/");
        env.remove("NEBIUS_API_KEY");
        assertThat(WebConfig.from(env).liveEnabled()).isFalse();
    }

    @Test
    @DisplayName("the configuration never shows a key: not in toString, and a key's source only by name")
    void toStringHasNoKey() {
        Map<String, String> env = minimal();
        env.put("NEBIUS_API_KEY", "TEST-ONLY-MODEL-KEY");
        String text = WebConfig.from(env).toString();
        assertThat(text).doesNotContain("TEST-ONLY");
    }

    @Test
    @DisplayName("the gateway may be reached over plain http at one host the operator names, for a gateway on a private network; any other host still needs https or loopback")
    void internalHost() {
        Map<String, String> env = minimal();
        env.put("NKAP_GATEWAY_URL", "http://gateway:8080");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> WebConfig.from(env)).hasMessageContaining("NKAP_GATEWAY_URL");
        env.put("NKAP_AGENT_INTERNAL_HOST", "gateway");
        WebConfig config = WebConfig.from(env);
        assertThat(config.gatewayUrl()).isEqualTo("http://gateway:8080");
        assertThat(config.internalHost()).isEqualTo("gateway");
        env.put("NKAP_GATEWAY_URL", "http://other:8080");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> WebConfig.from(env)).hasMessageContaining("NKAP_GATEWAY_URL");
        env.put("NKAP_GATEWAY_URL", "http://gateway:8080");
        env.put("NKAP_AGENT_INTERNAL_HOST", "Not A Host");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> WebConfig.from(env)).hasMessageContaining("NKAP_AGENT_INTERNAL_HOST");
        assertThat(WebConfig.from(minimal()).internalHost()).isNull();
    }
}

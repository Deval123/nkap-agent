package dev.nkap.agent.loop;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.nkap.agent.scenario.Catalogue;
import dev.nkap.agent.scenario.ScenarioCase;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RunCliTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String KEY = "TEST-ONLY-KEY-VALUE-77ab";

    @TempDir
    Path dir;

    private int code;
    private String out;
    private String err;

    private void run(Map<String, String> env, String... args) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        ByteArrayOutputStream e = new ByteArrayOutputStream();
        code = RunCli.run(args, env, new PrintStream(o, true, StandardCharsets.UTF_8), new PrintStream(e, true, StandardCharsets.UTF_8));
        out = o.toString(StandardCharsets.UTF_8);
        err = e.toString(StandardCharsets.UTF_8);
    }

    private Map<String, String> env() {
        Map<String, String> env = new HashMap<>();
        env.put("NEBIUS_API_KEY", KEY);
        env.put("NKAP_AGENT_KEY", "TEST-ONLY-AGENT-KEY");
        env.put("NKAP_GATEWAY_URL", "http://127.0.0.1:1");
        env.put("NEBIUS_BASE_URL", "http://127.0.0.1:1/v1/");
        return env;
    }

    @Test
    @DisplayName("fake-data writes, for every case, a manifest entry, the gateway's answers and the scripted final answer")
    void fakeData() throws Exception {
        run(env(), "fake-data", dir.toString());
        assertThat(code).isZero();
        JsonNode manifest = JSON.readTree(dir.resolve("manifest.json").toFile());
        assertThat(manifest.get("cases")).hasSize(Catalogue.all().size());
        for (ScenarioCase c : Catalogue.all()) {
            JsonNode entry = null;
            for (JsonNode n : manifest.get("cases")) {
                if (n.get("id").asText().equals(c.id())) {
                    entry = n;
                }
            }
            assertThat(entry).as(c.id()).isNotNull();
            String ref = entry.get("paymentReference").asText();
            assertThat(Files.exists(dir.resolve("gateway/payments/" + ref))).isTrue();
            if (c.lines().isEmpty()) {
                assertThat(entry.get("statementImportId").isNull()).as(c.id() + " has no import").isTrue();
            } else {
                assertThat(Files.exists(dir.resolve("gateway/statements/imports/" + entry.get("statementImportId").asText()))).isTrue();
            }
            assertThat(JSON.readTree(dir.resolve("script.json").toFile()).has(ref)).isTrue();
        }
        JsonNode injection = JSON.readTree(dir.resolve("gateway/payments/"
                + JSON.readTree(dir.resolve("manifest.json").toFile()).get("cases").get(3).get("paymentReference").asText()).toFile());
        assertThat(injection.get("history").get(0).get("operatorCode").asText()).isEqualTo(Catalogue.INJECTION_SENTENCE);
    }

    @Test
    @DisplayName("the runner refuses, sends nothing and says why: no model key, no agent key, a runs count or call cap out of range, a clear-text remote endpoint")
    void refusals() throws Exception {
        run(env(), "fake-data", dir.toString());
        String manifest = dir.resolve("manifest.json").toString();
        Map<String, String> noKey = env();
        noKey.remove("NEBIUS_API_KEY");
        run(noKey, "run", "--manifest", manifest);
        assertThat(code).isEqualTo(2);
        assertThat(err).contains("NEBIUS_API_KEY is not set");

        Map<String, String> noAgent = env();
        noAgent.put("NKAP_AGENT_KEY", " ");
        run(noAgent, "run", "--manifest", manifest);
        assertThat(code).isEqualTo(2);
        assertThat(err).contains("NKAP_AGENT_KEY is not set");

        for (String runs : new String[] {"0", "6", "x", "-1"}) {
            run(env(), "run", "--manifest", manifest, "--runs", runs);
            assertThat(code).as("runs " + runs).isEqualTo(2);
        }
        for (String cap : new String[] {"0", "121", "x", ""}) {
            run(env(), "run", "--manifest", manifest, "--max-calls", cap);
            assertThat(code).as("cap " + cap).isEqualTo(2);
        }
        Map<String, String> remote = env();
        remote.put("NEBIUS_BASE_URL", "http://example.invalid/v1/");
        run(remote, "run", "--manifest", manifest);
        assertThat(code).isEqualTo(2);
        assertThat(err).contains("https");

        run(env(), "run");
        assertThat(code).isEqualTo(2);
        assertThat(out + err).doesNotContain(KEY);
    }

    @Test
    @DisplayName("the log is off below WARNING by default and on at INFO with --verbose, for the loggers of the tools and of the model")
    void loggingLevel() {
        java.util.logging.Logger root = java.util.logging.Logger.getLogger("");
        java.util.logging.Level before = root.getLevel();
        java.util.List<java.util.logging.Level> handlerLevels = java.util.Arrays.stream(root.getHandlers()).map(java.util.logging.Handler::getLevel).toList();
        // Other tests give these loggers a level of their own; in the runner they have none and inherit the root's.
        java.util.Map<String, java.util.logging.Level> own = new java.util.HashMap<>();
        for (String name : java.util.List.of("dev.nkap.agent.tools", "dev.nkap.agent.model")) {
            own.put(name, java.util.logging.Logger.getLogger(name).getLevel());
            java.util.logging.Logger.getLogger(name).setLevel(null);
        }
        try {
            RunCli.configureLogging(false);
            for (String name : java.util.List.of("dev.nkap.agent.tools", "dev.nkap.agent.model")) {
                assertThat(java.util.logging.Logger.getLogger(name).isLoggable(java.util.logging.Level.INFO)).as(name).isFalse();
                assertThat(java.util.logging.Logger.getLogger(name).isLoggable(java.util.logging.Level.WARNING)).as(name).isTrue();
            }
            RunCli.configureLogging(true);
            assertThat(java.util.logging.Logger.getLogger("dev.nkap.agent.model").isLoggable(java.util.logging.Level.INFO)).isTrue();
            for (java.util.logging.Handler h : root.getHandlers()) {
                assertThat(h.getLevel().intValue()).isLessThanOrEqualTo(java.util.logging.Level.INFO.intValue());
            }
        } finally {
            own.forEach((name, level) -> java.util.logging.Logger.getLogger(name).setLevel(level));
            root.setLevel(before);
            java.util.Iterator<java.util.logging.Level> it = handlerLevels.iterator();
            for (java.util.logging.Handler h : root.getHandlers()) {
                h.setLevel(it.next());
            }
        }
    }

    @Test
    @DisplayName("--verbose is a flag, not an option that takes a value, and it does not make a run start")
    void verboseFlagIsAccepted() throws Exception {
        run(env(), "fake-data", dir.toString());
        Map<String, String> noKey = env();
        noKey.remove("NEBIUS_API_KEY");
        run(noKey, "run", "--manifest", dir.resolve("manifest.json").toString(), "--verbose");
        assertThat(code).isEqualTo(2);
        assertThat(err).contains("NEBIUS_API_KEY is not set");
    }

    @Test
    @DisplayName("the statements directory is --statements-dir, or else statements/ beside the manifest, where the seed puts it")
    void statementsDirectory() {
        assertThat(RunCli.statementsDir(Map.of(), Path.of("/out/seed-manifest.json"))).isEqualTo(Path.of("/out/statements"));
        assertThat(RunCli.statementsDir(Map.of("statements-dir", "/elsewhere"), Path.of("/out/seed-manifest.json")))
                .isEqualTo(Path.of("/elsewhere"));
    }

    @Test
    @DisplayName("fake-data writes, for each case that has lines, the statement file under its import id, and none for a case without")
    void fakeStatements() throws Exception {
        run(env(), "fake-data", dir.toString());
        JsonNode manifest = JSON.readTree(dir.resolve("manifest.json").toFile());
        for (ScenarioCase c : Catalogue.all()) {
            for (JsonNode n : manifest.get("cases")) {
                if (n.get("id").asText().equals(c.id()) && !c.lines().isEmpty()) {
                    Path file = dir.resolve("statements/" + n.get("statementImportId").asText() + ".csv");
                    assertThat(Files.readString(file)).startsWith("operator_transaction_id,amount_minor,fee_minor,");
                }
            }
        }
        try (var files = Files.list(dir.resolve("statements"))) {
            assertThat(files.count()).isEqualTo(Catalogue.all().stream().filter(c -> !c.lines().isEmpty()).count());
        }
        JsonNode finding = JSON.readTree(Files.readString(dir.resolve("gateway/statements/imports/"
                + manifest.get("cases").get(0).get("statementImportId").asText()))).get("findings").get(0);
        assertThat(finding.get("detail").asText()).as("the gateway's finding does not carry the fee, as the real one does not")
                .doesNotContain("fee");
    }
}

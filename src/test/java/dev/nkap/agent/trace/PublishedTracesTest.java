package dev.nkap.agent.trace;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.nkap.agent.scenario.Catalogue;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The traces under demo/traces are what a visitor is shown as a recording. They are checked as files, as they are. */
class PublishedTracesTest {

    private static final Path DIR = Path.of("demo/traces");
    private static final ObjectMapper JSON = new ObjectMapper();
    /** The only hosts a published trace may name: the problem-type namespace of Nkap's documentation. */
    private static final Set<String> DOCUMENTED_HOSTS = Set.of("nkap.dev");
    private static final Pattern UUID = Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private static List<Path> files() throws IOException {
        try (Stream<Path> list = Files.list(DIR)) {
            return list.filter(p -> p.toString().endsWith(".json")).sorted().toList();
        }
    }

    @Test
    @DisplayName("the published traces are the first run of each of the six cases and the rejected run, by the rule in demo/README.md")
    void theSet() throws IOException {
        List<String> names = files().stream().map(p -> p.getFileName().toString()).toList();
        List<String> expected = new ArrayList<>(Catalogue.all().stream().map(c -> c.id() + "-1.json").toList());
        expected.add("duplicate-posting-2.json");
        assertThat(names).containsExactlyInAnyOrderElementsOf(expected);
        String readme = Files.readString(Path.of("demo/README.md"));
        for (String name : names) {
            assertThat(readme).as(name).contains(name.replace(".json", ""));
        }
        assertThat(readme).contains("17 of 18").contains("run-20261013T174953Z");
    }

    @Test
    @DisplayName("each published trace replays, says it is a recording, and says which run of how many it is and whether it matched")
    void eachReplays() throws IOException {
        int rejected = 0;
        for (Path file : files()) {
            List<TraceReplay.Step> steps = TraceReplay.read(file);
            assertThat(steps).as(file.toString()).allMatch(TraceReplay.Step::recording);
            JsonNode provenance = steps.get(0).provenance();
            assertThat(provenance.get("date").asText()).isEqualTo("2026-10-13");
            assertThat(provenance.get("runs").asInt()).isEqualTo(3);
            assertThat(provenance.get("model").asText()).isNotBlank();
            assertThat(file.getFileName().toString()).isEqualTo(provenance.get("case").asText() + "-" + provenance.get("run").asInt() + ".json");
            TraceReplay.Step last = steps.get(steps.size() - 1);
            assertThat(last.type()).isEqualTo("outcome");
            if (last.data().get("kind").asText().equals("REJECTED")) {
                rejected++;
                assertThat(provenance.get("matched").asBoolean()).isFalse();
                assertThat(file.getFileName().toString()).isEqualTo("duplicate-posting-2.json");
            } else {
                assertThat(provenance.get("matched").asBoolean()).as(file.toString()).isTrue();
            }
        }
        assertThat(rejected).isEqualTo(1);
    }

    @Test
    @DisplayName("no published trace holds a key, a Bearer header, a long token, a variable that names a key, a local address, or a host that is not documented")
    void scan() throws IOException {
        for (Path file : files()) {
            String text = Files.readString(file);
            String name = file.getFileName().toString();
            assertThat(text).as(name).doesNotContain("Bearer").doesNotContain("NEBIUS").doesNotContain("NKAP_AGENT_KEY")
                    .doesNotContain("NKAP_APPROVER_KEY").doesNotContain("Authorization").doesNotContain("authorization")
                    .doesNotContain("127.0.0.1").doesNotContain("localhost").doesNotContain("host.docker").doesNotContain("\r");
            assertThat(text.toLowerCase()).as(name).doesNotContain("api key").doesNotContain("apikey").doesNotContain("api_key")
                    .doesNotContain("secret").doesNotContain("password").doesNotContain("token\":");
            // Two things are long and meant to be there, and are taken out before the scan for tokens: the model's id, and the
            // documented problem-type URLs of Nkap (the host of which is checked below).
            String withoutIds = UUID.matcher(text.replaceAll("\"model\": \"[A-Za-z0-9/._-]+\"", "\"model\": \"MODEL\"")
                    .replaceAll("https://nkap\\.dev/problems/[a-z-]+", "PROBLEM")).replaceAll("UUID");
            assertThat(Pattern.compile("[A-Za-z0-9_+/=-]{32,}").matcher(withoutIds).find()).as(name + " has no long token").isFalse();
            assertThat(Pattern.compile("\\b(sk|pk|ey[JK])[-_A-Za-z0-9]{12,}").matcher(withoutIds).find()).as(name + " has no key shape").isFalse();

            Matcher urls = Pattern.compile("https?://([A-Za-z0-9.-]+)").matcher(text);
            while (urls.find()) {
                assertThat(DOCUMENTED_HOSTS).as(name + " names " + urls.group(1)).contains(urls.group(1));
            }
            Matcher hosts = Pattern.compile("\\b([a-z0-9-]+(?:\\.[a-z0-9-]+)*\\.(?:com|dev|io|org|net|cm|co|app|xyz|me|ai|local|internal))\\b").matcher(text);
            while (hosts.find()) {
                assertThat(DOCUMENTED_HOSTS).as(name + " names " + hosts.group(1)).contains(hosts.group(1));
            }
        }
    }

    @Test
    @DisplayName("the scan itself works: it would catch a key shape, a Bearer header and an undocumented host if one were there")
    void scanCatches() {
        assertThat(Pattern.compile("[A-Za-z0-9_+/=-]{32,}").matcher("x " + "a".repeat(40)).find()).isTrue();
        assertThat(Pattern.compile("\\b(sk|pk|ey[JK])[-_A-Za-z0-9]{12,}").matcher("sk-abcdefghijklmnop").find()).isTrue();
        Matcher hosts = Pattern.compile("https?://([A-Za-z0-9.-]+)").matcher("see https://gateway.internal:8080/x");
        assertThat(hosts.find()).isTrue();
        assertThat(DOCUMENTED_HOSTS).doesNotContain(hosts.group(1));
    }

    @Test
    @DisplayName("every published trace is in the exporter's own format: pretty, LF, a trailing newline, the keys in its order")
    void inTheExportersFormat() throws IOException {
        for (Path file : files()) {
            String text = Files.readString(file);
            assertThat(TraceExport.render(JSON.readTree(text))).as(file.toString()).isEqualTo(text);
        }
    }
}

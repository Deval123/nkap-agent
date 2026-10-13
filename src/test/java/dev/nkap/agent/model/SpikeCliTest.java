package dev.nkap.agent.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.nkap.agent.scenario.Catalogue;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SpikeCliTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path dir;

    private JsonNode read(String name) throws IOException {
        return JSON.readTree(Files.readString(dir.resolve(name)));
    }

    private String run(String input, String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int code = SpikeCli.run(args, new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)),
                new PrintStream(out, true, StandardCharsets.UTF_8));
        assertThat(code).isZero();
        return out.toString(StandardCharsets.UTF_8);
    }

    private static String all(JsonNode messages) {
        StringBuilder sb = new StringBuilder();
        messages.forEach(m -> sb.append(m.get("content").asText()).append('\n'));
        return sb.toString();
    }

    @Test
    @DisplayName("the fixtures hold the request's response_format as the client sends it, and every conversation")
    void fixturesAreWritten() throws IOException {
        run("", "fixtures", dir.toString());
        assertThat(read("response_format.json")).isEqualTo(AnswerSchema.responseFormat());
        for (String name : List.of("m1-prose", "m2-injection", "m2-control", "m3-realistic")) {
            JsonNode messages = read(name + ".messages.json");
            assertThat(messages.isArray()).as(name).isTrue();
            messages.forEach(m -> assertThat(List.of("system", "user", "assistant")).contains(m.get("role").asText()));
            assertThat(messages.get(0).get("role").asText()).isEqualTo("system");
            assertThat(messages.get(0).get("content").asText()).as(name).contains("not the product prompt");
        }
    }

    @Test
    @DisplayName("the injection fixture carries the catalogue's sentence in a tool result, and the control does not")
    void injectionFixture() throws IOException {
        run("", "fixtures", dir.toString());
        String injected = all(read("m2-injection.messages.json"));
        assertThat(injected).contains(Catalogue.INJECTION_SENTENCE);
        assertThat(injected).contains("untrusted");
        assertThat(all(read("m2-control.messages.json"))).doesNotContain("Ignore your previous instructions");
    }

    @Test
    @DisplayName("the realistic fixture is built from the unrecorded-fee case, through the same masking the tools apply")
    void realisticFixture() throws IOException {
        run("", "fixtures", dir.toString());
        String text = all(read("m3-realistic.messages.json"));
        assertThat(text).contains("5000").contains("75").contains("FT-UF-0001").contains("call=\"c1\"")
                .contains("call=\"c2\"");
        assertThat(text).as("the telephone number is masked as the tools mask it").doesNotContain("447700900001")
                .contains("4477");
        assertThat(text).contains("synthetic");
    }

    @Test
    @DisplayName("check reports, for each answer on stdin, whether it conforms, and says why not")
    void check() {
        String conforming = "{\"action\":\"call_tool\",\"tool\":\"search_docs\",\"argument\":\"fee\","
                + "\"outcome\":null,\"hypothesis\":null,\"postings\":null,\"evidence\":null,\"explanation\":null}";
        String input = JSON.valueToTree(conforming) + "\n" + JSON.valueToTree("Two sentences of prose.") + "\n"
                + JSON.valueToTree(conforming.replace("search_docs", "get_balance")) + "\n";
        List<String> lines = run(input, "check").lines().toList();
        assertThat(lines).hasSize(3);
        assertThat(lines.get(0)).isEqualTo("ok call_tool");
        assertThat(lines.get(1)).startsWith("violation ").contains("not JSON");
        assertThat(lines.get(2)).startsWith("violation ").contains("tool");
    }

    private static final String GOOD_FINAL = "{\"action\":\"final\",\"tool\":null,\"argument\":null,\"outcome\":\"propose\","
            + "\"hypothesis\":\"unrecorded_fee\",\"postings\":["
            + "{\"account\":\"fees:mtn-cm:XAF\",\"amountMinor\":75,\"currency\":\"XAF\"},"
            + "{\"account\":\"provider:mtn-cm:float:XAF\",\"amountMinor\":-75,\"currency\":\"XAF\"}],"
            + "\"evidence\":[{\"callId\":\"c2\",\"path\":\"/findings/0/kind\"},"
            + "{\"callId\":\"c1\",\"path\":\"/state\"}],\"explanation\":\"A fee of 75.\"}";

    @Test
    @DisplayName("check --expect judges a final answer against the case: hypothesis, postings, and every evidence pointer against the trace")
    void checkAgainstTheCase() {
        String right = run(JSON.valueToTree(GOOD_FINAL) + "\n", "check", "--expect", "unrecorded-fee");
        assertThat(right).isEqualTo("ok final/propose hypothesis=right postings=right evidence=2/2\n");

        String wrongHypothesis = GOOD_FINAL.replace("unrecorded_fee", "float_drift");
        String wrongAmount = GOOD_FINAL.replace("\"amountMinor\":75", "\"amountMinor\":57");
        String invented = GOOD_FINAL.replace("\"/state\"", "\"/status\"");
        String input = JSON.valueToTree(wrongHypothesis) + "\n" + JSON.valueToTree(wrongAmount) + "\n"
                + JSON.valueToTree(invented) + "\n";
        List<String> lines = run(input, "check", "--expect", "unrecorded-fee").lines().toList();
        assertThat(lines.get(0)).contains("hypothesis=wrong").contains("postings=right");
        assertThat(lines.get(1)).contains("hypothesis=right").contains("postings=wrong");
        assertThat(lines.get(2)).contains("evidence=1/2");
    }

    @Test
    @DisplayName("check --expect no_proposal counts a proposal as the wrong outcome")
    void checkNoProposal() {
        String noProposal = "{\"action\":\"final\",\"tool\":null,\"argument\":null,\"outcome\":\"no_proposal\","
                + "\"hypothesis\":null,\"postings\":[],\"evidence\":[],\"explanation\":\"Nothing.\"}";
        assertThat(run(JSON.valueToTree(noProposal) + "\n", "check", "--expect", "no_proposal"))
                .isEqualTo("ok final/no_proposal outcome=right\n");
        assertThat(run(JSON.valueToTree(GOOD_FINAL) + "\n", "check", "--expect", "no_proposal"))
                .startsWith("ok final/propose outcome=wrong");
    }
}

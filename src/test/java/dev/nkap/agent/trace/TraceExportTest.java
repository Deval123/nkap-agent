package dev.nkap.agent.trace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import dev.nkap.agent.loop.RunRecord;
import dev.nkap.agent.scenario.Catalogue;
import dev.nkap.agent.scenario.ScenarioCase;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TraceExportTest {

    private static final ScenarioCase FEE = Catalogue.all().get(0);

    private static TraceExport.Provenance provenance() {
        return new TraceExport.Provenance("2026-10-13", "test/model", 1, 3, true, "hypothesis right, entry right", 0.30, 0.90);
    }

    private static List<String> types(JsonNode trace) {
        List<String> out = new ArrayList<>();
        trace.get("steps").forEach(s -> out.add(s.get("type").asText()));
        return out;
    }

    @Test
    @DisplayName("a run becomes its steps in the order they happened: the task, each model answer, each tool result, the repair, the outcome last")
    void steps() {
        JsonNode trace = TraceExport.export(TraceFixtures.json(TraceFixtures.record()), FEE, provenance());
        assertThat(types(trace)).containsExactly("task", "model", "tool", "model", "tool", "model", "harness", "model", "outcome");
        JsonNode steps = trace.get("steps");
        assertThat(steps.get(0).get("text").asText()).startsWith("Investigate payment");
        assertThat(steps.get(1).get("answer").get("tool").asText()).isEqualTo("get_payment");
        assertThat(steps.get(1).get("millis").asLong()).isEqualTo(3000);
        assertThat(steps.get(1).get("promptTokens").asLong()).isEqualTo(1000);
        assertThat(steps.get(2).get("callId").asText()).isEqualTo("c1");
        assertThat(steps.get(2).get("tool").asText()).isEqualTo("get_payment");
        assertThat(steps.get(2).get("status").asInt()).isEqualTo(200);
        assertThat(steps.get(4).get("fields").get("lines").get(0).get("feeMinor").asInt()).isEqualTo(75);
        assertThat(steps.get(6).get("text").asText()).isEqualTo(TraceFixtures.REPAIR);
        assertThat(steps.get(7).get("answer").get("action").asText()).isEqualTo("final");
        assertThat(steps.get(8).get("kind").asText()).isEqualTo("PROPOSED");
    }

    @Test
    @DisplayName("the provenance is the date, the model, run n of K, tokens, cost, calls, and the catalogue's expectation with whether the run matched")
    void provenanceFields() {
        JsonNode trace = TraceExport.export(TraceFixtures.json(TraceFixtures.record()), FEE, provenance());
        JsonNode p = trace.get("provenance");
        assertThat(p.get("date").asText()).isEqualTo("2026-10-13");
        assertThat(p.get("model").asText()).isEqualTo("test/model");
        assertThat(p.get("case").asText()).isEqualTo("unrecorded-fee");
        assertThat(p.get("run").asInt()).isEqualTo(1);
        assertThat(p.get("runs").asInt()).isEqualTo(3);
        assertThat(p.get("modelCalls").asInt()).isEqualTo(4);
        assertThat(p.get("promptTokens").asLong()).isEqualTo(7100);
        assertThat(p.get("completionTokens").asLong()).isEqualTo(1800);
        assertThat(p.get("costUsd").asDouble()).isEqualTo((7100 * 0.30 + 1800 * 0.90) / 1e6, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(p.get("prices").get("inputPerMillionUsd").asDouble()).isEqualTo(0.30);
        assertThat(p.get("elapsedMillis").asLong()).isEqualTo(18500);
        assertThat(p.get("matched").asBoolean()).isTrue();
        assertThat(p.get("judgement").asText()).isEqualTo("hypothesis right, entry right");
        JsonNode expected = p.get("expected");
        assertThat(expected.get("outcome").asText()).isEqualTo("PROPOSE");
        assertThat(expected.get("hypothesis").asText()).isEqualTo("unrecorded_fee");
        assertThat(expected.get("postings")).hasSize(2);
        assertThat(trace.get("recording").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("nothing else is exported: the top level and the provenance hold exactly these keys, and the record's other fields are dropped")
    void nothingElse() {
        JsonNode trace = TraceExport.export(TraceFixtures.json(TraceFixtures.record()), FEE, provenance());
        List<String> top = new ArrayList<>();
        trace.fieldNames().forEachRemaining(top::add);
        assertThat(top).containsExactly("format", "recording", "provenance", "steps");
        assertThat(trace.get("format").asText()).isEqualTo("nkap-agent-trace/1");
        List<String> prov = new ArrayList<>();
        trace.get("provenance").fieldNames().forEachRemaining(prov::add);
        assertThat(prov).containsExactly("date", "model", "case", "title", "run", "runs", "modelCalls", "promptTokens", "completionTokens",
                "costUsd", "prices", "elapsedMillis", "expected", "matched", "judgement");
        assertThat(trace.toString()).doesNotContain("SYSTEM PROMPT").doesNotContain("messagesSent").doesNotContain("toolCalls");
    }

    @Test
    @DisplayName("the file is pretty JSON with LF line endings, a trailing newline, a stable key order, and the same twice")
    void stableText() {
        String first = TraceExport.render(TraceExport.export(TraceFixtures.json(TraceFixtures.record()), FEE, provenance()));
        String second = TraceExport.render(TraceExport.export(TraceFixtures.json(TraceFixtures.record()), FEE, provenance()));
        assertThat(first).isEqualTo(second).doesNotContain("\r").endsWith("}\n").contains("\n  \"provenance\": {\n");
        assertThat(first.indexOf("\"format\"")).isLessThan(first.indexOf("\"provenance\""));
    }

    @Test
    @DisplayName("a run that does not exist is refused, and so is a case that does not; nothing is written")
    void refusesMissingRuns(@TempDir Path dir) throws IOException {
        Path results = dir.resolve("run.jsonl");
        Files.writeString(results, resultsLine("unrecorded-fee", 1) + "\n");
        assertThatThrownBy(() -> TraceExport.fromResults(results, "unrecorded-fee", 2, dir.resolve("out"), "m", "2026-10-13"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("no run 2");
        assertThatThrownBy(() -> TraceExport.fromResults(results, "nope", 1, dir.resolve("out"), "m", "2026-10-13"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(Files.exists(dir.resolve("out"))).isFalse();
    }

    @Test
    @DisplayName("from a results file, the run is written as <case>-<n>.json with K the number of runs of that case in the file")
    void fromResults(@TempDir Path dir) throws IOException {
        Path results = dir.resolve("run.jsonl");
        Files.writeString(results, resultsLine("unrecorded-fee", 1) + "\n" + resultsLine("unrecorded-fee", 2) + "\n" + resultsLine("unrecorded-fee", 3) + "\n");
        Path written = TraceExport.fromResults(results, "unrecorded-fee", 2, dir.resolve("out"), "test/model", "2026-10-13");
        assertThat(written).isEqualTo(dir.resolve("out/unrecorded-fee-2.json"));
        JsonNode trace = TraceFixtures.node(Files.readString(written));
        assertThat(trace.get("provenance").get("run").asInt()).isEqualTo(2);
        assertThat(trace.get("provenance").get("runs").asInt()).isEqualTo(3);
        assertThat(trace.get("provenance").get("matched").asBoolean()).isTrue();
    }

    private static String resultsLine(String caseId, int run) {
        RunRecord record = TraceFixtures.record();
        return "{\"case\":\"" + caseId + "\",\"run\":" + run + ",\"matches\":true,\"judgement\":\"hypothesis right, entry right\",\"record\":"
                + record.toJson() + "}";
    }
}

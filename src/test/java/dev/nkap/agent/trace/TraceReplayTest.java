package dev.nkap.agent.trace;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import dev.nkap.agent.loop.RunRecord;
import dev.nkap.agent.scenario.Catalogue;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TraceReplayTest {

    private static JsonNode exported() {
        return TraceExport.export(TraceFixtures.json(TraceFixtures.record()), Catalogue.all().get(0),
                new TraceExport.Provenance("2026-10-13", "test/model", 1, 3, true, "ok", 0.30, 0.90));
    }

    @Test
    @DisplayName("a trace round-trips: exported, then replayed, it gives the same steps and the same outcome as the record")
    void roundTrip() {
        RunRecord record = TraceFixtures.record();
        JsonNode recorded = TraceFixtures.json(record);
        List<TraceReplay.Step> steps = TraceReplay.steps(TraceFixtures.node(TraceExport.render(exported())));

        List<JsonNode> answers = steps.stream().filter(s -> s.type().equals("model")).map(s -> s.data().get("answer")).toList();
        assertThat(answers).hasSize(record.calls().size());
        for (int i = 0; i < answers.size(); i++) {
            assertThat(answers.get(i)).isEqualTo(TraceFixtures.node(record.calls().get(i).answer()));
        }
        List<JsonNode> tools = steps.stream().filter(s -> s.type().equals("tool")).map(TraceReplay.Step::data).toList();
        assertThat(tools).hasSize(record.toolCalls().size());
        for (int i = 0; i < tools.size(); i++) {
            assertThat(tools.get(i).get("callId").asText()).isEqualTo(record.toolCalls().get(i).callId());
            assertThat(tools.get(i).get("fields")).isEqualTo(record.toolCalls().get(i).fields());
        }
        TraceReplay.Step last = steps.get(steps.size() - 1);
        assertThat(last.type()).isEqualTo("outcome");
        com.fasterxml.jackson.databind.node.ObjectNode outcome = last.data().deepCopy();
        assertThat(outcome.remove("type").asText()).isEqualTo("outcome");
        assertThat(outcome).isEqualTo(recorded.get("outcome"));
        assertThat(steps.stream().filter(s -> s.type().equals("outcome"))).hasSize(1);
    }

    @Test
    @DisplayName("the steps come in order with their index, and the timings are the record's, capped at 2 seconds")
    void timings() {
        List<TraceReplay.Step> steps = TraceReplay.steps(exported());
        for (int i = 0; i < steps.size(); i++) {
            assertThat(steps.get(i).index()).isEqualTo(i);
        }
        List<Long> delays = steps.stream().filter(s -> s.type().equals("model")).map(TraceReplay.Step::delayMillis).toList();
        assertThat(delays).containsExactly(2000L, 2000L, 2000L, 1500L);
        assertThat(TraceReplay.MAX_DELAY_MILLIS).isEqualTo(2000);
        assertThat(steps.stream().filter(s -> !s.type().equals("model")).map(TraceReplay.Step::delayMillis)).containsOnly(0L);
    }

    @Test
    @DisplayName("every replayed step is marked as a recording, and the provenance is part of the first item")
    void recordingAndProvenance() {
        List<TraceReplay.Step> steps = TraceReplay.steps(exported());
        assertThat(steps).allMatch(TraceReplay.Step::recording);
        assertThat(steps.get(0).provenance()).isNotNull();
        assertThat(steps.get(0).provenance().get("model").asText()).isEqualTo("test/model");
        assertThat(steps.get(0).provenance().get("case").asText()).isEqualTo("unrecorded-fee");
        assertThat(steps.subList(1, steps.size())).allMatch(s -> s.provenance() == null);
    }

    @Test
    @DisplayName("a file that is not a trace, or that is not marked as a recording, is not replayed")
    void refusesWhatIsNotATrace() {
        for (String bad : List.of("{}", "[]", "{\"format\":\"other\",\"recording\":true,\"provenance\":{},\"steps\":[]}",
                "{\"format\":\"nkap-agent-trace/1\",\"recording\":false,\"provenance\":{},\"steps\":[{\"type\":\"outcome\"}]}",
                "{\"format\":\"nkap-agent-trace/1\",\"recording\":true,\"provenance\":{},\"steps\":[{\"type\":\"model\"}]}",
                "{\"format\":\"nkap-agent-trace/1\",\"recording\":true,\"provenance\":{},\"steps\":[{\"type\":\"outcome\"},{\"type\":\"task\"}]}")) {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> TraceReplay.steps(TraceFixtures.node(bad)))
                    .as(bad).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    @DisplayName("replay never calls a model or a gateway: its classes refer to neither, nor to the network")
    void touchesNothing() throws IOException {
        for (Class<?> type : List.of(TraceReplay.class, TraceReplay.Step.class)) {
            try (InputStream in = type.getResourceAsStream("/" + type.getName().replace('.', '/') + ".class")) {
                String bytes = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.ISO_8859_1);
                assertThat(bytes).as(type.getSimpleName()).doesNotContain("GatewayClient").doesNotContain("NebiusModel").doesNotContain("AgentLoop")
                        .doesNotContain("java/net").doesNotContain("HttpClient").doesNotContain("approval");
            }
        }
    }
}

package dev.nkap.agent.loop;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import dev.nkap.agent.model.NebiusModel;
import dev.nkap.agent.scenario.Hypothesis;
import dev.nkap.agent.tools.AgentTools;
import dev.nkap.agent.tools.DocsIndex;
import dev.nkap.agent.tools.GatewayClient;
import dev.nkap.agent.tools.Trace;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The loop with the real Nebius client and the real tools, against a fake chat endpoint and a fake gateway. */
class LoopWithTheRealClientTest {

    private static final String REF = "0985e8df-93aa-4949-b7aa-185e19ea41d0";
    private static final String IMPORT = "16de24bf-b9fe-425d-a28e-b0d4d57912b4";
    private static final ObjectMapper JSON = new ObjectMapper();

    private static String completion(String content) throws IOException {
        return JSON.writeValueAsString(java.util.Map.of("choices", java.util.List.of(java.util.Map.of(
                "finish_reason", "stop", "message", java.util.Map.of("role", "assistant", "content", content))),
                "usage", java.util.Map.of("prompt_tokens", 100, "completion_tokens", 50)));
    }

    @Test
    @DisplayName("answers in the shapes the provider really produced, with leftovers in the unused fields, are accepted all the way to a proposal")
    void measuredShapesEndToEnd() throws Exception {
        Deque<String> answers = new ArrayDeque<>();
        // a tool call with final-only fields filled in, as the measured run's wrong couplings were
        answers.add("{\"action\":\"call_tool\",\"tool\":\"get_payment\",\"argument\":\"" + REF + "\",\"outcome\":\"propose\","
                + "\"hypothesis\":null,\"postings\":null,\"evidence\":null,\"explanation\":\"leftover\"}");
        answers.add("{\"action\":\"call_tool\",\"tool\":\"get_statement_findings\",\"argument\":\"" + IMPORT + "\",\"outcome\":null,"
                + "\"hypothesis\":null,\"postings\":null,\"evidence\":null,\"explanation\":null}");
        // a final answer with argument "" and a tool, which the provider wrote in 20 of 20
        answers.add("{\"action\":\"final\",\"tool\":\"search_docs\",\"argument\":\"\",\"outcome\":\"propose\","
                + "\"hypothesis\":\"unrecorded_fee\",\"postings\":[{\"account\":\"fees:mtn-cm:XAF\",\"amountMinor\":75,"
                + "\"currency\":\"XAF\"},{\"account\":\"provider:mtn-cm:float:XAF\",\"amountMinor\":-75,\"currency\":\"XAF\"}],"
                + "\"evidence\":[{\"callId\":\"c2\",\"path\":\"/findings/0/detail\"}],\"explanation\":\"A fee of 75.\"}");

        HttpServer chat = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        chat.createContext("/", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] out = completion(answers.poll()).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        chat.start();
        try (FakeGateway gateway = new FakeGateway()
                .serve("/payments/" + REF, "{\"reference\":\"" + REF + "\",\"provider\":\"mtn-cm\",\"currency\":\"XAF\","
                        + "\"amountMinorUnits\":5000,\"state\":\"UNKNOWN\"}")
                .serve("/statements/imports/" + IMPORT, "{\"findings\":[{\"detail\":\"the line carries a fee of 75 XAF\"}]}")) {
            NebiusModel model = new NebiusModel("http://127.0.0.1:" + chat.getAddress().getPort() + "/v1/", () -> "TEST-ONLY-KEY-1a2b",
                    "test/model", 6000, Duration.ofSeconds(5));
            java.util.function.Function<Trace, AgentTools> tools = trace -> new AgentTools(
                    new GatewayClient(gateway.url(), () -> "TEST-ONLY-AGENT-KEY"), DocsIndex.bundled(), trace);
            RunRecord record = new AgentLoop(model, AgentLoop.Limits.defaults()).run(tools, REF, IMPORT);

            assertThat(record.outcome()).isInstanceOf(RunOutcome.Proposed.class);
            assertThat(((RunOutcome.Proposed) record.outcome()).proposal().hypothesis()).isEqualTo(Hypothesis.UNRECORDED_FEE);
            assertThat(record.toJson()).doesNotContain("leftover").doesNotContain("TEST-ONLY-KEY").doesNotContain("TEST-ONLY-AGENT-KEY");
        } finally {
            chat.stop(0);
        }
    }
}

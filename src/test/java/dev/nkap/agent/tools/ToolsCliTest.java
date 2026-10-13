package dev.nkap.agent.tools;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ToolsCliTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final AgentTools tools = new AgentTools(new GatewayClient("http://127.0.0.1:1", () -> "unused"),
            DocsIndex.bundled(), new Trace());

    private record Run(int code, List<JsonNode> lines) {
    }

    private Run run(String... args) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int code = ToolsCli.run(args, tools, new PrintStream(bytes, true, StandardCharsets.UTF_8));
        List<JsonNode> lines = new java.util.ArrayList<>();
        for (String line : bytes.toString(StandardCharsets.UTF_8).lines().toList()) {
            lines.add(JSON.readTree(line));
        }
        return new Run(code, lines);
    }

    @Test
    @DisplayName("a tool call prints one line of JSON: the result as a model would see it")
    void printsResult() throws Exception {
        Run r = run("search_docs", "statement fee suspense");
        assertThat(r.code()).isZero();
        assertThat(r.lines()).hasSize(1);
        assertThat(r.lines().get(0).get("callId").asText()).isEqualTo("c1");
        assertThat(r.lines().get(0).get("tool").asText()).isEqualTo("search_docs");
    }

    @Test
    @DisplayName("--cite checks a claim against the call and prints whether it was accepted")
    void cites() throws Exception {
        Run r = run("search_docs", "statement fee suspense", "--cite", "/results/0/file=mtn.md",
                "--cite", "/results/0/file=nonsense.md");
        assertThat(r.lines()).hasSize(3);
        assertThat(r.lines().get(1).get("ok").asBoolean()).isTrue();
        assertThat(r.lines().get(2).get("ok").asBoolean()).isFalse();
        assertThat(r.lines().get(2).get("reason").asText()).contains("value differs");
    }

    @Test
    @DisplayName("a tool that does not exist, or a missing argument, is a usage error with code 2 and prints nothing to stdout")
    void usage() throws Exception {
        assertThat(ToolsCli.run(new String[] {"get_balance", "x"}, tools, new PrintStream(new ByteArrayOutputStream()))).isEqualTo(2);
        assertThat(ToolsCli.run(new String[] {"get_payment"}, tools, new PrintStream(new ByteArrayOutputStream()))).isEqualTo(2);
        assertThat(ToolsCli.run(new String[] {}, tools, new PrintStream(new ByteArrayOutputStream()))).isEqualTo(2);
        assertThat(ToolsCli.run(new String[] {"search_docs", "x", "--cite"}, tools, new PrintStream(new ByteArrayOutputStream()))).isEqualTo(2);
    }

    @Test
    @DisplayName("the tool list holds exactly four tools, and get_balance is not one of them")
    void threeTools() {
        assertThat(ToolsCli.TOOLS).containsExactlyInAnyOrder("get_payment", "get_statement_findings", "get_statement_lines", "search_docs");
        assertThat(java.util.Arrays.stream(AgentTools.class.getDeclaredMethods())
                .filter(m -> java.lang.reflect.Modifier.isPublic(m.getModifiers()))
                .map(java.lang.reflect.Method::getName).sorted().toList())
                .containsExactly("getPayment", "getStatementFindings", "getStatementLines", "searchDocs");
    }
}

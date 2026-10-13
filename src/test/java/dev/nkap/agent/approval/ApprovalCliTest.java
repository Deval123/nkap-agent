package dev.nkap.agent.approval;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.nkap.agent.model.AnswerSchema;
import dev.nkap.agent.model.ModelAnswer;
import dev.nkap.agent.scenario.Catalogue;
import dev.nkap.agent.scenario.Hypothesis;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ApprovalCliTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String AGENT_KEY = "TEST-ONLY-AGENT-KEY-31aa";
    private static final String APPROVER_KEY = "TEST-ONLY-APPROVER-KEY-72bb";
    private static final String REF = "10000000-0000-4000-8000-000000000001";
    private static final String IMPORT = "20000000-0000-4000-8000-000000000001";
    private static final String ADJ = "5d3c1f0e-6a51-4c2b-9d7e-1f2a3b4c5d6e";
    private static final String CSV = "operator_transaction_id,amount_minor,fee_minor,currency,occurred_at,status\n"
            + "FT-UF-0001,5000,75,XAF,2026-10-13T10:00:00Z,SETTLED\n";

    @TempDir
    Path dir;

    private FakeNkap nkap;
    private int code;
    private String out;
    private String err;

    @BeforeEach
    void start() throws Exception {
        nkap = new FakeNkap();
        Files.writeString(dir.resolve("manifest.json"), "{\"cases\":[{\"id\":\"unrecorded-fee\",\"paymentReference\":\"" + REF
                + "\",\"statementImportId\":\"" + IMPORT + "\"},{\"id\":\"injection\",\"paymentReference\":\"" + REF
                + "\",\"statementImportId\":null}]}");
        Files.createDirectories(dir.resolve("statements"));
        Files.writeString(dir.resolve("statements/" + IMPORT + ".csv"), CSV);
        nkap.handler = request -> {
            String bearer = request.headers().get("authorization").get(0);
            if (request.path().equals("/payments/" + REF)) {
                return new FakeNkap.Answer(200, "{\"reference\":\"" + REF + "\",\"provider\":\"mtn-cm\",\"currency\":\"XAF\",\"amountMinorUnits\":5000,\"state\":\"UNKNOWN\"}");
            }
            if (request.path().equals("/statements/imports/" + IMPORT)) {
                return new FakeNkap.Answer(200, "{\"findings\":[{\"detail\":\"posted 5000 XAF\"}]}");
            }
            if (request.method().equals("POST") && request.path().equals("/adjustments")) {
                return new FakeNkap.Answer(201, "{\"id\":\"" + ADJ + "\",\"status\":\"pending\",\"hypothesis\":\"unrecorded_fee\"}");
            }
            if (request.method().equals("POST") && request.path().equals("/adjustments/" + ADJ + "/approve")) {
                if (!bearer.equals("Bearer " + AGENT_KEY)) {
                    approved = true;
                }
                return bearer.equals("Bearer " + AGENT_KEY)
                        ? new FakeNkap.Answer(403, "{\"type\":\"https://nkap.dev/problems/adjustment-self-approval\",\"detail\":\"SERVER-DETAIL\"}")
                        : new FakeNkap.Answer(200, "{\"id\":\"" + ADJ + "\",\"status\":\"approved\",\"hypothesis\":\"unrecorded_fee\",\"ledgerEntryId\":\"adjustment:" + ADJ + "\"}");
            }
            if (request.method().equals("GET") && request.path().equals("/adjustments/" + ADJ)) {
                return new FakeNkap.Answer(200, "{\"id\":\"" + ADJ + "\",\"status\":\"" + (approved ? "approved" : "pending")
                        + "\",\"hypothesis\":\"unrecorded_fee\"" + (approved ? ",\"ledgerEntryId\":\"adjustment:" + ADJ + "\"" : "") + "}");
            }
            return new FakeNkap.Answer(404, "{}");
        };
    }

    private boolean approved;

    private Map<String, String> env() {
        Map<String, String> env = new HashMap<>();
        env.put("NKAP_GATEWAY_URL", nkap.url());
        env.put("NKAP_AGENT_KEY", AGENT_KEY);
        env.put("NKAP_APPROVER_KEY", APPROVER_KEY);
        return env;
    }

    private void cli(Map<String, String> env, String... args) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        ByteArrayOutputStream e = new ByteArrayOutputStream();
        code = ApprovalCli.run(args, env, new PrintStream(o, true, StandardCharsets.UTF_8), new PrintStream(e, true, StandardCharsets.UTF_8));
        out = o.toString(StandardCharsets.UTF_8);
        err = e.toString(StandardCharsets.UTF_8);
    }

    private Path answers(String caseId) throws Exception {
        cli(env(), "answers", "--manifest", dir.resolve("manifest.json").toString(), "--case", caseId);
        assertThat(code).isZero();
        Path file = dir.resolve(caseId + ".answers");
        Files.writeString(file, out);
        return file;
    }

    private JsonNode step(String name) throws Exception {
        for (String line : out.lines().toList()) {
            JsonNode node = JSON.readTree(line);
            if (node.path("step").asText().equals(name)) {
                return node;
            }
        }
        throw new AssertionError("no step " + name + " in " + out);
    }

    @Test
    @DisplayName("answers writes the scripted answers of a case: look at the payment, look at the statement lines, then the expected final answer")
    void answersFile() throws Exception {
        Path file = answers("unrecorded-fee");
        List<String> lines = Files.readAllLines(file);
        assertThat(lines).hasSize(3);
        ModelAnswer first = AnswerSchema.parse(lines.get(0));
        assertThat(first).isEqualTo(new ModelAnswer.CallTool("get_payment", REF));
        assertThat(AnswerSchema.parse(lines.get(1))).isEqualTo(new ModelAnswer.CallTool("get_statement_lines", IMPORT));
        ModelAnswer.Final fin = (ModelAnswer.Final) AnswerSchema.parse(lines.get(2));
        assertThat(fin.hypothesis()).isEqualTo(Hypothesis.UNRECORDED_FEE);
        assertThat(fin.evidence()).extracting(e -> e.callId() + e.path()).contains("c2/lines/0/feeMinor");
        assertThat(fin.postings().get(0).amountMinor()).isEqualTo(Catalogue.all().get(0).proposal().orElseThrow().postings().get(0).amountMinor());
    }

    @Test
    @DisplayName("run proposes with the agent's key, approves with the approver's key, and reads the ledger entry the server named")
    void proposeAndApprove() throws Exception {
        Path file = answers("unrecorded-fee");
        nkap.seen.clear();
        cli(env(), "run", "--manifest", dir.resolve("manifest.json").toString(), "--case", "unrecorded-fee", "--answers", file.toString());
        assertThat(code).isZero();
        assertThat(step("loop").get("outcome").asText()).isEqualTo("PROPOSED");
        assertThat(step("propose").get("id").asText()).isEqualTo(ADJ);
        assertThat(step("approve").get("by").asText()).isEqualTo("approver");
        assertThat(step("approve").get("ledgerEntryId").asText()).isEqualTo("adjustment:" + ADJ);
        assertThat(step("read").get("status").asText()).isEqualTo("approved");
        assertThat(step("read").get("ledgerEntryId").asText()).isEqualTo("adjustment:" + ADJ);
        List<FakeNkap.Seen> adjustments = nkap.seen.stream().filter(s -> s.path().startsWith("/adjustments")).toList();
        assertThat(adjustments).extracting(s -> s.method() + " " + s.path()).containsExactly(
                "POST /adjustments", "POST /adjustments/" + ADJ + "/approve", "GET /adjustments/" + ADJ);
        assertThat(adjustments.get(0).headers().get("authorization")).containsExactly("Bearer " + AGENT_KEY);
        assertThat(adjustments.get(1).headers().get("authorization")).containsExactly("Bearer " + APPROVER_KEY);
        assertThat(adjustments.get(2).headers().get("authorization")).containsExactly("Bearer " + AGENT_KEY);
        assertThat(nkap.seen.stream().filter(s -> s.path().startsWith("/payments")).findFirst().orElseThrow()
                .headers().get("authorization")).containsExactly("Bearer " + AGENT_KEY);
        assertThat(out + err).doesNotContain(AGENT_KEY).doesNotContain(APPROVER_KEY);
    }

    @Test
    @DisplayName("--self-approve tries the approval with the agent's own key: Nkap refuses it, the refusal is shown as a fixed text, and the exit is 1")
    void selfApprove() throws Exception {
        Path file = answers("unrecorded-fee");
        Map<String, String> env = env();
        env.remove("NKAP_APPROVER_KEY");
        nkap.seen.clear();
        cli(env, "run", "--manifest", dir.resolve("manifest.json").toString(), "--case", "unrecorded-fee", "--answers", file.toString(), "--self-approve");
        assertThat(code).isEqualTo(1);
        JsonNode approve = step("approve");
        assertThat(approve.get("by").asText()).isEqualTo("agent");
        assertThat(approve.get("code").asText()).isEqualTo("adjustment-self-approval");
        assertThat(approve.get("text").asText()).contains("different key");
        assertThat(out).doesNotContain("SERVER-DETAIL").doesNotContain(AGENT_KEY);
        assertThat(step("read").get("status").asText()).isEqualTo("pending");
    }

    @Test
    @DisplayName("without the approver's key, and not self-approving, nothing is sent and the exit is 2")
    void noApproverKey() throws Exception {
        Path file = answers("unrecorded-fee");
        Map<String, String> env = env();
        env.remove("NKAP_APPROVER_KEY");
        nkap.seen.clear();
        cli(env, "run", "--manifest", dir.resolve("manifest.json").toString(), "--case", "unrecorded-fee", "--answers", file.toString());
        assertThat(code).isEqualTo(2);
        assertThat(err).contains("NKAP_APPROVER_KEY is not set");
        assertThat(nkap.seen).isEmpty();
    }

    @Test
    @DisplayName("a case whose answer is no_proposal sends nothing to the adjustment routes and says so")
    void noProposalCase() throws Exception {
        Path file = answers("injection");
        nkap.seen.clear();
        cli(env(), "run", "--manifest", dir.resolve("manifest.json").toString(), "--case", "injection", "--answers", file.toString());
        assertThat(step("loop").get("outcome").asText()).isEqualTo("NO_PROPOSAL");
        assertThat(nkap.seen.stream().filter(s -> s.path().startsWith("/adjustments"))).isEmpty();
    }

    @Test
    @DisplayName("try-unchecked sends an entry the validator would refuse, to show Nkap's own check, and shows a fixed text")
    void nkapRefusesTooMuch() throws Exception {
        nkap.handler = r -> r.method().equals("POST")
                ? new FakeNkap.Answer(400, "{\"type\":\"https://nkap.dev/problems/adjustment-over-ceiling\",\"detail\":\"SERVER-DETAIL\"}")
                : new FakeNkap.Answer(404, "{}");
        cli(env(), "try-unchecked", "--manifest", dir.resolve("manifest.json").toString(), "--case", "unrecorded-fee", "--amount", "100001");
        assertThat(code).isEqualTo(1);
        JsonNode propose = step("propose");
        assertThat(propose.get("code").asText()).isEqualTo("adjustment-over-ceiling");
        assertThat(out).doesNotContain("SERVER-DETAIL");
        assertThat(JSON.readTree(nkap.seen.get(0).body()).get("postings").get(0).get("amountMinor").asLong()).isEqualTo(100001);
    }

    @Test
    @DisplayName("a missing argument, an unknown case and an unreadable manifest are usage errors, with nothing sent")
    void usage() {
        cli(env(), "run");
        assertThat(code).isEqualTo(2);
        cli(env(), "run", "--manifest", dir.resolve("manifest.json").toString(), "--case", "nope", "--answers", "x");
        assertThat(code).isEqualTo(2);
        cli(env(), "run", "--manifest", dir.resolve("none.json").toString(), "--case", "injection", "--answers", "x");
        assertThat(code).isEqualTo(2);
        assertThat(nkap.seen).isEmpty();
    }
}

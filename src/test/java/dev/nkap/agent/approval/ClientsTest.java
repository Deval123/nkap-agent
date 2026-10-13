package dev.nkap.agent.approval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.nkap.agent.loop.Proposal;
import dev.nkap.agent.model.Posting;
import dev.nkap.agent.scenario.Hypothesis;
import dev.nkap.agent.tools.EvidenceRef;
import java.io.InputStream;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ClientsTest {

    /** Obviously not a key. */
    private static final String AGENT_KEY = "TEST-ONLY-AGENT-KEY-5e6f";
    private static final String APPROVER_KEY = "TEST-ONLY-APPROVER-KEY-9a1c";
    private static final UUID ID = UUID.fromString("5d3c1f0e-6a51-4c2b-9d7e-1f2a3b4c5d6e");
    private static final String REF = "0985e8df-93aa-4949-b7aa-185e19ea41d0";

    private FakeNkap nkap;
    private final List<String> logs = new java.util.concurrent.CopyOnWriteArrayList<>();
    private Handler handler;
    private final Logger logger = Logger.getLogger("dev.nkap.agent.approval");

    @BeforeEach
    void start() throws Exception {
        nkap = new FakeNkap();
        handler = new Handler() {
            @Override
            public void publish(LogRecord r) {
                logs.add(r.getLevel() + " " + new java.util.logging.SimpleFormatter().formatMessage(r)
                        + (r.getThrown() == null ? "" : " " + r.getThrown()));
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        logger.setLevel(Level.ALL);
        logger.addHandler(handler);
    }

    @AfterEach
    void stop() {
        logger.removeHandler(handler);
        nkap.close();
    }

    private AdjustmentProposer proposer() {
        return new AdjustmentProposer(nkap.url(), () -> AGENT_KEY);
    }

    private AdjustmentApprover approver() {
        return new AdjustmentApprover(nkap.url(), () -> APPROVER_KEY);
    }

    private static Proposal proposal() {
        return new Proposal(REF, Hypothesis.UNRECORDED_FEE,
                List.of(new Posting("fees:mtn-cm:XAF", 75, "XAF"), new Posting("provider:mtn-cm:float:XAF", -75, "XAF")),
                List.of(new EvidenceRef("c2", "/lines/0/feeMinor", "75")), "A fee of 75.");
    }

    private static String problem(String type) {
        return "{\"type\":\"https://nkap.dev/problems/" + type + "\",\"title\":\"SERVER-TITLE\",\"detail\":\"SERVER-DETAIL\"}";
    }

    private static String adjustment(String status, String entry) {
        return "{\"id\":\"" + ID + "\",\"status\":\"" + status + "\",\"hypothesis\":\"unrecorded_fee\""
                + (entry == null ? "" : ",\"ledgerEntryId\":\"" + entry + "\"") + "}";
    }

    // --- the proposer

    @Test
    @DisplayName("propose sends one POST /adjustments with the agent's key as its only credential and the mapped body, and returns the id")
    void propose() throws Exception {
        nkap.handler = r -> new FakeNkap.Answer(201, adjustment("pending", null));
        ProposalResult result = proposer().propose(proposal());
        assertThat(result).isEqualTo(new ProposalResult.Created(ID));
        assertThat(nkap.seen).hasSize(1);
        FakeNkap.Seen request = nkap.seen.get(0);
        assertThat(request.method()).isEqualTo("POST");
        assertThat(request.path()).isEqualTo("/adjustments");
        assertThat(request.headers().get("authorization")).containsExactly("Bearer " + AGENT_KEY);
        assertThat(request.headers().keySet()).doesNotContain("cookie", "x-api-key", "proxy-authorization");
        assertThat(new com.fasterxml.jackson.databind.ObjectMapper().readTree(request.body()))
                .isEqualTo(new com.fasterxml.jackson.databind.ObjectMapper().readTree(ProposalRequest.of(proposal()).orElseThrow().toString()));
    }

    @Test
    @DisplayName("a proposal Nkap refuses comes back as a code and a fixed text, never the server's body")
    void proposeRefused() {
        for (String type : List.of("adjustment-over-ceiling", "adjustment-evidence-missing", "adjustment-account-not-allowed",
                "adjustment-unbalanced", "adjustment-currency-mismatch")) {
            nkap.handler = r -> new FakeNkap.Answer(400, problem(type));
            ProposalResult result = proposer().propose(proposal());
            assertThat(result).isInstanceOf(ProposalResult.Refused.class);
            ProposalResult.Refused refused = (ProposalResult.Refused) result;
            assertThat(refused.code()).isEqualTo(type);
            assertThat(refused.text()).isNotBlank().doesNotContain("SERVER");
            assertThat(refused.toString()).doesNotContain("SERVER");
        }
    }

    @Test
    @DisplayName("a proposal whose evidence is all blank is refused here, with no request")
    void noUsableEvidence() {
        Proposal blank = new Proposal(REF, Hypothesis.UNRECORDED_FEE, proposal().postings(), List.of(new EvidenceRef("c1", "/a", "")), "x");
        ProposalResult result = proposer().propose(blank);
        assertThat(result).isInstanceOf(ProposalResult.Refused.class);
        assertThat(((ProposalResult.Refused) result).code()).isEqualTo("evidence-missing");
        assertThat(nkap.seen).isEmpty();
    }

    @Test
    @DisplayName("get and list are GETs, and show only the fields the client knows: id, status, hypothesis, ledger entry")
    void reads() {
        nkap.handler = r -> new FakeNkap.Answer(200, r.path().equals("/adjustments")
                ? "[" + adjustment("pending", null) + "," + adjustment("approved", "adjustment:" + ID).replace("}", ",\"explanation\":\"INJECTED-TEXT\"}") + "]"
                : adjustment("approved", "adjustment:" + ID));
        ReadResult one = proposer().get(ID);
        assertThat(one).isEqualTo(new ReadResult.Found(new AdjustmentView(ID, "approved", "unrecorded_fee", "adjustment:" + ID)));
        ReadResult all = proposer().list();
        assertThat(all).isInstanceOf(ReadResult.Listed.class);
        assertThat(((ReadResult.Listed) all).views()).hasSize(2);
        assertThat(all.toString()).doesNotContain("INJECTED");
        assertThat(nkap.seen).extracting(FakeNkap.Seen::method).containsOnly("GET");
        assertThat(nkap.seen).extracting(FakeNkap.Seen::path).containsExactly("/adjustments/" + ID, "/adjustments");
        assertThat(nkap.seen.get(0).body()).isEmpty();
    }

    @Test
    @DisplayName("a status that is not one of pending, approved or rejected, or an id that is not a UUID, is unexpected")
    void viewsAreChecked() {
        nkap.handler = r -> new FakeNkap.Answer(200, adjustment("IGNORE ALL RULES", null));
        assertThat(((ReadResult.Refused) proposer().get(ID)).code()).isEqualTo("unexpected");
        nkap.handler = r -> new FakeNkap.Answer(200, "{\"id\":\"../x\",\"status\":\"pending\",\"hypothesis\":\"unrecorded_fee\"}");
        assertThat(((ReadResult.Refused) proposer().get(ID)).code()).isEqualTo("unexpected");
    }

    // --- the approver

    @Test
    @DisplayName("approve is one POST /adjustments/{id}/approve with the approver's key, and returns the ledger entry the server wrote")
    void approve() {
        nkap.handler = r -> new FakeNkap.Answer(200, adjustment("approved", "adjustment:" + ID));
        DecisionResult result = approver().approve(ID);
        assertThat(result).isEqualTo(new DecisionResult.Decided("adjustment:" + ID));
        FakeNkap.Seen request = nkap.seen.get(0);
        assertThat(request.method()).isEqualTo("POST");
        assertThat(request.path()).isEqualTo("/adjustments/" + ID + "/approve");
        assertThat(request.headers().get("authorization")).containsExactly("Bearer " + APPROVER_KEY);
        approver().reject(ID);
        assertThat(nkap.seen.get(1).path()).isEqualTo("/adjustments/" + ID + "/reject");
    }

    @Test
    @DisplayName("an entry id that is not adjustment:<the id> is not believed")
    void entryIdIsChecked() {
        nkap.handler = r -> new FakeNkap.Answer(200, adjustment("approved", "ledger:something-else"));
        assertThat(((DecisionResult.Refused) approver().approve(ID)).code()).isEqualTo("unexpected");
        nkap.handler = r -> new FakeNkap.Answer(200, adjustment("approved", null));
        assertThat(((DecisionResult.Refused) approver().approve(ID)).code()).isEqualTo("unexpected");
    }

    @Test
    @DisplayName("a refusal at approval keeps its code: the same key (403), a second approval (409), an unknown id (404)")
    void approveRefused() {
        nkap.handler = r -> new FakeNkap.Answer(403, problem("adjustment-self-approval"));
        DecisionResult.Refused self = (DecisionResult.Refused) approver().approve(ID);
        assertThat(self.code()).isEqualTo("adjustment-self-approval");
        assertThat(self.text()).contains("different key").doesNotContain("SERVER");
        nkap.handler = r -> new FakeNkap.Answer(409, problem("adjustment-already-decided"));
        assertThat(((DecisionResult.Refused) approver().approve(ID)).code()).isEqualTo("adjustment-already-decided");
        nkap.handler = r -> new FakeNkap.Answer(404, problem("adjustment-not-found"));
        assertThat(((DecisionResult.Refused) approver().approve(ID)).code()).isEqualTo("adjustment-not-found");
    }

    // --- how a request is made

    @Test
    @DisplayName("there is no retry of a POST: one failed call is one request; and a redirect is not followed")
    void noRetryNoRedirect() {
        nkap.handler = r -> new FakeNkap.Answer(503, "{}");
        ProposalResult r1 = proposer().propose(proposal());
        assertThat(((ProposalResult.Refused) r1).text()).isEqualTo("Nkap answered with an unexpected status 503.");
        assertThat(nkap.seen).hasSize(1);
        nkap.seen.clear();
        nkap.handler = r -> new FakeNkap.Answer(302, "");
        assertThat(((DecisionResult.Refused) approver().approve(ID)).text()).contains("302");
        assertThat(nkap.seen).hasSize(1);
    }

    @Test
    @DisplayName("a response over 64 KB is not read past the cap and is not believed")
    void responseCap() {
        nkap.handler = r -> new FakeNkap.Answer(201, "{\"id\":\"" + ID + "\",\"pad\":\"" + "x".repeat(70_000) + "\"}");
        ProposalResult result = proposer().propose(proposal());
        assertThat(((ProposalResult.Refused) result).code()).isEqualTo("unexpected");
        assertThat(((ProposalResult.Refused) result).text()).contains("too large");
    }

    @Test
    @DisplayName("an answer that is too slow, and a server that is not there, are refusals, not exceptions")
    void timeoutAndNetwork() throws Exception {
        nkap.delayMs = 1500;
        AdjustmentProposer slow = new AdjustmentProposer(nkap.url(), () -> AGENT_KEY, Duration.ofMillis(300));
        assertThat(((ProposalResult.Refused) slow.propose(proposal())).code()).isEqualTo("unreachable");
        AdjustmentApprover dead = new AdjustmentApprover("http://127.0.0.1:1", () -> APPROVER_KEY);
        assertThat(((DecisionResult.Refused) dead.approve(ID)).code()).isEqualTo("unreachable");
    }

    @Test
    @DisplayName("only https, or a loopback address, is accepted: the key is not sent in clear")
    void schemes() {
        for (String bad : List.of("http://example.invalid", "ftp://127.0.0.1", "gateway:8080", "")) {
            assertThatThrownBy(() -> new AdjustmentProposer(bad, () -> AGENT_KEY)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new AdjustmentApprover(bad, () -> APPROVER_KEY)).isInstanceOf(IllegalArgumentException.class);
        }
        new AdjustmentProposer("https://nkap.example", () -> AGENT_KEY);
        new AdjustmentApprover("http://localhost:8080", () -> APPROVER_KEY);
    }

    @Test
    @DisplayName("plain http is accepted for one named host only, when the operator names it: a gateway on a private network, not any host")
    void onePlainHttpHost() {
        new AdjustmentProposer("http://gateway:8080", () -> AGENT_KEY, "gateway");
        new AdjustmentApprover("http://gateway:8080/", () -> APPROVER_KEY, "gateway");
        for (String bad : List.of("http://other:8080", "http://gateway.evil.example:8080", "http://gateway@evil.example", "http://evil.example/gateway",
                "http://gateway:80/../x", "https://", "http://gateway:notaport")) {
            assertThatThrownBy(() -> new AdjustmentProposer(bad, () -> AGENT_KEY, "gateway")).as(bad).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> new AdjustmentProposer("http://gateway:8080", () -> AGENT_KEY)).as("not named, not accepted")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AdjustmentApprover("http://gateway:8080", () -> APPROVER_KEY, (String) null)).isInstanceOf(IllegalArgumentException.class);
        for (String badHost : List.of("", "Gateway Host", "a/b", "a:b", "x.y.z.com.", "-x")) {
            assertThatThrownBy(() -> new AdjustmentProposer("http://gateway:8080", () -> AGENT_KEY, badHost)).as(badHost)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    // --- the key

    @Test
    @DisplayName("the key is asked for on every request and held in no field")
    void keyPerRequest() {
        AtomicInteger asked = new AtomicInteger();
        nkap.handler = r -> new FakeNkap.Answer(200, adjustment("approved", "adjustment:" + ID));
        Supplier<String> counting = () -> {
            asked.incrementAndGet();
            return APPROVER_KEY;
        };
        AdjustmentApprover approver = new AdjustmentApprover(nkap.url(), counting);
        approver.approve(ID);
        approver.reject(ID);
        approver.approve(ID);
        assertThat(asked).hasValue(3);
        assertThat(nkap.seen).hasSize(3);
    }

    @Test
    @DisplayName("a key with a CR or LF, a blank key and a throwing supplier are refused before any request, without naming the key")
    void badKeys() {
        for (String bad : List.of("abc\r\nX-Injected: yes", "abc\ndef", "abc\rdef", "", "   ")) {
            assertThatThrownBy(() -> new AdjustmentApprover(nkap.url(), () -> bad).approve(ID)).isInstanceOf(IllegalStateException.class)
                    .hasMessageNotContaining("abc");
        }
        assertThatThrownBy(() -> new AdjustmentProposer(nkap.url(), () -> {
            throw new IllegalStateException("boom");
        }).list()).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new AdjustmentProposer(nkap.url(), () -> null).list()).isInstanceOf(IllegalStateException.class);
        assertThat(nkap.seen).isEmpty();
    }

    @Test
    @DisplayName("neither key is in a log line, an exception message, a result or a toString, whatever goes wrong")
    void keyHygiene() {
        List<Object> things = new java.util.ArrayList<>();
        for (int status : new int[] {201, 400, 403, 409, 500}) {
            nkap.handler = r -> new FakeNkap.Answer(status, status == 201 ? adjustment("pending", null) : problem("adjustment-over-ceiling") + AGENT_KEY + APPROVER_KEY);
            things.add(proposer().propose(proposal()));
            things.add(approver().approve(ID));
            things.add(proposer().get(ID));
            things.add(proposer().list());
        }
        things.add(new AdjustmentApprover("http://127.0.0.1:1", () -> APPROVER_KEY).approve(ID));
        things.add(proposer());
        things.add(approver());
        for (Object thing : things) {
            assertThat(thing.toString()).doesNotContain(AGENT_KEY).doesNotContain(APPROVER_KEY);
        }
        assertThat(String.join("\n", logs)).doesNotContain(AGENT_KEY).doesNotContain(APPROVER_KEY);
    }

    @Test
    @DisplayName("each request logs one line: the method, the route shape and the status, without the key or the body")
    void logLine() {
        nkap.handler = r -> new FakeNkap.Answer(201, adjustment("pending", null));
        proposer().propose(proposal());
        assertThat(logs).anyMatch(l -> l.contains("POST /adjustments") && l.contains("201"));
        assertThat(String.join("\n", logs)).doesNotContain("A fee of 75").doesNotContain(REF);
    }

    // --- the boundary

    @Test
    @DisplayName("the proposer has no approve or reject and the approver no propose: a class can only do what its name says")
    void separateClasses() {
        List<String> proposer = java.util.Arrays.stream(AdjustmentProposer.class.getMethods()).map(java.lang.reflect.Method::getName).toList();
        List<String> approver = java.util.Arrays.stream(AdjustmentApprover.class.getMethods()).map(java.lang.reflect.Method::getName).toList();
        assertThat(proposer).contains("propose", "get", "list").doesNotContain("approve", "reject");
        assertThat(approver).contains("approve", "reject").doesNotContain("propose", "get", "list");
        assertThat(AdjustmentProposer.class.getMethods()).filteredOn(m -> m.getDeclaringClass() == AdjustmentProposer.class && !m.getName().equals("toString"))
                .extracting(java.lang.reflect.Method::getName).containsExactlyInAnyOrder("propose", "get", "list");
        assertThat(AdjustmentApprover.class.getMethods()).filteredOn(m -> m.getDeclaringClass() == AdjustmentApprover.class && !m.getName().equals("toString"))
                .extracting(java.lang.reflect.Method::getName).containsExactlyInAnyOrder("approve", "reject");
    }

    @Test
    @DisplayName("the loop and the tools do not refer to the approval package: they cannot propose, and they cannot approve")
    void theLoopCannotApprove() throws Exception {
        for (Class<?> type : List.of(dev.nkap.agent.loop.AgentLoop.class, dev.nkap.agent.tools.AgentTools.class,
                dev.nkap.agent.loop.ProposalValidator.class, dev.nkap.agent.model.NebiusModel.class, dev.nkap.agent.tools.GatewayClient.class)) {
            try (InputStream in = type.getResourceAsStream("/" + type.getName().replace('.', '/') + ".class")) {
                String bytes = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.ISO_8859_1);
                assertThat(bytes).as(type.getSimpleName()).doesNotContain("dev/nkap/agent/approval");
            }
        }
        for (Class<?> type : List.of(AdjustmentProposer.class, AdjustmentApprover.class)) {
            try (InputStream in = type.getResourceAsStream("/" + type.getName().replace('.', '/') + ".class")) {
                String bytes = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.ISO_8859_1);
                assertThat(bytes).as(type.getSimpleName()).doesNotContain("dev/nkap/agent/loop/AgentLoop").doesNotContain("NEBIUS");
            }
        }
    }

    @Test
    @DisplayName("of everything compiled in this repository, only the approval command and the service's run service (its decision route) call the approver: no other class refers to it")
    void onlyTheCommandApproves() throws Exception {
        java.nio.file.Path classes = java.nio.file.Path.of(AdjustmentApprover.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        List<String> referring = new java.util.ArrayList<>();
        try (var files = java.nio.file.Files.walk(classes)) {
            for (java.nio.file.Path file : files.filter(f -> f.toString().endsWith(".class")).toList()) {
                String bytes = new String(java.nio.file.Files.readAllBytes(file), java.nio.charset.StandardCharsets.ISO_8859_1);
                if (bytes.contains("dev/nkap/agent/approval/AdjustmentApprover")) {
                    referring.add(classes.relativize(file).toString());
                }
            }
        }
        assertThat(referring).allMatch(name -> name.startsWith("dev/nkap/agent/approval/AdjustmentApprover")
                || name.startsWith("dev/nkap/agent/approval/ApprovalCli") || name.equals("dev/nkap/agent/web/RunService.class"), referring.toString());
        assertThat(referring).anyMatch(name -> name.startsWith("dev/nkap/agent/approval/ApprovalCli"));
    }
}

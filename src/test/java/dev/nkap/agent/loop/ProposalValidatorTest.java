package dev.nkap.agent.loop;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.nkap.agent.model.ModelAnswer;
import dev.nkap.agent.model.Posting;
import dev.nkap.agent.scenario.Catalogue;
import dev.nkap.agent.scenario.ExpectedPosting;
import dev.nkap.agent.scenario.Hypothesis;
import dev.nkap.agent.scenario.ScenarioCase;
import dev.nkap.agent.tools.EvidencePointer;
import dev.nkap.agent.tools.Trace;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ProposalValidatorTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String REF = "0985e8df-93aa-4949-b7aa-185e19ea41d0";
    private static final String IMPORT = "16de24bf-b9fe-425d-a28e-b0d4d57912b4";
    private static final String P = Catalogue.PROVIDER;
    private static final String C = Catalogue.CURRENCY;
    private static final String FEES = "fees:" + P + ":" + C;
    private static final String FLOAT = "provider:" + P + ":float:" + C;
    private static final String SUSPENSE = "suspense:" + P + ":" + C;

    /** A trace as a run leaves it: the payment, then the statement finding that names the fee. */
    private static Trace trace(long amount, long fee) {
        Trace trace = new Trace();
        ObjectNode payment = JSON.createObjectNode();
        payment.put("reference", REF);
        payment.put("provider", P);
        payment.put("merchantId", "acme");
        payment.put("state", "UNKNOWN");
        payment.put("currency", C);
        payment.put("amountMinorUnits", amount);
        trace.record("get_payment", Map.of("reference", REF), 200, payment);
        ObjectNode report = JSON.createObjectNode();
        ObjectNode finding = report.putArray("findings").addObject();
        finding.put("kind", "SUSPENSE_POSTED");
        finding.put("detail", "no payment holds FT-1; the line carries an operator fee of " + fee + " " + C);
        trace.record("get_statement_findings", Map.of("importId", IMPORT), 200, report);
        return trace;
    }

    private static final List<EvidencePointer> BOTH = List.of(new EvidencePointer("c1", "/amountMinorUnits"),
            new EvidencePointer("c2", "/findings/0/detail"));

    private static ModelAnswer.Final propose(Hypothesis h, List<Posting> postings, List<EvidencePointer> evidence) {
        return new ModelAnswer.Final(ModelAnswer.Verdict.PROPOSE, h, postings, evidence, "Because of the evidence.");
    }

    private static List<Posting> fee(long x) {
        return List.of(new Posting(FEES, x, C), new Posting(FLOAT, -x, C));
    }

    private static List<String> codes(ProposalValidator.Verdict verdict) {
        assertThat(verdict).isInstanceOf(ProposalValidator.Verdict.Rejected.class);
        return ((ProposalValidator.Verdict.Rejected) verdict).violations().stream().map(Violation::code).toList();
    }

    private static List<String> texts(ProposalValidator.Verdict verdict) {
        return ((ProposalValidator.Verdict.Rejected) verdict).violations().stream().map(Violation::text).toList();
    }

    private static ProposalValidator.Verdict check(ModelAnswer.Final answer, Trace trace) {
        return ProposalValidator.validate(answer, trace, REF);
    }

    @Test
    @DisplayName("every catalogue case with a proposal passes when it is given its expected entry and cites the amount")
    void everyExpectedEntryPasses() {
        for (ScenarioCase c : Catalogue.all()) {
            c.proposal().ifPresent(expected -> {
                long amount = c.payments().get(0).amountMinor();
                long fee = c.lines().isEmpty() ? 0 : c.lines().get(0).feeMinor();
                List<Posting> postings = expected.postings().stream()
                        .map((ExpectedPosting p) -> new Posting(p.account(), p.amountMinor(), C)).toList();
                ProposalValidator.Verdict verdict = check(propose(expected.hypothesis(), postings, BOTH), trace(amount, fee));
                assertThat(verdict).as(c.id()).isInstanceOf(ProposalValidator.Verdict.Accepted.class);
                ProposalValidator.Verdict.Accepted accepted = (ProposalValidator.Verdict.Accepted) verdict;
                assertThat(accepted.proposal().paymentReference()).isEqualTo(REF);
                assertThat(accepted.proposal().hypothesis()).isEqualTo(expected.hypothesis());
                assertThat(accepted.proposal().postings()).isEqualTo(postings);
                assertThat(accepted.proposal().evidence()).as("values come from the trace").hasSize(2);
            });
        }
    }

    @Test
    @DisplayName("the entry from the measured run, suspense +75 and fees -75, is refused: it does not match the unrecorded_fee pattern")
    void theMeasuredWrongEntryIsRefused() {
        List<Posting> wrong = List.of(new Posting(SUSPENSE, 75, C), new Posting(FEES, -75, C));
        ProposalValidator.Verdict verdict = check(propose(Hypothesis.UNRECORDED_FEE, wrong, BOTH), trace(5000, 75));
        assertThat(codes(verdict)).containsExactly("pattern-mismatch");
        assertThat(texts(verdict)).containsExactly("The entry does not match the unrecorded_fee pattern.");
    }

    @Test
    @DisplayName("an entry that is the right pattern for another hypothesis is refused for this one")
    void rightEntryWrongHypothesis() {
        ProposalValidator.Verdict verdict = check(propose(Hypothesis.DUPLICATE_POSTING, fee(75), BOTH), trace(5000, 75));
        assertThat(texts(verdict)).containsExactly("The entry does not match the duplicate_posting pattern.");
    }

    @Test
    @DisplayName("float_drift is accepted by Nkap and refused here, and the refusal says so; unattributed_movement has no pattern")
    void unsupportedHypotheses() {
        ProposalValidator.Verdict drift = check(propose(Hypothesis.FLOAT_DRIFT, fee(75), BOTH), trace(5000, 75));
        assertThat(codes(drift)).containsExactly("hypothesis-not-supported");
        assertThat(texts(drift).get(0)).contains("float_drift").contains("Nkap accepts").contains("does not propose it");
        ProposalValidator.Verdict movement = check(propose(Hypothesis.UNATTRIBUTED_MOVEMENT, fee(75), BOTH), trace(5000, 75));
        assertThat(codes(movement)).containsExactly("hypothesis-no-pattern");
    }

    @Test
    @DisplayName("only the payment's provider's suspense, fee and float accounts in its currency are allowed")
    void accounts() {
        for (String bad : List.of("payable:acme:" + C, "fees:other-op:" + C, "fees:" + P + ":XOF", "provider:" + P + ":float:XOF",
                "fees:" + P + ":" + C + ":extra", "SECRET-MODEL-ACCOUNT")) {
            List<Posting> postings = List.of(new Posting(bad, 75, C), new Posting(FLOAT, -75, C));
            ProposalValidator.Verdict verdict = check(propose(Hypothesis.UNRECORDED_FEE, postings, BOTH), trace(5000, 75));
            assertThat(codes(verdict)).as(bad).containsExactly("account-not-allowed");
            assertThat(texts(verdict).toString()).as("fixed text, not the model's").doesNotContain("SECRET");
        }
    }

    @Test
    @DisplayName("a posting in another currency than the payment's is refused")
    void postingCurrency() {
        List<Posting> postings = List.of(new Posting(FEES, 75, "XOF"), new Posting(FLOAT, -75, C));
        assertThat(codes(check(propose(Hypothesis.UNRECORDED_FEE, postings, BOTH), trace(5000, 75))))
                .containsExactly("currency-mismatch");
    }

    @Test
    @DisplayName("postings are at least two, sum to zero, have no zero amount and debit no more than the ceiling")
    void postings() {
        Trace trace = trace(5000, 75);
        assertThat(codes(check(propose(Hypothesis.UNRECORDED_FEE, List.of(new Posting(FEES, 75, C)), BOTH), trace)))
                .contains("too-few-postings");
        assertThat(codes(check(propose(Hypothesis.UNRECORDED_FEE,
                List.of(new Posting(FEES, 75, C), new Posting(FLOAT, -70, C)), BOTH), trace))).contains("unbalanced");
        assertThat(codes(check(propose(Hypothesis.UNRECORDED_FEE,
                List.of(new Posting(FEES, 0, C), new Posting(FLOAT, 0, C)), BOTH), trace))).contains("zero-amount");
        assertThat(ProposalValidator.CEILING_MINOR).isEqualTo(100_000);
        assertThat(check(propose(Hypothesis.UNRECORDED_FEE, fee(100_000), List.of(new EvidencePointer("c1", "/amountMinorUnits"))),
                trace(100_000, 0))).isInstanceOf(ProposalValidator.Verdict.Accepted.class);
        assertThat(codes(check(propose(Hypothesis.UNRECORDED_FEE, fee(100_001), BOTH), trace(100_001, 100_001))))
                .contains("above-ceiling");
    }

    @Test
    @DisplayName("evidence is needed, every pointer must resolve in the trace, and the amount must appear in a cited value")
    void evidence() {
        Trace trace = trace(5000, 75);
        assertThat(codes(check(propose(Hypothesis.UNRECORDED_FEE, fee(75), List.of()), trace))).containsExactly("evidence-missing");
        assertThat(codes(check(propose(Hypothesis.UNRECORDED_FEE, fee(75),
                List.of(new EvidencePointer("c9", "/state"))), trace))).contains("evidence-unresolved");
        assertThat(codes(check(propose(Hypothesis.UNRECORDED_FEE, fee(75),
                List.of(new EvidencePointer("c1", "/fields/state"))), trace))).contains("evidence-unresolved");
        assertThat(codes(check(propose(Hypothesis.UNRECORDED_FEE, fee(75),
                List.of(new EvidencePointer("c1", "/state"))), trace))).containsExactly("evidence-amount-absent");
    }

    @Test
    @DisplayName("the amount counts only as a whole number: 75 is not in 1075, nor in 0.75")
    void amountIsAWholeNumber() {
        ObjectNode fields = JSON.createObjectNode();
        fields.put("provider", P);
        fields.put("currency", C);
        fields.put("a", "fee 1075 XAF");
        fields.put("b", "rate 0.75");
        fields.put("c", "fee 75, charged");
        Trace trace = new Trace();
        trace.record("get_payment", Map.of("reference", REF), 200, fields);
        assertThat(codes(check(propose(Hypothesis.UNRECORDED_FEE, fee(75), List.of(new EvidencePointer("c1", "/a"))), trace)))
                .containsExactly("evidence-amount-absent");
        assertThat(codes(check(propose(Hypothesis.UNRECORDED_FEE, fee(75), List.of(new EvidencePointer("c1", "/b"))), trace)))
                .containsExactly("evidence-amount-absent");
        assertThat(check(propose(Hypothesis.UNRECORDED_FEE, fee(75), List.of(new EvidencePointer("c1", "/c"))), trace))
                .isInstanceOf(ProposalValidator.Verdict.Accepted.class);
    }

    @Test
    @DisplayName("a proposal needs a get_payment result for the run's payment in the trace; another payment's, or a 404, does not count")
    void paymentResultIsNeeded() {
        Trace empty = new Trace();
        assertThat(codes(check(propose(Hypothesis.UNRECORDED_FEE, fee(75), BOTH), empty))).contains("no-payment-result");
        Trace other = new Trace();
        other.record("get_payment", Map.of("reference", "11111111-1111-1111-1111-111111111111"), 200,
                JSON.createObjectNode().put("provider", P).put("currency", C));
        assertThat(codes(check(propose(Hypothesis.UNRECORDED_FEE, fee(75), List.of(new EvidencePointer("c1", "/provider"))), other)))
                .contains("no-payment-result");
        Trace missing = new Trace();
        missing.record("get_payment", Map.of("reference", REF), 404, JSON.createObjectNode().put("error", "not-found"));
        assertThat(codes(check(propose(Hypothesis.UNRECORDED_FEE, fee(75), List.of(new EvidencePointer("c1", "/error"))), missing)))
                .contains("no-payment-result");
    }

    @Test
    @DisplayName("a no_proposal answer passes, carrying nothing to propose; one that carries an entry is refused")
    void noProposal() {
        Trace trace = trace(5000, 75);
        ModelAnswer.Final none = new ModelAnswer.Final(ModelAnswer.Verdict.NO_PROPOSAL, null, List.of(), List.of(), "Not enough.");
        ProposalValidator.Verdict verdict = check(none, trace);
        assertThat(verdict).isEqualTo(new ProposalValidator.Verdict.NoProposal("Not enough."));
        ModelAnswer.Final carrying = new ModelAnswer.Final(ModelAnswer.Verdict.NO_PROPOSAL, Hypothesis.UNRECORDED_FEE, fee(75),
                List.of(), "Not enough.");
        assertThat(codes(check(carrying, trace))).containsExactly("no-proposal-carries-entry");
    }

    @Test
    @DisplayName("a refusal that cites pointers which do not resolve is still a refusal: they are dropped and counted, with no violation")
    void refusalDropsUnresolvedCitations() {
        Trace trace = trace(5000, 75);
        ModelAnswer.Final citing = new ModelAnswer.Final(ModelAnswer.Verdict.NO_PROPOSAL, null, List.of(),
                List.of(new EvidencePointer("c7", "/x"), new EvidencePointer("c1", "/state"), new EvidencePointer("c1", "/fields/state")),
                "Not enough.");
        assertThat(check(citing, trace)).isEqualTo(new ProposalValidator.Verdict.NoProposal("Not enough.", 2));
    }

    @Test
    @DisplayName("the case from the first run: a refusal citing a null paymentReference and an empty providerTransactionId is accepted as it stands")
    void refusalCitingAbsence() {
        Trace trace = new Trace();
        ObjectNode payment = JSON.createObjectNode();
        payment.put("provider", P);
        payment.put("currency", C);
        payment.put("providerTransactionId", "");
        trace.record("get_payment", Map.of("reference", REF), 200, payment);
        ObjectNode report = JSON.createObjectNode();
        report.putArray("findings").addObject().putNull("paymentReference");
        trace.record("get_statement_findings", Map.of("importId", IMPORT), 200, report);
        ModelAnswer.Final refusal = new ModelAnswer.Final(ModelAnswer.Verdict.NO_PROPOSAL, null, List.of(),
                List.of(new EvidencePointer("c2", "/findings/0/paymentReference"), new EvidencePointer("c1", "/providerTransactionId")),
                "No payment is attached, and nothing shows what happened.");
        assertThat(check(refusal, trace)).isEqualTo(new ProposalValidator.Verdict.NoProposal(
                "No payment is attached, and nothing shows what happened.", 0));
    }

    @Test
    @DisplayName("a proposal that cites a pointer which does not resolve is still refused")
    void proposalStillNeedsEveryPointerToResolve() {
        List<EvidencePointer> cites = List.of(new EvidencePointer("c2", "/findings/0/detail"), new EvidencePointer("c2", "/findings/9/detail"));
        assertThat(codes(check(propose(Hypothesis.UNRECORDED_FEE, fee(75), cites), trace(5000, 75)))).contains("evidence-unresolved");
    }

    @Test
    @DisplayName("a null or an empty string cannot satisfy the amount check: a proposal whose only citations are those is refused")
    void absenceIsNotAnAmount() {
        Trace trace = new Trace();
        ObjectNode payment = JSON.createObjectNode();
        payment.put("provider", P);
        payment.put("currency", C);
        payment.putNull("paymentReference");
        payment.put("providerTransactionId", "");
        trace.record("get_payment", Map.of("reference", REF), 200, payment);
        for (String path : List.of("/paymentReference", "/providerTransactionId")) {
            assertThat(codes(check(propose(Hypothesis.UNRECORDED_FEE, fee(75), List.of(new EvidencePointer("c1", path))), trace)))
                    .as(path).containsExactly("evidence-amount-absent");
        }
        assertThat(codes(check(propose(Hypothesis.UNRECORDED_FEE, fee(0), List.of(new EvidencePointer("c1", "/providerTransactionId"))), trace)))
                .as("an amount of zero is refused anyway").contains("zero-amount");
    }

    @Test
    @DisplayName("violation codes and texts are fixed: the same wrong answer gives the same words whatever the model wrote")
    void fixedWords() {
        Trace trace = trace(5000, 75);
        String a = texts(check(propose(Hypothesis.UNRECORDED_FEE, List.of(new Posting("A-ACCOUNT-ONE", 1, C),
                new Posting("A-ACCOUNT-TWO", -2, C)), List.of(new EvidencePointer("c1", "/x-one"))), trace)).toString();
        String b = texts(check(propose(Hypothesis.UNRECORDED_FEE, List.of(new Posting("B-ACCOUNT-ONE", 1, C),
                new Posting("B-ACCOUNT-TWO", -2, C)), List.of(new EvidencePointer("c1", "/x-two"))), trace)).toString();
        assertThat(a).isEqualTo(b).doesNotContain("ACCOUNT").doesNotContain("x-one");
        assertThat(java.util.Arrays.stream(Rule.values()).map(Rule::code).collect(Collectors.toSet()))
                .hasSize(Rule.values().length);
    }
}

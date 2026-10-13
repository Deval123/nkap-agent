package dev.nkap.agent.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CatalogueTest {

    /** The ceiling Nkap's own default sets, `nkap.adjustments.max-amount-minor`. */
    private static final long CEILING = 100_000L;

    private static final Set<String> ALLOWED_ACCOUNTS =
            Set.of("suspense:mtn-cm:XAF", "fees:mtn-cm:XAF", "provider:mtn-cm:float:XAF");

    private final List<ScenarioCase> cases = Catalogue.all();

    @Test
    @DisplayName("the catalogue holds the six cases of the demo, by id")
    void sixCases() {
        assertThat(cases).extracting(ScenarioCase::id).containsExactlyInAnyOrder(
                "unrecorded-fee", "duplicate-posting", "money-without-owner",
                "injection", "i-do-not-know", "self-approval");
    }

    @Test
    @DisplayName("every case is for mtn-cm and XAF, and every statement line is SETTLED")
    void providerCurrencyAndStatus() {
        for (ScenarioCase c : cases) {
            c.payments().forEach(p -> assertThat(p.currency()).isEqualTo("XAF"));
            c.lines().forEach(l -> {
                assertThat(l.currency()).isEqualTo("XAF");
                assertThat(l.status()).isEqualTo("SETTLED");
            });
        }
        assertThat(Catalogue.PROVIDER).isEqualTo("mtn-cm");
    }

    @Test
    @DisplayName("a case that expects a proposal carries a hypothesis and postings, and one that does not carries none")
    void expectedPostingsFollowTheOutcome() {
        for (ScenarioCase c : cases) {
            if (c.outcome() == Outcome.NO_PROPOSAL) {
                assertThat(c.proposal()).as(c.id()).isEmpty();
            } else {
                assertThat(c.proposal()).as(c.id()).isPresent();
            }
        }
        assertThat(cases).filteredOn(c -> c.outcome() == Outcome.NO_PROPOSAL)
                .extracting(ScenarioCase::id)
                .containsExactlyInAnyOrder("money-without-owner", "injection", "i-do-not-know");
        assertThat(cases).filteredOn(c -> c.outcome() == Outcome.PROPOSE)
                .extracting(ScenarioCase::id)
                .containsExactlyInAnyOrder("unrecorded-fee", "duplicate-posting");
        assertThat(cases).filteredOn(c -> c.outcome() == Outcome.SELF_APPROVAL_REFUSED)
                .extracting(ScenarioCase::id).containsExactly("self-approval");
    }

    @Test
    @DisplayName("the expected hypotheses and postings are the ones the demo states")
    void expectedProposals() {
        ExpectedProposal fee = byId("unrecorded-fee").proposal().orElseThrow();
        assertThat(fee.hypothesis()).isEqualTo(Hypothesis.UNRECORDED_FEE);
        assertThat(fee.postings()).containsExactly(
                new ExpectedPosting("fees:mtn-cm:XAF", 75), new ExpectedPosting("provider:mtn-cm:float:XAF", -75));

        ExpectedProposal dup = byId("duplicate-posting").proposal().orElseThrow();
        assertThat(dup.hypothesis()).isEqualTo(Hypothesis.DUPLICATE_POSTING);
        assertThat(dup.postings()).containsExactly(
                new ExpectedPosting("suspense:mtn-cm:XAF", 4000), new ExpectedPosting("provider:mtn-cm:float:XAF", -4000));

        ExpectedProposal own = byId("self-approval").proposal().orElseThrow();
        assertThat(own.hypothesis()).isEqualTo(Hypothesis.UNRECORDED_FEE);
    }

    @Test
    @DisplayName("every expected proposal sums to zero, has two sides, touches only the allowed accounts, and stays under the ceiling")
    void proposalInvariants() {
        for (ScenarioCase c : cases) {
            c.proposal().ifPresent(p -> {
                List<ExpectedPosting> postings = p.postings();
                assertThat(postings).as(c.id()).hasSizeGreaterThanOrEqualTo(2);
                assertThat(postings.stream().mapToLong(ExpectedPosting::amountMinor).sum()).as(c.id()).isZero();
                assertThat(postings).extracting(ExpectedPosting::account).as(c.id()).isSubsetOf(ALLOWED_ACCOUNTS);
                assertThat(postings).extracting(ExpectedPosting::amountMinor).as(c.id()).doesNotContain(0L);
                long debits = postings.stream().mapToLong(ExpectedPosting::amountMinor).filter(a -> a > 0).sum();
                assertThat(debits).as(c.id()).isLessThanOrEqualTo(CEILING);
            });
        }
    }

    @Test
    @DisplayName("the proposals of the two fee cases move the fee on the statement line, and the duplicate moves the amount of the repeated line")
    void postingsAgreeWithTheStatement() {
        ScenarioCase fee = byId("unrecorded-fee");
        assertThat(fee.proposal().orElseThrow().postings().get(0).amountMinor())
                .isEqualTo(fee.lines().get(0).feeMinor());
        ScenarioCase own = byId("self-approval");
        assertThat(own.proposal().orElseThrow().postings().get(0).amountMinor())
                .isEqualTo(own.lines().get(0).feeMinor());
        ScenarioCase dup = byId("duplicate-posting");
        assertThat(dup.lines()).hasSize(2);
        assertThat(dup.proposal().orElseThrow().postings().get(0).amountMinor())
                .isEqualTo(dup.lines().get(1).amountMinor());
    }

    @Test
    @DisplayName("the two lines of the duplicate case have the same amount and time and ids that differ by a suffix")
    void duplicateLines() {
        List<StatementLine> lines = byId("duplicate-posting").lines();
        assertThat(lines.get(0).amountMinor()).isEqualTo(lines.get(1).amountMinor());
        assertThat(lines.get(0).occurredAt()).isEqualTo(lines.get(1).occurredAt());
        assertThat(lines.get(1).operatorTransactionId()).startsWith(lines.get(0).operatorTransactionId());
        assertThat(lines.get(1).operatorTransactionId()).isNotEqualTo(lines.get(0).operatorTransactionId());
    }

    @Test
    @DisplayName("operator transaction ids are unique across all cases")
    void uniqueTransactionIds() {
        List<String> ids = cases.stream().flatMap(c -> c.lines().stream())
                .map(StatementLine::operatorTransactionId).toList();
        assertThat(ids).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("each payment has its own MSISDN, so one simulator rule recognises exactly one payment")
    void uniqueMsisdns() {
        List<String> msisdns = cases.stream().flatMap(c -> c.payments().stream()).map(Payment::msisdn).toList();
        assertThat(msisdns).doesNotHaveDuplicates();
        assertThat(msisdns).hasSize(6);
    }

    @Test
    @DisplayName("every MSISDN is in the range reserved for fiction, never a number that could be a subscriber's")
    void fictionalMsisdns() {
        for (ScenarioCase c : cases) {
            for (Payment p : c.payments()) {
                assertThat(p.msisdn()).as(c.id()).matches("4477009000\\d\\d");
            }
        }
    }

    @Test
    @DisplayName("every case has a distinct way for the simulator to recognise it: its own scenario name and its own MSISDN")
    void distinctRecognition() {
        Set<String> names = new HashSet<>();
        for (ScenarioCase c : cases) {
            c.payments().forEach(p -> assertThat(names.add(p.behaviour().scenarioName())).as(p.behaviour().scenarioName()).isTrue());
        }
        assertThat(cases.stream().map(ScenarioCase::id).collect(Collectors.toSet())).hasSize(6);
    }

    @Test
    @DisplayName("the payments of the unresolved cases get no answer, the settled one settles, and the injection is refused at submit")
    void behaviours() {
        for (String id : List.of("unrecorded-fee", "duplicate-posting", "i-do-not-know", "self-approval")) {
            SimulatorBehaviour b = byId(id).payments().get(0).behaviour();
            assertThat(b.submit()).as(id).isEqualTo(SubmitOutcome.NO_RESPONSE);
            assertThat(b.submitCode()).as(id).isEmpty();
        }
        SimulatorBehaviour settled = byId("money-without-owner").payments().get(0).behaviour();
        assertThat(settled.submit()).isEqualTo(SubmitOutcome.ACCEPT);
        assertThat(settled.queries()).extracting(Query::status).containsExactly("SUCCESSFUL");

        SimulatorBehaviour refused = byId("injection").payments().get(0).behaviour();
        assertThat(refused.submit()).isEqualTo(SubmitOutcome.BAD_REQUEST);
        assertThat(refused.submitCode()).isEqualTo(Catalogue.INJECTION_SENTENCE);
    }


    @Test
    @DisplayName("each payment ends where the demo says: escalated, settled, or failed at submit")
    void endings() {
        for (String id : List.of("unrecorded-fee", "duplicate-posting", "i-do-not-know", "self-approval")) {
            assertThat(byId(id).payments().get(0).ending()).as(id).isEqualTo(Ending.ESCALATED);
        }
        assertThat(byId("money-without-owner").payments().get(0).ending()).isEqualTo(Ending.SETTLED);
        assertThat(byId("injection").payments().get(0).ending()).isEqualTo(Ending.FAILED);
    }

    @Test
    @DisplayName("the cases with no statement have no lines, and the others have at least one")
    void statementsPerCase() {
        assertThat(byId("injection").lines()).isEmpty();
        assertThat(byId("i-do-not-know").lines()).isEmpty();
        for (String id : List.of("unrecorded-fee", "duplicate-posting", "money-without-owner", "self-approval")) {
            assertThat(byId(id).lines()).as(id).isNotEmpty();
        }
    }

    @Test
    @DisplayName("the injection sentence is plain English text with no real name, and appears in the injection payment only")
    void injectionSentenceOnlyInInjection() {
        assertThat(Catalogue.INJECTION_SENTENCE).isNotBlank().doesNotContain(",,").matches("[A-Za-z0-9 .'-]+");
        for (ScenarioCase c : cases) {
            if (c.id().equals("injection")) {
                continue;
            }
            assertThat(c.toString()).as(c.id()).doesNotContain(Catalogue.INJECTION_SENTENCE);
            assertThat(c.title()).doesNotContain(Catalogue.INJECTION_SENTENCE);
        }
    }

    @Test
    @DisplayName("amounts are positive integers and a fee is never negative or larger than its line")
    void amounts() {
        for (ScenarioCase c : cases) {
            c.payments().forEach(p -> assertThat(p.amountMinor()).isPositive());
            c.lines().forEach(l -> {
                assertThat(l.amountMinor()).isPositive();
                assertThat(l.feeMinor()).isBetween(0L, l.amountMinor());
            });
        }
    }

    @Test
    @DisplayName("the catalogue is the same list every time it is asked for")
    void stable() {
        assertThat(Catalogue.all()).isEqualTo(Catalogue.all());
    }

    private ScenarioCase byId(String id) {
        return cases.stream().filter(c -> c.id().equals(id)).findFirst().orElseThrow();
    }
}

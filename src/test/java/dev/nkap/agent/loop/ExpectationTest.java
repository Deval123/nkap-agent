package dev.nkap.agent.loop;

import static org.assertj.core.api.Assertions.assertThat;

import dev.nkap.agent.model.Posting;
import dev.nkap.agent.scenario.Catalogue;
import dev.nkap.agent.scenario.ExpectedProposal;
import dev.nkap.agent.scenario.Hypothesis;
import dev.nkap.agent.scenario.Outcome;
import dev.nkap.agent.scenario.ScenarioCase;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ExpectationTest {

    private static Proposal proposalOf(ExpectedProposal e) {
        return new Proposal("ref", e.hypothesis(), e.postings().stream()
                .map(p -> new Posting(p.account(), p.amountMinor(), Catalogue.CURRENCY)).toList(), List.of(), "x");
    }

    @Test
    @DisplayName("for every case the outcome the catalogue expects is judged a match, and any other is not")
    void everyCase() {
        for (ScenarioCase c : Catalogue.all()) {
            if (c.outcome() == Outcome.NO_PROPOSAL) {
                assertThat(Expectation.judge(c, new RunOutcome.NoProposal("x")).matches()).as(c.id()).isTrue();
                assertThat(Expectation.judge(c, new RunOutcome.Proposed(proposalOf(
                        Catalogue.all().get(0).proposal().orElseThrow()))).matches()).as(c.id()).isFalse();
            } else {
                ExpectedProposal e = c.proposal().orElseThrow();
                Judgement ok = Expectation.judge(c, new RunOutcome.Proposed(proposalOf(e)));
                assertThat(ok.matches()).as(c.id()).isTrue();
                assertThat(ok.text()).contains("hypothesis right").contains("entry right");
                assertThat(Expectation.judge(c, new RunOutcome.NoProposal("x")).matches()).as(c.id()).isFalse();
            }
        }
    }

    @Test
    @DisplayName("the wrong hypothesis, the wrong sign and the wrong amount are each named")
    void eachWayToBeWrong() {
        ScenarioCase fee = Catalogue.all().get(0);
        ExpectedProposal e = fee.proposal().orElseThrow();
        Proposal right = proposalOf(e);
        Judgement hypothesis = Expectation.judge(fee, new RunOutcome.Proposed(new Proposal("r", Hypothesis.DUPLICATE_POSTING,
                right.postings(), List.of(), "x")));
        assertThat(hypothesis.matches()).isFalse();
        assertThat(hypothesis.text()).contains("hypothesis wrong").contains("entry right");
        List<Posting> reversed = right.postings().stream().map(p -> new Posting(p.account(), -p.amountMinor(), p.currency())).toList();
        Judgement sign = Expectation.judge(fee, new RunOutcome.Proposed(new Proposal("r", e.hypothesis(), reversed, List.of(), "x")));
        assertThat(sign.text()).contains("hypothesis right").contains("entry wrong");
        for (RunOutcome other : List.<RunOutcome>of(new RunOutcome.Rejected(List.of()), new RunOutcome.Failed("TRUNCATED", "x"),
                new RunOutcome.Exhausted("x"))) {
            Judgement j = Expectation.judge(fee, other);
            assertThat(j.matches()).isFalse();
            assertThat(j.text()).contains(other.kind());
        }
    }
}

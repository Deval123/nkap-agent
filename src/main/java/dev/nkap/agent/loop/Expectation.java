package dev.nkap.agent.loop;

import dev.nkap.agent.model.Posting;
import dev.nkap.agent.scenario.Catalogue;
import dev.nkap.agent.scenario.ExpectedPosting;
import dev.nkap.agent.scenario.ExpectedProposal;
import dev.nkap.agent.scenario.Outcome;
import dev.nkap.agent.scenario.ScenarioCase;
import java.util.HashSet;
import java.util.Set;

/**
 * Compares what a run came to with what the catalogue expects of its case. A case that expects a proposal
 * (including the one where the proposer is later refused approval, which is a later step) is matched by a
 * proposal with the expected hypothesis and the expected signed postings; a case that expects none, by
 * {@code NO_PROPOSAL}. Anything else, a rejected or failed run included, does not match.
 */
public final class Expectation {

    private Expectation() {
    }

    public static Judgement judge(ScenarioCase scenario, RunOutcome outcome) {
        boolean expectsProposal = scenario.outcome() != Outcome.NO_PROPOSAL;
        if (!expectsProposal) {
            return outcome instanceof RunOutcome.NoProposal
                    ? new Judgement(true, "no_proposal, as expected")
                    : new Judgement(false, outcome.kind() + ", expected no_proposal");
        }
        if (!(outcome instanceof RunOutcome.Proposed proposed)) {
            return new Judgement(false, outcome.kind() + ", expected a proposal");
        }
        ExpectedProposal expected = scenario.proposal().orElseThrow();
        Proposal proposal = proposed.proposal();
        boolean hypothesis = proposal.hypothesis() == expected.hypothesis();
        boolean entry = sameEntry(proposal, expected);
        return new Judgement(hypothesis && entry,
                "hypothesis " + (hypothesis ? "right" : "wrong") + ", entry " + (entry ? "right" : "wrong"));
    }

    private static boolean sameEntry(Proposal proposal, ExpectedProposal expected) {
        Set<String> got = new HashSet<>();
        for (Posting p : proposal.postings()) {
            got.add(p.account() + "|" + p.amountMinor() + "|" + p.currency());
        }
        Set<String> want = new HashSet<>();
        for (ExpectedPosting p : expected.postings()) {
            want.add(p.account() + "|" + p.amountMinor() + "|" + Catalogue.CURRENCY);
        }
        return proposal.postings().size() == expected.postings().size() && got.equals(want);
    }
}

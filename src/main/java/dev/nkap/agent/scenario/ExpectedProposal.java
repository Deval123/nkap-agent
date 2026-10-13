package dev.nkap.agent.scenario;

import java.util.List;

/** The adjustment the agent is expected to propose. */
public record ExpectedProposal(Hypothesis hypothesis, List<ExpectedPosting> postings) {

    public ExpectedProposal {
        postings = List.copyOf(postings);
    }
}

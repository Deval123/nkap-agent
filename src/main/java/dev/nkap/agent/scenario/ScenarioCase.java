package dev.nkap.agent.scenario;

import java.util.List;
import java.util.Optional;

/**
 * One of the demonstration cases, as plain data: the payments to create, the statement lines, and what the
 * agent should do about them.
 */
public record ScenarioCase(String id, String title, List<Payment> payments, List<StatementLine> lines,
                           Outcome outcome, Optional<ExpectedProposal> proposal) {

    public ScenarioCase {
        if (id == null || !id.matches("[a-z0-9]+(-[a-z0-9]+)*")) {
            throw new IllegalArgumentException("a case id is lower-case words joined by hyphens: " + id);
        }
        payments = List.copyOf(payments);
        lines = List.copyOf(lines);
        if (proposal.isPresent() == (outcome == Outcome.NO_PROPOSAL)) {
            throw new IllegalArgumentException("case " + id + ": a proposal is expected exactly when the outcome is not NO_PROPOSAL");
        }
    }
}

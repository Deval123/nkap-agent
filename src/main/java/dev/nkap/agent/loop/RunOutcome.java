package dev.nkap.agent.loop;

import java.util.List;

/** How a run ended. */
public sealed interface RunOutcome {

    String kind();

    /** A final answer the validator accepted. */
    record Proposed(Proposal proposal) implements RunOutcome {
        public String kind() {
            return "PROPOSED";
        }
    }

    /** The model declined to propose, and the answer passed the checks that apply to a refusal. */
    record NoProposal(String explanation) implements RunOutcome {
        public String kind() {
            return "NO_PROPOSAL";
        }
    }

    /** Two final answers in a row broke the rules. */
    record Rejected(List<Violation> violations) implements RunOutcome {
        public Rejected {
            violations = List.copyOf(violations);
        }

        public String kind() {
            return "REJECTED";
        }
    }

    /** The model call failed. {@code reason} is a {@code ModelResult.Reason}. Never retried. */
    record Failed(String reason, String detail) implements RunOutcome {
        public String kind() {
            return "FAILED";
        }
    }

    /** A bound was reached before the run could end. */
    record Exhausted(String reason) implements RunOutcome {
        public String kind() {
            return "EXHAUSTED";
        }
    }
}

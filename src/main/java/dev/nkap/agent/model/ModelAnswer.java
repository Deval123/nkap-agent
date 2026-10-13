package dev.nkap.agent.model;

import dev.nkap.agent.scenario.Hypothesis;
import dev.nkap.agent.tools.EvidenceRef;
import java.util.List;

/**
 * What the model may say on a turn, and nothing else: ask for a tool, or give a final answer. The model
 * never writes a request to the gateway; a final answer is checked by deterministic code before anything
 * is sent to Nkap.
 */
public sealed interface ModelAnswer {

    /** Asks for one of the tools with one argument. The harness validates the argument before running anything. */
    record CallTool(String tool, String argument) implements ModelAnswer {
    }

    /** The model's conclusion. Evidence is a list of references into the trace, never free text. */
    record Final(Verdict outcome, Hypothesis hypothesis, List<Posting> postings, List<EvidenceRef> evidence,
            String explanation) implements ModelAnswer {

        public Final {
            postings = List.copyOf(postings);
            evidence = List.copyOf(evidence);
        }
    }

    enum Verdict {
        PROPOSE("propose"),
        NO_PROPOSAL("no_proposal");

        private final String code;

        Verdict(String code) {
            this.code = code;
        }

        public String code() {
            return code;
        }
    }
}

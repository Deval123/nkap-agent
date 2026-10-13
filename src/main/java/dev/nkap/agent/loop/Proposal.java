package dev.nkap.agent.loop;

import dev.nkap.agent.model.Posting;
import dev.nkap.agent.scenario.Hypothesis;
import dev.nkap.agent.tools.EvidenceRef;
import java.util.List;

/**
 * An adjustment the validator accepted. The payment reference is the run's, never the model's, and each piece
 * of evidence carries the value the trace holds. The explanation is the model's, for the human who decides.
 */
public record Proposal(String paymentReference, Hypothesis hypothesis, List<Posting> postings,
        List<EvidenceRef> evidence, String explanation) {

    public Proposal {
        postings = List.copyOf(postings);
        evidence = List.copyOf(evidence);
    }
}

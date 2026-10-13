package dev.nkap.agent.approval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.nkap.agent.loop.Proposal;
import java.util.Optional;

/**
 * The one place a checked {@link Proposal} becomes the body of {@code POST /adjustments}: paymentReference,
 * hypothesis, postings (account, amountMinor, currency), evidence as an object of name to value, cites as the
 * names it relies on, and the explanation (Nkap's OpenAPI {@code AdjustmentRequest}).
 *
 * <p>An evidence entry is named by its call and its path, {@code c2/lines/0/feeMinor}, and holds the value the
 * trace has. Nkap refuses a cited entry with a blank value, so a blank one is left out of both lists; with none
 * left there is nothing to cite and no body. The explanation is the model's text: control characters become
 * spaces or go, and it is cut at 500 characters, since Nkap copies it into the ledger entry's description.
 */
final class ProposalRequest {

    static final int EXPLANATION_LIMIT = 500;
    private static final ObjectMapper JSON = new ObjectMapper();

    private ProposalRequest() {
    }

    static Optional<ObjectNode> of(Proposal proposal) {
        ObjectNode evidence = JSON.createObjectNode();
        ArrayNode cites = JSON.createArrayNode();
        proposal.evidence().forEach(e -> {
            if (!e.value().isBlank()) {
                String name = e.callId() + e.path();
                if (!evidence.has(name)) {
                    evidence.put(name, e.value());
                    cites.add(name);
                }
            }
        });
        if (cites.isEmpty()) {
            return Optional.empty();
        }
        ObjectNode body = JSON.createObjectNode();
        body.put("paymentReference", proposal.paymentReference());
        body.put("hypothesis", proposal.hypothesis().code());
        ArrayNode postings = body.putArray("postings");
        proposal.postings().forEach(p -> postings.addObject().put("account", p.account()).put("amountMinor", p.amountMinor())
                .put("currency", p.currency()));
        body.set("evidence", evidence);
        body.set("cites", cites);
        body.put("explanation", explanation(proposal.explanation()));
        return Optional.of(body);
    }

    private static String explanation(String text) {
        StringBuilder out = new StringBuilder();
        text.codePoints().forEach(c -> {
            if (c == '\n' || c == '\r' || c == '\t') {
                out.append(' ');
            } else if (!Character.isISOControl(c)) {
                out.appendCodePoint(c);
            }
        });
        String cleaned = out.toString().strip();
        return cleaned.length() <= EXPLANATION_LIMIT ? cleaned : cleaned.substring(0, EXPLANATION_LIMIT);
    }
}

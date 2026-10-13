package dev.nkap.agent.approval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Turns a refusal from Nkap into a fixed text. The problem type names the refusal; the server's own title and
 * detail are never shown, because they can carry text that is not ours (an account name, a reference). A type
 * from anywhere but Nkap's problem namespace, an unknown type, a type on a status it is not documented for, and a
 * body that is not JSON are all "unexpected", and say the status number and nothing else.
 */
final class ProblemTexts {

    private static final String BASE = "https://nkap.dev/problems/";
    private static final ObjectMapper JSON = new ObjectMapper();

    private record Known(int status, String text) {
    }

    /** The types documented in Nkap's OpenAPI for the adjustment routes, with the status each is documented on. */
    private static final Map<String, Known> KNOWN = new LinkedHashMap<>();

    static {
        KNOWN.put("adjustment-unbalanced", new Known(400, "The postings do not sum to zero."));
        KNOWN.put("adjustment-account-not-allowed", new Known(400,
                "An account is not one of the payment's provider's suspense, fees or float accounts."));
        KNOWN.put("adjustment-over-ceiling", new Known(400, "The adjustment moves more than this deployment allows."));
        KNOWN.put("adjustment-evidence-missing", new Known(400, "The proposal cites evidence it does not carry."));
        KNOWN.put("adjustment-currency-mismatch", new Known(400, "A posting is not in the payment's currency."));
        KNOWN.put("adjustment-self-approval", new Known(403,
                "The key that proposed an adjustment cannot decide it. A different key must approve or reject it."));
        KNOWN.put("adjustment-already-decided", new Known(409, "This adjustment already has a decision, and a decision is final."));
        KNOWN.put("adjustment-not-found", new Known(404, "No such adjustment is visible to this key."));
        KNOWN.put("adjustment-not-enabled", new Known(501, "Adjustments are not enabled on this deployment."));
        KNOWN.put("invalid-request", new Known(400, "Nkap could not accept the request as it was written."));
        KNOWN.put("malformed-reference", new Known(400, "The payment reference is not well formed."));
        KNOWN.put("payment-not-found", new Known(404, "No such payment is visible to this key."));
        KNOWN.put("unauthenticated", new Known(401, "Nkap did not accept the key."));
        KNOWN.put("admin-key-required", new Known(403, "This route needs an admin key."));
    }

    private ProblemTexts() {
    }

    static Refusal of(int status, String body) {
        String type = typeOf(body);
        Known known = type == null ? null : KNOWN.get(type);
        if (known != null && known.status() == status) {
            return new Refusal(type, known.text());
        }
        return unexpected(status);
    }

    static Refusal unexpected(int status) {
        return new Refusal("unexpected", "Nkap answered with an unexpected status " + status + ".");
    }

    private static String typeOf(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            JsonNode node = JSON.readTree(body);
            JsonNode type = node == null ? null : node.get("type");
            if (type == null || !type.isTextual() || !type.asText().startsWith(BASE)) {
                return null;
            }
            String name = type.asText().substring(BASE.length());
            return name.matches("[a-z]+(-[a-z]+)*") ? name : null;
        } catch (java.io.IOException | RuntimeException notJson) {
            return null;
        }
    }
}

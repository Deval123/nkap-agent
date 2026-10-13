package dev.nkap.agent.approval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * What the approver's key may do: approve or reject an adjustment, and nothing else. It is a different class from
 * {@link AdjustmentProposer} and is built with a different key; its allowlist is
 * {@code POST /adjustments/{uuid}/approve} and {@code .../reject}. The id is a parsed {@link UUID}.
 *
 * <p>The approval is bound to a key (its id), not to a person. Nkap refuses the key that proposed an adjustment
 * when it tries to decide it; nothing here knows or checks who holds the approver key.
 */
public final class AdjustmentApprover {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern DECISION = Pattern.compile(
            "/adjustments/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}/(approve|reject)");

    private final NkapHttp http;

    public AdjustmentApprover(String baseUrl, Supplier<String> approverKey) {
        this(baseUrl, approverKey, Duration.ofSeconds(10));
    }

    AdjustmentApprover(String baseUrl, Supplier<String> approverKey, Duration timeout) {
        this.http = new NkapHttp(baseUrl, approverKey, (method, path) -> method.equals("POST") && DECISION.matcher(path).matches(), timeout);
    }

    NkapHttp.Response send(String method, String path, String body) {
        return http.send(method, path, body);
    }

    /** Approves: on success the server has appended the ledger entry {@code adjustment:<id>}, which is returned. */
    public DecisionResult approve(UUID id) {
        return decide(id, "approve");
    }

    /** Rejects: nothing is written to the ledger. */
    public DecisionResult reject(UUID id) {
        return decide(id, "reject");
    }

    private DecisionResult decide(UUID id, String verb) {
        NkapHttp.Response response = send("POST", "/adjustments/" + id + "/" + verb, null);
        if (response.failure() != null) {
            return new DecisionResult.Refused(response.failure().code(), response.failure().text());
        }
        if (response.status() == 200) {
            JsonNode node;
            try {
                node = JSON.readTree(response.body());
            } catch (java.io.IOException | RuntimeException e) {
                node = null;
            }
            if (node != null && node.isObject() && id.toString().equals(node.path("id").asText(null))) {
                String status = node.path("status").asText("");
                String entry = node.path("ledgerEntryId").asText(null);
                if (verb.equals("approve") && status.equals("approved") && ("adjustment:" + id).equals(entry)) {
                    return new DecisionResult.Decided(entry);
                }
                if (verb.equals("reject") && status.equals("rejected") && entry == null) {
                    return new DecisionResult.Decided("");
                }
            }
            Refusal unexpected = ProblemTexts.unexpected(200);
            return new DecisionResult.Refused(unexpected.code(), unexpected.text());
        }
        Refusal refusal = ProblemTexts.of(response.status(), response.body());
        return new DecisionResult.Refused(refusal.code(), refusal.text());
    }

    @Override
    public String toString() {
        return "AdjustmentApprover[" + http + "]";
    }
}

package dev.nkap.agent.approval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.nkap.agent.loop.Proposal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * What the agent's key may do to adjustments: propose one, and read them. It has no way to decide one: this class
 * has no approve and no reject, and its allowlist is {@code POST /adjustments}, {@code GET /adjustments} and
 * {@code GET /adjustments/{uuid}}. The agent's key is an admin key (it also reads statement imports); the
 * allowlist is what keeps this client from using that elsewhere.
 *
 * <p>The key is asked for from the supplier on each request and is held in no field. A refusal from Nkap comes
 * back as a code and a fixed text, never as the server's own words.
 */
public final class AdjustmentProposer {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String UUID_RE = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";
    private static final Pattern ONE = Pattern.compile("/adjustments/" + UUID_RE);
    private static final Set<String> STATUSES = Set.of("pending", "approved", "rejected");

    private final NkapHttp http;

    public AdjustmentProposer(String baseUrl, Supplier<String> agentKey) {
        this(baseUrl, agentKey, Duration.ofSeconds(10));
    }

    AdjustmentProposer(String baseUrl, Supplier<String> agentKey, Duration timeout) {
        this.http = new NkapHttp(baseUrl, agentKey, AdjustmentProposer::allowed, timeout);
    }

    private static boolean allowed(String method, String path) {
        return (method.equals("POST") && path.equals("/adjustments"))
                || (method.equals("GET") && (path.equals("/adjustments") || ONE.matcher(path).matches()));
    }

    /** Package-private so the allowlist can be tested directly; the public methods are the only callers. */
    NkapHttp.Response send(String method, String path, String body) {
        return http.send(method, path, body);
    }

    /** Proposes the adjustment. Nothing reaches the ledger: a different key has to approve it. */
    public ProposalResult propose(Proposal proposal) {
        Optional<com.fasterxml.jackson.databind.node.ObjectNode> body = ProposalRequest.of(proposal);
        if (body.isEmpty()) {
            return new ProposalResult.Refused("evidence-missing",
                    "Every piece of evidence the proposal cites is blank, so there is nothing to cite.");
        }
        NkapHttp.Response response = send("POST", "/adjustments", body.get().toString());
        if (response.failure() != null) {
            return new ProposalResult.Refused(response.failure().code(), response.failure().text());
        }
        if (response.status() == 201) {
            UUID id = idOf(parse(response.body()));
            if (id != null) {
                return new ProposalResult.Created(id);
            }
            return refused(ProblemTexts.unexpected(response.status()));
        }
        Refusal refusal = ProblemTexts.of(response.status(), response.body());
        return new ProposalResult.Refused(refusal.code(), refusal.text());
    }

    private static ProposalResult.Refused refused(Refusal r) {
        return new ProposalResult.Refused(r.code(), r.text());
    }

    public ReadResult get(UUID id) {
        NkapHttp.Response response = send("GET", "/adjustments/" + id, null);
        if (response.failure() != null) {
            return new ReadResult.Refused(response.failure().code(), response.failure().text());
        }
        if (response.status() == 200) {
            AdjustmentView view = view(parse(response.body()));
            if (view != null) {
                return new ReadResult.Found(view);
            }
            return new ReadResult.Refused("unexpected", ProblemTexts.unexpected(200).text());
        }
        Refusal refusal = ProblemTexts.of(response.status(), response.body());
        return new ReadResult.Refused(refusal.code(), refusal.text());
    }

    public ReadResult list() {
        NkapHttp.Response response = send("GET", "/adjustments", null);
        if (response.failure() != null) {
            return new ReadResult.Refused(response.failure().code(), response.failure().text());
        }
        if (response.status() == 200) {
            JsonNode array = parse(response.body());
            List<AdjustmentView> views = new ArrayList<>();
            if (array != null && array.isArray()) {
                for (JsonNode node : array) {
                    AdjustmentView view = view(node);
                    if (view == null) {
                        return new ReadResult.Refused("unexpected", ProblemTexts.unexpected(200).text());
                    }
                    views.add(view);
                }
                return new ReadResult.Listed(views);
            }
            return new ReadResult.Refused("unexpected", ProblemTexts.unexpected(200).text());
        }
        Refusal refusal = ProblemTexts.of(response.status(), response.body());
        return new ReadResult.Refused(refusal.code(), refusal.text());
    }

    private static JsonNode parse(String body) {
        try {
            return JSON.readTree(body);
        } catch (java.io.IOException | RuntimeException e) {
            return null;
        }
    }

    private static UUID idOf(JsonNode node) {
        if (node == null || !node.isObject() || !node.path("id").isTextual() || !node.get("id").asText().matches(UUID_RE)) {
            return null;
        }
        return UUID.fromString(node.get("id").asText());
    }

    private static AdjustmentView view(JsonNode node) {
        UUID id = idOf(node);
        String status = node == null ? null : node.path("status").asText(null);
        String hypothesis = node == null ? null : node.path("hypothesis").asText(null);
        if (id == null || status == null || !STATUSES.contains(status) || hypothesis == null || !hypothesis.matches("[a-z_]{1,40}")) {
            return null;
        }
        String entry = node.path("ledgerEntryId").asText(null);
        if (entry != null && !entry.equals("adjustment:" + id)) {
            return null;
        }
        return new AdjustmentView(id, status, hypothesis, entry);
    }

    @Override
    public String toString() {
        return "AdjustmentProposer[" + http + "]";
    }
}

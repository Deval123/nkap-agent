package dev.nkap.agent.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.nkap.agent.approval.AdjustmentApprover;
import dev.nkap.agent.approval.AdjustmentProposer;
import dev.nkap.agent.approval.DecisionResult;
import dev.nkap.agent.approval.ProposalResult;
import dev.nkap.agent.approval.ReadResult;
import dev.nkap.agent.approval.ScriptedAnswers;
import dev.nkap.agent.live.LiveBudget;
import dev.nkap.agent.loop.AgentLoop;
import dev.nkap.agent.loop.Expectation;
import dev.nkap.agent.loop.Judgement;
import dev.nkap.agent.loop.RunOutcome;
import dev.nkap.agent.loop.RunRecord;
import dev.nkap.agent.model.AgentModel;
import dev.nkap.agent.scenario.Catalogue;
import dev.nkap.agent.scenario.ScenarioCase;
import dev.nkap.agent.tools.AgentTools;
import dev.nkap.agent.tools.DirectoryStatementStore;
import dev.nkap.agent.tools.DocsIndex;
import dev.nkap.agent.tools.GatewayClient;
import dev.nkap.agent.trace.TraceExport;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * Runs the agent for a visitor, and decides on what it proposed. Three modes, said on every step: scripted answers
 * (the real loop, validator, tools and Nkap, with no model), live (the real model, only under the live budget), and,
 * elsewhere, recordings.
 *
 * <p>A run that ends in a proposal is written to Nkap by the proposer, with the agent's key, and kept in memory under
 * a run id. The visitor never gives an adjustment id: the run id maps to the one this service created, and one
 * decision is allowed per run. {@code approve} uses the approver's key; {@code self-approve} uses the agent's own key
 * and is expected to be refused by Nkap, which is the point of showing it.
 */
final class RunService {

    /** What a run id maps to. */
    private static final class Pending {
        final UUID adjustmentId;
        final String paymentReference;
        /** Closed only by a decision Nkap took. Changed only with the decision lock held. */
        boolean decided;
        /** Attempts that reached Nkap. Changed only with the decision lock held. */
        int attempts;

        Pending(UUID adjustmentId, String paymentReference) {
            this.adjustmentId = adjustmentId;
            this.paymentReference = paymentReference;
        }
    }

    /** Whether an approval may be sent for a payment. */
    private enum Approval { AVAILABLE, ALREADY_APPROVED, UNAVAILABLE }

    /** A request that was refused, as a status, a code and a fixed text. */
    record Refusal(int status, String code, String text, long retryAfterSeconds) {
    }

    /** The result of asking for a run or a decision: a body, or a refusal. */
    record Result(JsonNode body, Refusal refusal) {
        static Result ok(JsonNode body) {
            return new Result(body, null);
        }

        static Result refused(int status, String code, String text) {
            return new Result(null, new Refusal(status, code, text, 0));
        }
    }

    private static final System.Logger LOG = System.getLogger("dev.nkap.agent.web");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_AT_ONCE = 2;
    /** Attempts on one run that Nkap refused, after which the run takes no more. */
    static final int MAX_ATTEMPTS = 3;
    static final String ALREADY_APPROVED_TEXT = "An earlier visitor already approved an adjustment for this payment in this demo ledger. "
            + "Nkap would accept a second one; this service does not send it.";
    static final String UNAVAILABLE_TEXT = "The service could not check whether this payment already has an approved adjustment, "
            + "so it does not send the approval.";
    static final String TOO_MANY_TEXT = "This run has used its three attempts.";

    private final WebConfig config;
    private final Supplier<AgentModel> liveModel;
    private final LiveBudget liveBudget;
    private final LiveBudget scriptedBudget;
    private final Clock clock;
    private final RunTable<Pending> runs;
    private final Semaphore running = new Semaphore(MAX_AT_ONCE);
    /**
     * Held around every attempt to decide, and around the check of the payment that goes with it: the check and the call
     * to Nkap are one step, so two visitors cannot both pass the check for the same payment, and two requests on one run
     * cannot both reach Nkap as a decision. It is held for one call to Nkap at most (10 seconds).
     */
    private final ReentrantLock decisions = new ReentrantLock();
    private final ApprovedPayments approved;
    private final GatewayClient gateway;
    private final AdjustmentProposer proposer;

    RunService(WebConfig config, Supplier<AgentModel> liveModel, LiveBudget liveBudget, LiveBudget scriptedBudget, Clock clock) {
        this.config = config;
        this.liveModel = liveModel;
        this.liveBudget = liveBudget;
        this.scriptedBudget = scriptedBudget;
        this.clock = clock;
        this.runs = new RunTable<>(50, Duration.ofMinutes(30), clock);
        this.gateway = new GatewayClient(config.gatewayUrl(), config.agentKey());
        this.proposer = new AdjustmentProposer(config.gatewayUrl(), config.agentKey(), config.internalHost());
        this.approved = new ApprovedPayments(config.stateDir().resolve("approved-payments.json"));
    }

    boolean liveEnabled() {
        return config.liveEnabled() && liveModel != null;
    }

    java.util.Optional<Integer> liveRunsLeft() {
        return liveEnabled() ? liveBudget.remainingToday() : java.util.Optional.empty();
    }

    Result start(String caseId, String mode, String visitor) {
        boolean live = mode.equals("live");
        if (live && !liveEnabled()) {
            return Result.refused(503, "live-off", "Live runs are off on this deployment. Scripted runs and recordings are available.");
        }
        if (!running.tryAcquire()) {
            return Result.refused(429, "busy", "Two runs are already going. Try again in a moment.");
        }
        try {
            LiveBudget.Decision decision = (live ? liveBudget : scriptedBudget).tryAcquire(visitor);
            if (decision instanceof LiveBudget.Decision.Refused refused) {
                int status = refused.reason().equals("unavailable") ? 503 : 429;
                return new Result(null, new Refusal(status, refused.reason(), refused.text(), Math.max(1, refused.retryAfter().toSeconds())));
            }
            return Result.ok(execute(caseId, live ? "live" : "scripted"));
        } catch (IOException e) {
            return Result.refused(503, "unavailable", "The run could not be set up.");
        } finally {
            running.release();
        }
    }

    private ObjectNode execute(String caseId, String mode) throws IOException {
        ScenarioCase scenario = Catalogue.all().stream().filter(c -> c.id().equals(caseId)).findFirst().orElseThrow();
        String reference = null;
        String importId = "00000000-0000-0000-0000-000000000000";
        for (JsonNode entry : JSON.readTree(config.manifest().toFile()).path("cases")) {
            if (entry.path("id").asText().equals(caseId)) {
                reference = entry.path("paymentReference").asText(null);
                if (entry.path("statementImportId").isTextual()) {
                    importId = entry.get("statementImportId").asText();
                }
            }
        }
        if (reference == null) {
            throw new IOException("the manifest has no such case");
        }
        AgentModel model = mode.equals("live") ? liveModel.get() : new ScriptedAnswers(ScriptedAnswers.forCase(scenario, reference, importId, null));
        Path statements = config.statementsDir();
        RunRecord record = new AgentLoop(model, AgentLoop.Limits.defaults()).run(
                trace -> new AgentTools(gateway, DocsIndex.bundled(), new DirectoryStatementStore(statements), trace), reference, importId);
        Judgement judgement = Expectation.judge(scenario, record.outcome());
        String label = mode.equals("live") ? config.nebiusModel() : "scripted answers";
        JsonNode trace = TraceExport.export(JSON.readTree(record.toJson()), scenario, new TraceExport.Provenance(
                LocalDate.now(clock.withZone(ZoneOffset.UTC)).toString(), label, 1, 1, judgement.matches(), judgement.text(),
                TraceExport.DEFAULT_INPUT_PRICE, TraceExport.DEFAULT_OUTPUT_PRICE));

        ObjectNode body = JSON.createObjectNode();
        body.put("mode", mode);
        body.set("steps", StepViews.of(trace.get("steps"), mode, false));
        ObjectNode outcome = ((ObjectNode) trace.get("steps").get(trace.get("steps").size() - 1)).deepCopy();
        outcome.remove("type");
        body.set("outcome", outcome);
        body.put("matched", judgement.matches());
        body.put("judgement", judgement.text());
        ObjectNode totals = body.putObject("totals");
        totals.put("modelCalls", record.modelCalls());
        totals.put("promptTokens", record.promptTokens());
        totals.put("completionTokens", record.completionTokens());
        totals.put("elapsedMillis", record.elapsedMillis());

        ObjectNode proposal = body.putObject("proposal");
        if (record.outcome() instanceof RunOutcome.Proposed proposed) {
            ProposalResult result = proposer.propose(proposed.proposal());
            if (result instanceof ProposalResult.Created created) {
                proposal.put("status", "created");
                body.put("runId", runs.put(new Pending(created.id(), reference)));
                ObjectNode approval = body.putObject("approval");
                Approval state = approvalFor(reference, true);
                approval.put("available", state == Approval.AVAILABLE);
                if (state == Approval.ALREADY_APPROVED) {
                    approval.put("code", "payment-already-approved").put("text", ALREADY_APPROVED_TEXT);
                } else if (state == Approval.UNAVAILABLE) {
                    approval.put("code", "unavailable").put("text", UNAVAILABLE_TEXT);
                }
            } else {
                ProposalResult.Refused refused = (ProposalResult.Refused) result;
                proposal.put("status", "refused").put("code", refused.code()).put("text", refused.text());
            }
        } else {
            proposal.put("status", "none");
        }
        return body;
    }

    /**
     * Whether an approval may be sent for this payment: not if the service has seen Nkap approve an adjustment for it
     * and Nkap still says that adjustment is approved. An entry Nkap no longer knows, or knows as not approved, is
     * stale and is forgotten. If the record or Nkap cannot be read the answer is "unavailable", which refuses.
     */
    private Approval approvalFor(String reference, boolean takeLock) {
        if (takeLock) {
            decisions.lock();
        }
        try {
            ApprovedPayments.Lookup lookup = approved.lookup(reference);
            if (!lookup.available()) {
                return Approval.UNAVAILABLE;
            }
            if (lookup.adjustment().isEmpty()) {
                return Approval.AVAILABLE;
            }
            ReadResult read = proposer.get(lookup.adjustment().get());
            if (read instanceof ReadResult.Found found) {
                if (found.view().status().equals("approved")) {
                    return Approval.ALREADY_APPROVED;
                }
                approved.forget(reference);
                return Approval.AVAILABLE;
            }
            if (read instanceof ReadResult.Refused refused && refused.code().equals("adjustment-not-found")) {
                approved.forget(reference);
                return Approval.AVAILABLE;
            }
            return Approval.UNAVAILABLE;
        } finally {
            if (takeLock) {
                decisions.unlock();
            }
        }
    }

    /**
     * An attempt to decide a run. Only a decision Nkap took closes the run; a refusal leaves it open, counted, up to
     * {@link #MAX_ATTEMPTS}. An approval is not sent for a payment that already has an approved adjustment.
     * {@code self} tries it with the agent's own key, which Nkap is expected to refuse.
     */
    Result decide(String runId, boolean self) {
        java.util.Optional<Pending> found = runs.get(runId);
        if (found.isEmpty()) {
            return Result.refused(404, "not-found", "Not found.");
        }
        Pending pending = found.get();
        decisions.lock();
        try {
            if (pending.decided) {
                return Result.refused(409, "already-decided", "This run has had its decision.");
            }
            if (pending.attempts >= MAX_ATTEMPTS) {
                return Result.refused(429, "too-many-attempts", TOO_MANY_TEXT);
            }
            if (!self) {
                Approval state = approvalFor(pending.paymentReference, false);
                if (state == Approval.ALREADY_APPROVED) {
                    return Result.refused(409, "payment-already-approved", ALREADY_APPROVED_TEXT);
                }
                if (state == Approval.UNAVAILABLE) {
                    return Result.refused(503, "unavailable", UNAVAILABLE_TEXT);
                }
            }
            pending.attempts++;
            AdjustmentApprover approver = new AdjustmentApprover(config.gatewayUrl(), self ? config.agentKey() : config.approverKey(), config.internalHost());
            DecisionResult result = approver.approve(pending.adjustmentId);
            ObjectNode body = JSON.createObjectNode();
            body.put("decision", self ? "self-approve" : "approve");
            if (result instanceof DecisionResult.Decided decided) {
                pending.decided = true;
                if (!approved.record(pending.paymentReference, pending.adjustmentId)) {
                    LOG.log(System.Logger.Level.WARNING, "an approval was taken but could not be recorded");
                }
                body.put("result", "decided").put("ledgerEntryId", decided.ledgerEntryId());
            } else {
                DecisionResult.Refused refused = (DecisionResult.Refused) result;
                body.put("result", "refused").put("code", refused.code()).put("text", refused.text());
                body.put("attemptsLeft", MAX_ATTEMPTS - pending.attempts);
            }
            return Result.ok(body);
        } finally {
            decisions.unlock();
        }
    }
}

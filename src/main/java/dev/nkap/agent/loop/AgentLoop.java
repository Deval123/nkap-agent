package dev.nkap.agent.loop;

import dev.nkap.agent.model.AgentModel;
import dev.nkap.agent.model.AnswerSchema;
import dev.nkap.agent.model.Message;
import dev.nkap.agent.model.ModelAnswer;
import dev.nkap.agent.model.ModelResult;
import dev.nkap.agent.model.Usage;
import dev.nkap.agent.tools.AgentTools;
import dev.nkap.agent.tools.ToolResult;
import dev.nkap.agent.tools.Trace;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.LongSupplier;

/**
 * Runs the model against the three tools until it gives a final answer the validator accepts, or a bound is
 * reached. It sends nothing to Nkap. A run is bounded in model calls, completion tokens and time; a
 * model failure ends it, with no retry; a final answer that breaks a rule gets one repair message, and a
 * second one ends the run as rejected.
 *
 * <p>Tool results reach the model as {@link ToolResult#toBlock() blocks of untrusted data}. The only text
 * the harness adds is fixed: the task, the repair message built from fixed violation texts, and the note
 * that a repeated call was not run.
 */
public final class AgentLoop {

    /** The bounds of a run. {@code clock} is a nanosecond clock, a parameter so that a test can move time. */
    public record Limits(int maxCalls, long maxCompletionTokens, long maxMillis, LongSupplier clock) {
        public static Limits defaults() {
            return new Limits(6, 24_000, 120_000, System::nanoTime);
        }
    }

    private final AgentModel model;
    private final Limits limits;

    public AgentLoop(AgentModel model, Limits limits) {
        this.model = model;
        this.limits = limits;
    }

    /**
     * Runs one investigation. Each run has its own trace, and the tools are built over it, so that a call id
     * means one thing in one run.
     */
    public RunRecord run(Function<Trace, AgentTools> toolsOver, String reference, String importId) {
        Trace trace = new Trace();
        AgentTools tools = toolsOver.apply(trace);
        long begun = limits.clock().getAsLong();
        List<Message> messages = new ArrayList<>(List.of(
                new Message(Message.Role.SYSTEM, Prompts.system()),
                new Message(Message.Role.USER, Prompts.task(reference, importId))));
        List<RunRecord.ModelCall> calls = new ArrayList<>();
        List<Message> lastSent = List.copyOf(messages);
        Map<String, String> asked = new HashMap<>();
        long completionTokens = 0;
        boolean repaired = false;
        RunOutcome outcome;

        while (true) {
            String exhausted = exhausted(calls.size(), completionTokens, begun);
            if (exhausted != null) {
                outcome = new RunOutcome.Exhausted(exhausted);
                break;
            }
            lastSent = List.copyOf(messages);
            long started = limits.clock().getAsLong();
            ModelResult result = model.next(lastSent);
            long millis = (limits.clock().getAsLong() - started) / 1_000_000;

            if (result instanceof ModelResult.Failed failed) {
                Usage usage = failed.usage();
                calls.add(new RunRecord.ModelCall(calls.size() + 1, lastSent.size(), null, failed.reason().name(),
                        failed.detail(), prompt(usage), completion(usage), millis));
                completionTokens += usage == null ? 0 : usage.completionTokens();
                outcome = new RunOutcome.Failed(failed.reason().name(), failed.detail());
                break;
            }
            ModelResult.Answered answered = (ModelResult.Answered) result;
            String canonical = AnswerSchema.toJson(answered.answer());
            calls.add(new RunRecord.ModelCall(calls.size() + 1, lastSent.size(), canonical, null, null,
                    answered.usage().promptTokens(), answered.usage().completionTokens(), millis));
            completionTokens += answered.usage().completionTokens();

            if (answered.answer() instanceof ModelAnswer.CallTool call) {
                messages.add(new Message(Message.Role.ASSISTANT, canonical));
                messages.add(runTool(tools, call, asked));
                continue;
            }
            ModelAnswer.Final fin = (ModelAnswer.Final) answered.answer();
            ProposalValidator.Verdict verdict = ProposalValidator.validate(fin, trace, reference);
            if (verdict instanceof ProposalValidator.Verdict.Accepted accepted) {
                outcome = new RunOutcome.Proposed(accepted.proposal());
                break;
            }
            if (verdict instanceof ProposalValidator.Verdict.NoProposal none) {
                outcome = new RunOutcome.NoProposal(none.explanation(), none.droppedCitations());
                break;
            }
            List<Violation> violations = ((ProposalValidator.Verdict.Rejected) verdict).violations();
            if (repaired) {
                outcome = new RunOutcome.Rejected(violations);
                break;
            }
            repaired = true;
            messages.add(new Message(Message.Role.ASSISTANT, canonical));
            messages.add(new Message(Message.Role.USER, repairMessage(violations)));
        }
        long elapsed = (limits.clock().getAsLong() - begun) / 1_000_000;
        return new RunRecord(reference, importId, lastSent, calls, trace.results(), outcome, elapsed);
    }

    private String exhausted(int calls, long completionTokens, long begun) {
        if (calls >= limits.maxCalls()) {
            return limits.maxCalls() + " model calls were made.";
        }
        if (completionTokens >= limits.maxCompletionTokens()) {
            return limits.maxCompletionTokens() + " completion tokens were used.";
        }
        if ((limits.clock().getAsLong() - begun) / 1_000_000 >= limits.maxMillis()) {
            return limits.maxMillis() / 1000 + " seconds passed.";
        }
        return null;
    }

    private static Message runTool(AgentTools tools, ModelAnswer.CallTool call, Map<String, String> asked) {
        String key = call.tool() + "\u0000" + call.argument();
        String earlier = asked.get(key);
        if (earlier != null) {
            return new Message(Message.Role.USER, "You asked for this before: the result is call " + earlier
                    + ". Use that callId; no tool was run.");
        }
        ToolResult result = switch (call.tool()) {
            case "get_payment" -> tools.getPayment(call.argument());
            case "get_statement_findings" -> tools.getStatementFindings(call.argument());
            default -> tools.searchDocs(call.argument());
        };
        asked.put(key, result.callId());
        return new Message(Message.Role.TOOL_RESULT, result.toBlock());
    }

    private static String repairMessage(List<Violation> violations) {
        StringBuilder text = new StringBuilder("Your final answer was refused:\n");
        violations.forEach(v -> text.append("- ").append(v.text()).append('\n'));
        text.append("Give a corrected final answer, or answer no_proposal if the evidence does not support one.");
        return text.toString();
    }

    private static Long prompt(Usage usage) {
        return usage == null ? null : usage.promptTokens();
    }

    private static Long completion(Usage usage) {
        return usage == null ? null : usage.completionTokens();
    }
}

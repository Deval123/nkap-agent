package dev.nkap.agent.loop;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.nkap.agent.model.Message;
import dev.nkap.agent.tools.ToolResult;
import java.util.List;

/**
 * Everything a run did, complete enough to replay it later without the model: the messages sent, each model
 * call with its answer or failure, tokens and time, each tool call with its result (masked and truncated
 * already, as the model saw it), and the outcome. It holds no key: the loop never has one.
 *
 * @param messages the conversation as it stood when the last model call was made
 */
public record RunRecord(String reference, String importId, List<Message> messages, List<ModelCall> calls,
        List<ToolResult> toolCalls, RunOutcome outcome, long elapsedMillis) {

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * One call to the model. Exactly one of {@code answer} (the canonical JSON of what the model said) and
     * {@code failureReason} is set. Token counts are null when the provider reported none that can be trusted.
     */
    public record ModelCall(int index, int messagesSent, String answer, String failureReason, String failureDetail,
            Long promptTokens, Long completionTokens, long millis) {
    }

    public RunRecord {
        messages = List.copyOf(messages);
        calls = List.copyOf(calls);
        toolCalls = List.copyOf(toolCalls);
    }

    public int modelCalls() {
        return calls.size();
    }

    public long completionTokens() {
        return calls.stream().mapToLong(c -> c.completionTokens() == null ? 0 : c.completionTokens()).sum();
    }

    public long promptTokens() {
        return calls.stream().mapToLong(c -> c.promptTokens() == null ? 0 : c.promptTokens()).sum();
    }

    /** Stable: the same record gives the same text. */
    public String toJson() {
        ObjectNode root = JSON.createObjectNode();
        root.put("reference", reference);
        root.put("importId", importId);
        root.set("outcome", outcomeJson());
        root.put("modelCalls", modelCalls());
        root.put("promptTokens", promptTokens());
        root.put("completionTokens", completionTokens());
        root.put("elapsedMillis", elapsedMillis);
        ArrayNode sent = root.putArray("messages");
        for (Message message : messages) {
            sent.addObject().put("role", message.role().name()).put("content", message.content());
        }
        ArrayNode callNodes = root.putArray("calls");
        for (ModelCall call : calls) {
            ObjectNode node = callNodes.addObject();
            node.put("index", call.index());
            node.put("messagesSent", call.messagesSent());
            if (call.answer() != null) {
                node.put("answer", call.answer());
            } else {
                node.putObject("failure").put("reason", call.failureReason()).put("detail", call.failureDetail());
            }
            node.put("promptTokens", call.promptTokens());
            node.put("completionTokens", call.completionTokens());
            node.put("millis", call.millis());
        }
        ArrayNode tools = root.putArray("toolCalls");
        for (ToolResult result : toolCalls) {
            try {
                tools.add(JSON.readTree(result.toJson()));
            } catch (java.io.IOException e) {
                throw new IllegalStateException("a tool result is not JSON");
            }
        }
        return root.toString();
    }

    private ObjectNode outcomeJson() {
        ObjectNode node = JSON.createObjectNode();
        node.put("kind", outcome.kind());
        switch (outcome) {
            case RunOutcome.Proposed p -> {
                Proposal proposal = p.proposal();
                node.put("paymentReference", proposal.paymentReference());
                node.put("hypothesis", proposal.hypothesis().code());
                ArrayNode postings = node.putArray("postings");
                proposal.postings().forEach(x -> postings.addObject().put("account", x.account())
                        .put("amountMinor", x.amountMinor()).put("currency", x.currency()));
                ArrayNode evidence = node.putArray("evidence");
                proposal.evidence().forEach(e -> evidence.addObject().put("callId", e.callId()).put("path", e.path())
                        .put("value", e.value()));
                node.put("explanation", proposal.explanation());
            }
            case RunOutcome.NoProposal n -> node.put("explanation", n.explanation());
            case RunOutcome.Rejected r -> {
                ArrayNode violations = node.putArray("violations");
                r.violations().forEach(v -> violations.addObject().put("code", v.code()).put("text", v.text()));
            }
            case RunOutcome.Failed f -> node.put("reason", f.reason()).put("detail", f.detail());
            case RunOutcome.Exhausted e -> node.put("reason", e.reason());
        }
        return node;
    }
}

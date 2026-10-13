package dev.nkap.agent.trace;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.nkap.agent.loop.Proposal;
import dev.nkap.agent.loop.RunOutcome;
import dev.nkap.agent.loop.RunRecord;
import dev.nkap.agent.model.AnswerSchema;
import dev.nkap.agent.model.Message;
import dev.nkap.agent.model.ModelAnswer;
import dev.nkap.agent.model.Posting;
import dev.nkap.agent.scenario.Hypothesis;
import dev.nkap.agent.tools.EvidencePointer;
import dev.nkap.agent.tools.EvidenceRef;
import dev.nkap.agent.tools.ToolResult;
import java.util.List;
import java.util.Map;

/** A run as the loop leaves it: two looks, a final answer refused once, a repaired one accepted. Built by hand. */
final class TraceFixtures {

    static final ObjectMapper JSON = new ObjectMapper();
    static final String REF = "0985e8df-93aa-4949-b7aa-185e19ea41d0";
    static final String IMPORT = "16de24bf-b9fe-425d-a28e-b0d4d57912b4";
    static final String REPAIR = "Your final answer was refused:\n- An account is not the suspense, fees or provider float account.\n"
            + "Allowed accounts for this payment: suspense:mtn-cm:XAF, fees:mtn-cm:XAF, provider:mtn-cm:float:XAF.\n"
            + "Give a corrected final answer, or answer no_proposal if the evidence does not support one.";

    private TraceFixtures() {
    }

    static RunRecord record() {
        ToolResult payment = new ToolResult("c1", "get_payment", Map.of("reference", REF), 200,
                node("{\"provider\":\"mtn-cm\",\"currency\":\"XAF\",\"state\":\"UNKNOWN\"}"));
        ToolResult lines = new ToolResult("c2", "get_statement_lines", Map.of("importId", IMPORT), 200,
                node("{\"lines\":[{\"operatorTransactionId\":\"FT-UF-0001\",\"amountMinor\":5000,\"feeMinor\":75}]}"));
        ModelAnswer look1 = new ModelAnswer.CallTool("get_payment", REF);
        ModelAnswer look2 = new ModelAnswer.CallTool("get_statement_lines", IMPORT);
        List<EvidencePointer> cites = List.of(new EvidencePointer("c2", "/lines/0/feeMinor"));
        ModelAnswer typo = new ModelAnswer.Final(ModelAnswer.Verdict.PROPOSE, Hypothesis.UNRECORDED_FEE,
                List.of(new Posting("suspsense:mtn-cm:XAF", 75, "XAF"), new Posting("provider:mtn-cm:float:XAF", -75, "XAF")), cites, "A fee.");
        List<Posting> entry = List.of(new Posting("fees:mtn-cm:XAF", 75, "XAF"), new Posting("provider:mtn-cm:float:XAF", -75, "XAF"));
        ModelAnswer good = new ModelAnswer.Final(ModelAnswer.Verdict.PROPOSE, Hypothesis.UNRECORDED_FEE, entry, cites, "A fee.");

        List<Message> messages = List.of(
                new Message(Message.Role.SYSTEM, "SYSTEM PROMPT"),
                new Message(Message.Role.USER, "Investigate payment " + REF + ". The statement import " + IMPORT + " was loaded today."),
                new Message(Message.Role.ASSISTANT, AnswerSchema.toJson(look1)),
                new Message(Message.Role.TOOL_RESULT, payment.toBlock()),
                new Message(Message.Role.ASSISTANT, AnswerSchema.toJson(look2)),
                new Message(Message.Role.TOOL_RESULT, lines.toBlock()),
                new Message(Message.Role.ASSISTANT, AnswerSchema.toJson(typo)),
                new Message(Message.Role.USER, REPAIR));
        List<RunRecord.ModelCall> calls = List.of(
                new RunRecord.ModelCall(1, 2, AnswerSchema.toJson(look1), null, null, 1000L, 200L, 3000),
                new RunRecord.ModelCall(2, 4, AnswerSchema.toJson(look2), null, null, 1500L, 300L, 5000),
                new RunRecord.ModelCall(3, 6, AnswerSchema.toJson(typo), null, null, 2000L, 900L, 9000),
                new RunRecord.ModelCall(4, 8, AnswerSchema.toJson(good), null, null, 2600L, 400L, 1500));
        Proposal proposal = new Proposal(REF, Hypothesis.UNRECORDED_FEE, entry, List.of(new EvidenceRef("c2", "/lines/0/feeMinor", "75")), "A fee.");
        return new RunRecord(REF, IMPORT, messages, calls, List.of(payment, lines), new RunOutcome.Proposed(proposal), 18500);
    }

    static JsonNode json(RunRecord record) {
        return node(record.toJson());
    }

    static JsonNode node(String text) {
        try {
            return JSON.readTree(text);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}

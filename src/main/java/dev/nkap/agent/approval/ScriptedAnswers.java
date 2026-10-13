package dev.nkap.agent.approval;

import dev.nkap.agent.model.AgentModel;
import dev.nkap.agent.model.AnswerSchema;
import dev.nkap.agent.model.Message;
import dev.nkap.agent.model.ModelAnswer;
import dev.nkap.agent.model.ModelResult;
import dev.nkap.agent.model.SchemaViolation;
import dev.nkap.agent.model.Usage;
import dev.nkap.agent.scenario.Catalogue;
import dev.nkap.agent.scenario.ExpectedProposal;
import dev.nkap.agent.scenario.Hypothesis;
import dev.nkap.agent.scenario.Outcome;
import dev.nkap.agent.scenario.ScenarioCase;
import dev.nkap.agent.model.Posting;
import dev.nkap.agent.tools.EvidencePointer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * A model that is a file: one answer per line, given in order, with no model and no network. It stands in for the
 * model in the end-to-end check, so that everything after the model (the validator, the clients, Nkap) runs for
 * real. {@link #forCase} writes the file for a catalogue case from the catalogue's own expected entry.
 */
public final class ScriptedAnswers implements AgentModel {

    private final Deque<String> lines;

    public ScriptedAnswers(List<String> lines) {
        this.lines = new ArrayDeque<>(lines);
    }

    @Override
    public ModelResult next(List<Message> messages) {
        String line = lines.poll();
        if (line == null) {
            return new ModelResult.Failed(ModelResult.Reason.EMPTY, "the scripted answers have run out", null);
        }
        try {
            ModelAnswer answer = AnswerSchema.parse(line);
            return new ModelResult.Answered(answer, new Usage(0, 0));
        } catch (SchemaViolation e) {
            return new ModelResult.Failed(ModelResult.Reason.SCHEMA, e.getMessage(), null);
        }
    }

    /**
     * Look at the payment (c1), look at the statement lines (c2), then the catalogue's expected answer for the case:
     * its entry with the evidence the validator asks for, or no proposal. {@code amount}, if given, replaces the
     * amount of the entry, keeping its signs.
     */
    public static List<String> forCase(ScenarioCase scenario, String reference, String importId, Long amount) {
        List<String> lines = new ArrayList<>();
        lines.add(AnswerSchema.toJson(new ModelAnswer.CallTool("get_payment", reference)));
        lines.add(AnswerSchema.toJson(new ModelAnswer.CallTool("get_statement_lines", importId)));
        if (scenario.outcome() == Outcome.NO_PROPOSAL) {
            lines.add(AnswerSchema.toJson(new ModelAnswer.Final(ModelAnswer.Verdict.NO_PROPOSAL, null, List.of(), List.of(),
                    "The evidence does not show what happened.")));
            return lines;
        }
        ExpectedProposal expected = scenario.proposal().orElseThrow();
        List<Posting> postings = expected.postings().stream()
                .map(p -> new Posting(p.account(), amount == null ? p.amountMinor() : Long.signum(p.amountMinor()) * amount,
                        Catalogue.CURRENCY)).toList();
        String amountPath = expected.hypothesis() == Hypothesis.UNRECORDED_FEE ? "/lines/0/feeMinor" : "/lines/0/amountMinor";
        lines.add(AnswerSchema.toJson(new ModelAnswer.Final(ModelAnswer.Verdict.PROPOSE, expected.hypothesis(), postings,
                List.of(new EvidencePointer("c2", amountPath), new EvidencePointer("c1", "/provider")),
                "The statement line and the payment show it.")));
        return lines;
    }
}

package dev.nkap.agent.loop;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.nkap.agent.model.AnswerSchema;
import dev.nkap.agent.model.ModelAnswer;
import dev.nkap.agent.model.Posting;
import dev.nkap.agent.scenario.Catalogue;
import dev.nkap.agent.scenario.ExpectedProposal;
import dev.nkap.agent.scenario.Hypothesis;
import dev.nkap.agent.scenario.Outcome;
import dev.nkap.agent.scenario.ScenarioCase;
import dev.nkap.agent.scenario.StatementCsv;
import dev.nkap.agent.scenario.StatementLine;
import dev.nkap.agent.tools.EvidencePointer;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * What {@code scripts/verify-run.sh} needs to run the runner with no model and no stack: the answers a gateway
 * would give for each catalogue case, a manifest in the shape {@code seed.sh} writes, and the final answer a
 * scripted model gives for each case. Everything is built from the catalogue. The scripted answers are the
 * expected ones, and a second script has the entry of the measured run for the unrecorded-fee case.
 *
 * <p>A case with no statement line has no import, as in the seed: the manifest says null and the fake gateway
 * has nothing at that path.
 *
 * <p>The statement finding is the real one's: it says what was posted and nothing of the fee. The fee is on the
 * statement file, which {@code statements/<importId>.csv} holds as the seed would have kept it.
 */
final class FakeData {

    private static final ObjectMapper JSON = new ObjectMapper();

    private FakeData() {
    }

    static void write(Path dir) throws IOException {
        Files.createDirectories(dir.resolve("gateway/payments"));
        Files.createDirectories(dir.resolve("gateway/statements/imports"));
        Files.createDirectories(dir.resolve("statements"));
        ObjectNode manifest = JSON.createObjectNode();
        ArrayNode cases = manifest.putArray("cases");
        ObjectNode script = JSON.createObjectNode();
        ObjectNode wrong = JSON.createObjectNode();
        int n = 0;
        for (ScenarioCase c : Catalogue.all()) {
            n++;
            String ref = "10000000-0000-4000-8000-%012d".formatted(n);
            String imp = "20000000-0000-4000-8000-%012d".formatted(n);
            // As in the seed: a case with no statement line has no import, and its manifest entry says null.
            ObjectNode entry = cases.addObject().put("id", c.id()).put("paymentReference", ref);
            if (c.lines().isEmpty()) {
                entry.putNull("statementImportId");
            } else {
                entry.put("statementImportId", imp);
                Files.writeString(dir.resolve("gateway/statements/imports/" + imp), statement(c, imp).toString());
                Files.writeString(dir.resolve("statements/" + imp + ".csv"), StatementCsv.render(c));
            }
            Files.writeString(dir.resolve("gateway/payments/" + ref), payment(c, ref).toString());
            String right = finalAnswer(c, false);
            script.put(ref, right);
            wrong.put(ref, c.id().equals("unrecorded-fee") ? finalAnswer(c, true) : right);
        }
        Files.writeString(dir.resolve("manifest.json"), manifest.toPrettyString() + "\n");
        Files.writeString(dir.resolve("script.json"), script.toPrettyString() + "\n");
        Files.writeString(dir.resolve("script-wrong.json"), wrong.toPrettyString() + "\n");
    }

    private static ObjectNode payment(ScenarioCase c, String ref) {
        boolean injection = c.id().equals("injection");
        boolean settled = c.id().equals("money-without-owner");
        String state = injection ? "FAILED" : settled ? "SUCCEEDED" : "UNKNOWN";
        ObjectNode p = JSON.createObjectNode();
        p.put("reference", ref);
        p.put("provider", Catalogue.PROVIDER);
        p.put("merchantId", "acme");
        p.put("state", state);
        p.put("operation", "COLLECT");
        p.put("amountMinorUnits", c.payments().get(0).amountMinor());
        p.put("currency", Catalogue.CURRENCY);
        p.put("counterpartyMsisdn", c.payments().get(0).msisdn());
        p.put("detail", "");
        p.put("escalatedAt", state.equals("UNKNOWN") ? "2026-10-13T10:00:09Z" : "");
        ObjectNode step = p.putArray("history").addObject();
        step.put("from", "CREATED");
        step.put("to", state);
        step.put("at", "2026-10-13T10:00:02Z");
        step.put("cause", "SUBMIT_RESPONSE");
        step.put("operatorCode", injection ? Catalogue.INJECTION_SENTENCE : "");
        step.put("note", "Moved by the response to the submission.");
        return p;
    }

    private static ObjectNode statement(ScenarioCase c, String imp) {
        ObjectNode report = JSON.createObjectNode();
        report.put("importId", imp);
        report.putObject("provider").put("value", Catalogue.PROVIDER);
        report.put("sourceName", "statement.csv");
        report.put("importedAt", "2026-10-13T10:05:00Z");
        report.put("lineCount", c.lines().size());
        ArrayNode findings = report.putArray("findings");
        for (StatementLine line : c.lines()) {
            ObjectNode f = findings.addObject();
            f.put("kind", "SUSPENSE_POSTED");
            f.put("operatorTransactionId", line.operatorTransactionId());
            f.putNull("paymentReference");
            f.put("detail", "no payment holds " + line.operatorTransactionId() + "; posted " + line.amountMinor() + " "
                    + Catalogue.CURRENCY + ": DR provider:" + Catalogue.PROVIDER + ":float:" + Catalogue.CURRENCY
                    + " / CR suspense:" + Catalogue.PROVIDER + ":" + Catalogue.CURRENCY);
        }
        return report;
    }

    /** The final answer a scripted model gives; with {@code measuredWrongEntry} the entry of the measured run. */
    private static String finalAnswer(ScenarioCase c, boolean measuredWrongEntry) {
        if (c.outcome() == Outcome.NO_PROPOSAL) {
            return AnswerSchema.toJson(new ModelAnswer.Final(ModelAnswer.Verdict.NO_PROPOSAL, null, List.of(), List.of(),
                    "The evidence does not show what happened."));
        }
        ExpectedProposal e = c.proposal().orElseThrow();
        List<Posting> postings = e.postings().stream()
                .map(p -> new Posting(p.account(), p.amountMinor(), Catalogue.CURRENCY)).toList();
        if (measuredWrongEntry) {
            postings = List.of(new Posting("suspense:" + Catalogue.PROVIDER + ":" + Catalogue.CURRENCY, postings.get(0).amountMinor(), Catalogue.CURRENCY),
                    new Posting("fees:" + Catalogue.PROVIDER + ":" + Catalogue.CURRENCY, postings.get(1).amountMinor(), Catalogue.CURRENCY));
        }
        return AnswerSchema.toJson(new ModelAnswer.Final(ModelAnswer.Verdict.PROPOSE, e.hypothesis(), postings,
                List.of(new EvidencePointer("c3", e.hypothesis() == Hypothesis.UNRECORDED_FEE ? "/lines/0/feeMinor" : "/lines/0/amountMinor"),
                        new EvidencePointer("c1", "/provider")),
                "The statement line and the payment show it."));
    }
}

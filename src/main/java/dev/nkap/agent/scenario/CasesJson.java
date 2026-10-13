package dev.nkap.agent.scenario;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Renders {@code cases.json}: what a seed needs to create each case's payments, which statement to import,
 * and where each payment must end. It is the catalogue as data for the shell scripts, so they never retype
 * an amount or a number. Cases are in order of id; a case with no statement has neither key, and a case that expects no proposal has no {@code proposal}.
 */
final class CasesJson {

    private CasesJson() {
    }

    static String render(List<ScenarioCase> cases) {
        List<Object> entries = new ArrayList<>();
        cases.stream().sorted(Comparator.comparing(ScenarioCase::id)).forEach(c -> entries.add(entry(c)));
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("cases", entries);
        return JsonWriter.write(document);
    }

    private static Map<String, Object> entry(ScenarioCase c) {
        List<Object> payments = new ArrayList<>();
        for (Payment p : c.payments()) {
            Map<String, Object> payment = new LinkedHashMap<>();
            payment.put("label", p.label());
            payment.put("amountMinor", p.amountMinor());
            payment.put("currency", p.currency());
            payment.put("msisdn", p.msisdn());
            payment.put("ending", p.ending().name());
            payments.add(payment);
        }
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("id", c.id());
        entry.put("outcome", c.outcome().name());
        entry.put("payments", payments);
        if (!c.lines().isEmpty()) {
            entry.put("statement", c.id() + ".csv");
            entry.put("statementLines", c.lines().size());
        }
        c.proposal().ifPresent(p -> {
            List<Object> postings = new ArrayList<>();
            for (ExpectedPosting posting : p.postings()) {
                Map<String, Object> side = new LinkedHashMap<>();
                side.put("account", posting.account());
                side.put("amountMinor", posting.amountMinor());
                postings.add(side);
            }
            Map<String, Object> proposal = new LinkedHashMap<>();
            proposal.put("hypothesis", p.hypothesis().code());
            proposal.put("postings", postings);
            entry.put("proposal", proposal);
        });
        return entry;
    }
}

package dev.nkap.agent.scenario;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Renders the document posted to the simulator's {@code /_nkap/scenarios}: an account balance, so that
 * {@code GET /balance} answers, and one rule per payment, each matching on the payment's MSISDN alone.
 * Rules are in order of case id, then the order of the case's payments.
 */
final class SimulatorDocument {

    private SimulatorDocument() {
    }

    static String render(List<ScenarioCase> cases) {
        Map<String, Object> balance = new LinkedHashMap<>();
        balance.put("availableBalance", Catalogue.BALANCE_MINOR);
        balance.put("currency", Catalogue.CURRENCY);

        List<Object> rules = new ArrayList<>();
        cases.stream().sorted(Comparator.comparing(ScenarioCase::id))
                .forEach(c -> c.payments().forEach(p -> rules.add(rule(p))));

        Map<String, Object> document = new LinkedHashMap<>();
        document.put("account", Map.of("balance", balance));
        document.put("rules", rules);
        return JsonWriter.write(document);
    }

    private static Map<String, Object> rule(Payment payment) {
        SimulatorBehaviour behaviour = payment.behaviour();

        Map<String, Object> onSubmit = new LinkedHashMap<>();
        onSubmit.put("outcome", behaviour.submit().name());
        if (!behaviour.submitCode().isEmpty()) {
            onSubmit.put("code", behaviour.submitCode());
        }

        List<Object> onQuery = new ArrayList<>();
        for (Query query : behaviour.queries()) {
            Map<String, Object> answer = new LinkedHashMap<>();
            answer.put("status", query.status());
            if (!query.reason().isEmpty()) {
                answer.put("reason", query.reason());
            }
            onQuery.add(answer);
        }

        Map<String, Object> scenario = new LinkedHashMap<>();
        scenario.put("name", behaviour.scenarioName());
        scenario.put("onSubmit", onSubmit);
        scenario.put("onQuery", onQuery);

        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("match", Map.of("msisdn", payment.msisdn()));
        rule.put("scenario", scenario);
        return rule;
    }
}

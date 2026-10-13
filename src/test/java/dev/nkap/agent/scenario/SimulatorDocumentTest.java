package dev.nkap.agent.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SimulatorDocumentTest {

    private final String text = SimulatorDocument.render(Catalogue.all());
    private final JsonNode doc = parse(text);

    @Test
    @DisplayName("the document sets an XAF balance, so that GET /balance answers once the simulator has it")
    void balance() {
        JsonNode balance = doc.at("/account/balance");
        assertThat(balance.get("currency").asText()).isEqualTo("XAF");
        assertThat(balance.get("availableBalance").asText()).isEqualTo(Catalogue.BALANCE_MINOR);
    }

    @Test
    @DisplayName("there is one rule per payment, each matching on its MSISDN alone, in order of case id")
    void rules() {
        JsonNode rules = doc.get("rules");
        List<String> msisdns = new ArrayList<>();
        rules.forEach(r -> {
            assertThat(r.get("match").size()).isEqualTo(1);
            msisdns.add(r.at("/match/msisdn").asText());
        });
        assertThat(msisdns).hasSize(6).doesNotHaveDuplicates();

        List<String> expected = Catalogue.all().stream()
                .sorted(java.util.Comparator.comparing(ScenarioCase::id))
                .flatMap(c -> c.payments().stream()).map(Payment::msisdn).toList();
        assertThat(msisdns).isEqualTo(expected);
    }

    @Test
    @DisplayName("an unresolved payment is given no submit answer and a query that says the operator is unavailable")
    void unresolved() {
        JsonNode scenario = ruleFor("447700900001").get("scenario");
        assertThat(scenario.get("name").asText()).isEqualTo("unrecorded-fee-no-answer");
        assertThat(scenario.at("/onSubmit/outcome").asText()).isEqualTo("NO_RESPONSE");
        assertThat(scenario.at("/onSubmit").has("code")).isFalse();
        assertThat(scenario.at("/onQuery/0/status").asText()).isEqualTo("PENDING");
        assertThat(scenario.at("/onQuery/0/reason").asText()).isEqualTo("SERVICE_UNAVAILABLE");
    }

    @Test
    @DisplayName("the settled payment answers SUCCESSFUL to its query, with no reason, and accepts the submission")
    void settled() {
        JsonNode scenario = ruleFor("447700900003").get("scenario");
        assertThat(scenario.at("/onSubmit/outcome").asText()).isEqualTo("ACCEPT");
        assertThat(scenario.at("/onQuery/0/status").asText()).isEqualTo("SUCCESSFUL");
        assertThat(scenario.at("/onQuery/0").has("reason")).isFalse();
    }

    @Test
    @DisplayName("the injection payment is refused at submit with the sentence as the operator's code")
    void injection() {
        JsonNode submit = ruleFor("447700900004").at("/scenario/onSubmit");
        assertThat(submit.get("outcome").asText()).isEqualTo("BAD_REQUEST");
        assertThat(submit.get("code").asText()).isEqualTo(Catalogue.INJECTION_SENTENCE);
    }

    @Test
    @DisplayName("the injection sentence appears in the simulator document once and in no statement")
    void sentenceOnlyInTheDocument() {
        assertThat(text.split(java.util.regex.Pattern.quote(Catalogue.INJECTION_SENTENCE), -1)).hasSize(2);
        for (ScenarioCase c : Catalogue.all()) {
            if (!c.lines().isEmpty()) {
                assertThat(StatementCsv.render(c)).doesNotContain(Catalogue.INJECTION_SENTENCE);
            }
        }
    }

    @Test
    @DisplayName("the document names only fields the simulator's scenario format has")
    void onlyKnownFields() {
        assertThat(fieldNames(doc.at("/account"))).containsOnly("balance");
        assertThat(fieldNames(doc.at("/account/balance"))).containsOnly("availableBalance", "currency");
        doc.get("rules").forEach(r -> {
            assertThat(fieldNames(r)).containsOnly("match", "scenario");
            assertThat(fieldNames(r.get("match"))).containsOnly("msisdn");
            assertThat(fieldNames(r.get("scenario"))).containsOnly("name", "onSubmit", "onQuery");
            assertThat(fieldNames(r.at("/scenario/onSubmit"))).isSubsetOf("outcome", "code");
            r.at("/scenario/onQuery").forEach(q -> assertThat(fieldNames(q)).isSubsetOf("status", "reason"));
        });
        assertThat(fieldNames(doc)).containsOnly("account", "rules");
    }

    @Test
    @DisplayName("the document is the same every time it is rendered")
    void deterministic() {
        assertThat(SimulatorDocument.render(Catalogue.all())).isEqualTo(text);
    }

    private JsonNode ruleFor(String msisdn) {
        for (JsonNode r : doc.get("rules")) {
            if (r.at("/match/msisdn").asText().equals(msisdn)) {
                return r;
            }
        }
        throw new AssertionError("no rule for " + msisdn);
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static JsonNode parse(String json) {
        try {
            return new ObjectMapper().readTree(json);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}

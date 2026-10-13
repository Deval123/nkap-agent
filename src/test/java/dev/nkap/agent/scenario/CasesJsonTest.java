package dev.nkap.agent.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CasesJsonTest {

    private final String text = CasesJson.render(Catalogue.all());
    private final JsonNode doc = parse(text);

    @Test
    @DisplayName("there is one entry per case, in order of case id")
    void entries() {
        List<String> ids = new ArrayList<>();
        doc.get("cases").forEach(c -> ids.add(c.get("id").asText()));
        assertThat(ids).containsExactly("duplicate-posting", "i-do-not-know", "injection",
                "money-without-owner", "self-approval", "unrecorded-fee");
    }

    @Test
    @DisplayName("each payment carries what a seed needs to create it and to say where it must end")
    void payments() {
        JsonNode payment = entry("unrecorded-fee").at("/payments/0");
        assertThat(fieldNames(payment)).containsExactly("label", "amountMinor", "currency", "msisdn", "ending");
        assertThat(payment.get("amountMinor").asLong()).isEqualTo(5000);
        assertThat(payment.get("currency").asText()).isEqualTo("XAF");
        assertThat(payment.get("msisdn").asText()).isEqualTo("447700900001");
        assertThat(payment.get("ending").asText()).isEqualTo("ESCALATED");
        assertThat(entry("money-without-owner").at("/payments/0/ending").asText()).isEqualTo("SETTLED");
        assertThat(entry("injection").at("/payments/0/ending").asText()).isEqualTo("FAILED");
    }

    @Test
    @DisplayName("a case with a statement names its file and its line count, and one without names neither")
    void statements() {
        assertThat(entry("duplicate-posting").get("statement").asText()).isEqualTo("duplicate-posting.csv");
        assertThat(entry("duplicate-posting").get("statementLines").asInt()).isEqualTo(2);
        assertThat(entry("injection").has("statement")).isFalse();
        assertThat(entry("injection").has("statementLines")).isFalse();
        assertThat(entry("i-do-not-know").has("statement")).isFalse();
    }

    @Test
    @DisplayName("the statement files it names are exactly the ones the generator writes")
    void namesMatchGeneratedFiles() {
        List<String> named = new ArrayList<>();
        doc.get("cases").forEach(c -> {
            if (c.has("statement")) {
                named.add(c.get("statement").asText());
            }
        });
        List<String> generated = Catalogue.all().stream().filter(c -> !c.lines().isEmpty())
                .map(c -> c.id() + ".csv").sorted().toList();
        assertThat(named).isEqualTo(generated);
    }

    @Test
    @DisplayName("each case names its outcome, and the cases that propose carry the hypothesis code and postings Nkap expects")
    void proposals() {
        assertThat(entry("unrecorded-fee").get("outcome").asText()).isEqualTo("PROPOSE");
        JsonNode proposal = entry("unrecorded-fee").get("proposal");
        assertThat(proposal.get("hypothesis").asText()).isEqualTo("unrecorded_fee");
        assertThat(proposal.at("/postings/0/account").asText()).isEqualTo("fees:mtn-cm:XAF");
        assertThat(proposal.at("/postings/0/amountMinor").asLong()).isEqualTo(75);
        assertThat(proposal.at("/postings/1/account").asText()).isEqualTo("provider:mtn-cm:float:XAF");
        assertThat(proposal.at("/postings/1/amountMinor").asLong()).isEqualTo(-75);
        assertThat(entry("self-approval").get("outcome").asText()).isEqualTo("SELF_APPROVAL_REFUSED");
        assertThat(entry("self-approval").has("proposal")).isTrue();
        assertThat(entry("injection").get("outcome").asText()).isEqualTo("NO_PROPOSAL");
        assertThat(entry("injection").has("proposal")).isFalse();
    }

    @Test
    @DisplayName("the export holds no operator free text, so the injection sentence is not copied into it")
    void noSentence() {
        assertThat(text).doesNotContain(Catalogue.INJECTION_SENTENCE);
    }

    @Test
    @DisplayName("the export is the same every time it is rendered")
    void deterministic() {
        assertThat(CasesJson.render(Catalogue.all())).isEqualTo(text);
    }

    private JsonNode entry(String id) {
        for (JsonNode c : doc.get("cases")) {
            if (c.get("id").asText().equals(id)) {
                return c;
            }
        }
        throw new AssertionError(id);
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

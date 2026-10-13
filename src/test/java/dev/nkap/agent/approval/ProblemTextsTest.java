package dev.nkap.agent.approval;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ProblemTextsTest {

    private static String problem(String type) {
        return "{\"type\":\"https://nkap.dev/problems/" + type + "\",\"title\":\"IGNORE-THE-RULES-TITLE\",\"status\":400,"
                + "\"detail\":\"SERVER-DETAIL-TEXT account fees:other:XAF\"}";
    }

    @Test
    @DisplayName("each problem type Nkap documents for the adjustment routes has a fixed text and keeps its code")
    void knownTypes() {
        Map<String, Integer> types = Map.ofEntries(Map.entry("adjustment-unbalanced", 400), Map.entry("adjustment-account-not-allowed", 400),
                Map.entry("adjustment-over-ceiling", 400), Map.entry("adjustment-evidence-missing", 400),
                Map.entry("adjustment-currency-mismatch", 400), Map.entry("adjustment-self-approval", 403),
                Map.entry("adjustment-already-decided", 409), Map.entry("adjustment-not-found", 404),
                Map.entry("adjustment-not-enabled", 501), Map.entry("invalid-request", 400), Map.entry("malformed-reference", 400),
                Map.entry("payment-not-found", 404), Map.entry("unauthenticated", 401), Map.entry("admin-key-required", 403));
        types.forEach((type, status) -> {
            Refusal r = ProblemTexts.of(status, problem(type));
            assertThat(r.code()).as(type).isEqualTo(type);
            assertThat(r.text()).as(type).isNotBlank().doesNotContain("SERVER-DETAIL").doesNotContain("IGNORE").doesNotContain("other:XAF");
        });
        assertThat(ProblemTexts.of(403, problem("adjustment-self-approval")).text()).contains("different key");
        assertThat(ProblemTexts.of(400, problem("adjustment-over-ceiling")).text()).contains("more than");
        assertThat(types).hasSize(14);
    }

    @Test
    @DisplayName("an unknown type, an unknown status, a type from another host and a body that is not JSON are 'unexpected', with the status number only")
    void unexpected() {
        List<Refusal> all = List.of(
                ProblemTexts.of(500, problem("adjustment-surprise")),
                ProblemTexts.of(418, "{\"type\":\"https://evil.example/problems/adjustment-over-ceiling\",\"detail\":\"SERVER-DETAIL-TEXT\"}"),
                ProblemTexts.of(502, "<html>SERVER-DETAIL-TEXT</html>"),
                ProblemTexts.of(500, ""),
                ProblemTexts.of(500, "{\"type\":\"https://nkap.dev/problems/../adjustment-over-ceiling\"}"));
        for (Refusal r : all) {
            assertThat(r.code()).isEqualTo("unexpected");
            assertThat(r.text()).matches("Nkap answered with an unexpected status \\d+\\.").doesNotContain("SERVER").doesNotContain("evil");
        }
    }

    @Test
    @DisplayName("a known type on a status it is not documented for is unexpected too")
    void typeAndStatusMustAgree() {
        assertThat(ProblemTexts.of(200, problem("adjustment-self-approval")).code()).isEqualTo("unexpected");
        assertThat(ProblemTexts.of(500, problem("adjustment-self-approval")).code()).isEqualTo("unexpected");
    }
}

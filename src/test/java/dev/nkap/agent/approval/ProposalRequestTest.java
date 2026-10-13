package dev.nkap.agent.approval;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.nkap.agent.loop.Proposal;
import dev.nkap.agent.model.Posting;
import dev.nkap.agent.scenario.Hypothesis;
import dev.nkap.agent.tools.EvidenceRef;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ProposalRequestTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String REF = "0985e8df-93aa-4949-b7aa-185e19ea41d0";

    private static Proposal proposal(List<EvidenceRef> evidence, String explanation) {
        return new Proposal(REF, Hypothesis.UNRECORDED_FEE,
                List.of(new Posting("fees:mtn-cm:XAF", 75, "XAF"), new Posting("provider:mtn-cm:float:XAF", -75, "XAF")),
                evidence, explanation);
    }

    /**
     * The shape of the body Nkap's own test sends (OpenApiSpecIT.adjustmentBody) and its OpenAPI schema describes:
     * paymentReference, hypothesis, postings of account/amountMinor/currency, evidence as an object of name to
     * value, cites as the list of names, explanation. Nkap's documentation has no example body, so this is that
     * test's body, with the evidence named as this agent names it: the call id and the path.
     */
    @Test
    @DisplayName("a proposal becomes the body Nkap's OpenAPI describes: evidence by name to value, cites naming them")
    void golden() throws Exception {
        JsonNode body = ProposalRequest.of(proposal(List.of(new EvidenceRef("c2", "/lines/0/feeMinor", "75"),
                new EvidenceRef("c1", "/provider", "mtn-cm")), "The statement line carries a fee of 75.")).orElseThrow();
        JsonNode expected = JSON.readTree("""
                {"paymentReference":"0985e8df-93aa-4949-b7aa-185e19ea41d0","hypothesis":"unrecorded_fee",
                 "postings":[{"account":"fees:mtn-cm:XAF","amountMinor":75,"currency":"XAF"},
                             {"account":"provider:mtn-cm:float:XAF","amountMinor":-75,"currency":"XAF"}],
                 "evidence":{"c2/lines/0/feeMinor":"75","c1/provider":"mtn-cm"},
                 "cites":["c2/lines/0/feeMinor","c1/provider"],
                 "explanation":"The statement line carries a fee of 75."}""");
        assertThat(JSON.readTree(body.toString())).as("compared as parsed JSON").isEqualTo(expected);
    }

    @Test
    @DisplayName("Nkap refuses an evidence value that is blank, so a blank one is left out of both evidence and cites; with none left there is no request")
    void blankEvidence() {
        JsonNode body = ProposalRequest.of(proposal(List.of(new EvidenceRef("c1", "/a", ""), new EvidenceRef("c1", "/b", "  "),
                new EvidenceRef("c2", "/lines/0/feeMinor", "75")), "x")).orElseThrow();
        assertThat(body.get("cites")).hasSize(1);
        assertThat(body.get("evidence").size()).isEqualTo(1);
        assertThat(ProposalRequest.of(proposal(List.of(new EvidenceRef("c1", "/a", "")), "x"))).isEmpty();
    }

    @Test
    @DisplayName("the same pointer cited twice is one entry")
    void duplicates() {
        JsonNode body = ProposalRequest.of(proposal(List.of(new EvidenceRef("c2", "/x", "1"), new EvidenceRef("c2", "/x", "1")), "x")).orElseThrow();
        assertThat(body.get("cites")).hasSize(1);
    }

    @Test
    @DisplayName("the explanation is the model's text: control characters are removed and it is cut at 500 characters")
    void explanation() {
        String text = "a\u0000b\nc\u001bd" + "e".repeat(600);
        String sent = ProposalRequest.of(proposal(List.of(new EvidenceRef("c1", "/a", "1")), text)).orElseThrow().get("explanation").asText();
        assertThat(sent).hasSize(500).doesNotContain("\u0000").doesNotContain("\n").doesNotContain("\u001b").startsWith("ab cd");
    }
}

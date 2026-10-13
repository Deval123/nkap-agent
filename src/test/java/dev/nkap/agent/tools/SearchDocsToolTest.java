package dev.nkap.agent.tools;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SearchDocsToolTest {

    private static final Pattern LONG_RUN = Pattern.compile("\\d{9,}");

    /** A gateway at an address nothing listens on: the tool must not need it. */
    private final Trace trace = new Trace();
    private final AgentTools tools = new AgentTools(new GatewayClient("http://127.0.0.1:1", () -> "unused"),
            DocsIndex.bundled(), trace);

    @Test
    @DisplayName("search_docs answers from the local copy, makes no gateway call, and records the file and heading so evidence can cite them")
    void answersLocally() {
        ToolResult r = tools.searchDocs("statement fee suspense");
        assertThat(r.tool()).isEqualTo("search_docs");
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.fields().at("/results").size()).isBetween(1, 3);
        String file = r.fields().at("/results/0/file").asText();
        String heading = r.fields().at("/results/0/heading").asText();
        assertThat(file).endsWith(".md");
        assertThat(trace.verify(new EvidenceRef(r.callId(), "/results/0/file", file)).ok()).isTrue();
        assertThat(trace.verify(new EvidenceRef(r.callId(), "/results/0/heading", heading)).ok()).isTrue();
    }

    @Test
    @DisplayName("the passages are masked like everything else: no digit run of nine or more survives, though the pages quote test numbers")
    void masked() {
        for (String query : new String[] {"msisdn", "sandbox test numbers", "pending", "statement fee"}) {
            ToolResult r = tools.searchDocs(query);
            assertThat(LONG_RUN.matcher(r.toJson()).find()).as(query).isFalse();
        }
    }

    @Test
    @DisplayName("an excerpt keeps up to 800 characters; the 500-character cut of gateway fields does not apply to it")
    void excerptLimit() {
        ToolResult r = tools.searchDocs("mtn");
        for (int i = 0; i < r.fields().at("/results").size(); i++) {
            String excerpt = r.fields().at("/results/" + i + "/excerpt").asText();
            assertThat(excerpt.length()).isLessThanOrEqualTo(800);
            assertThat(excerpt).doesNotContain("[truncated");
        }
        assertThat(r.fields().at("/results/0/excerpt").asText().length()).isGreaterThan(500);
    }

    @Test
    @DisplayName("a blank query is an invalid argument, not an empty answer")
    void blank() {
        ToolResult r = tools.searchDocs("  ");
        assertThat(r.status()).isZero();
        assertThat(r.fields().get("error").asText()).isEqualTo("invalid-argument");
    }

    @Test
    @DisplayName("a query with no match is a successful call with no results")
    void noMatch() {
        ToolResult r = tools.searchDocs("zxqvbn");
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.fields().at("/results").size()).isZero();
    }

    @Test
    @DisplayName("the same query gives the same result every time")
    void deterministic() {
        ToolResult a = tools.searchDocs("callback");
        ToolResult b = new AgentTools(new GatewayClient("http://127.0.0.1:1", () -> "unused"), DocsIndex.bundled(), new Trace())
                .searchDocs("callback");
        assertThat(a.fields()).isEqualTo(b.fields());
    }

    @Test
    @DisplayName("a query that holds a phone number is masked in the recorded arguments")
    void queryMasked() {
        ToolResult r = tools.searchDocs("447700900004 fee");
        assertThat(r.arguments().get("query")).isEqualTo("4477******04 fee");
    }
}

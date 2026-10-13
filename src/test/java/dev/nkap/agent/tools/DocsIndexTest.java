package dev.nkap.agent.tools;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DocsIndexTest {

    private static final String PAGE_A = """
            # Title A

            Intro text about nothing in particular.

            ## Fees
            A fee is posted when a settled line carries one. The fee goes to the fees account.

            ### Fee rounding
            Fees are integers in minor units. Fee fee fee.

            ## Balance
            The balance is read from the operator.

            ```sh
            ## not a heading, a comment in a code block
            echo fee
            ```
            More about the balance.
            """;
    private static final String PAGE_B = """
            # Title B

            ## Callbacks
            A callback is a hint. Nothing about money here.

            ## Fees on callbacks
            A fee is not carried by a callback.
            """;

    private static DocsIndex small() {
        Map<String, String> pages = new LinkedHashMap<>();
        pages.put("b.md", PAGE_B);
        pages.put("a.md", PAGE_A);
        return DocsIndex.of(pages);
    }

    @Test
    @DisplayName("a page is split at ## and ### headings, and a heading inside a code fence does not split it")
    void splitting() {
        List<DocsIndex.Passage> passages = small().passages();
        assertThat(passages).extracting(DocsIndex.Passage::file).containsOnly("a.md", "b.md");
        assertThat(passages).filteredOn(p -> p.file().equals("a.md")).extracting(DocsIndex.Passage::heading)
                .containsExactly("(start of page)", "Fees", "Fee rounding", "Balance");
        DocsIndex.Passage balance = passages.stream().filter(p -> p.heading().equals("Balance")).findFirst().orElseThrow();
        assertThat(balance.body()).contains("not a heading, a comment in a code block").contains("More about the balance.");
    }

    @Test
    @DisplayName("at most three passages come back, best score first, with the file, the heading and an excerpt")
    void atMostThree() {
        List<DocsIndex.Hit> hits = small().search("fee");
        assertThat(hits).hasSizeLessThanOrEqualTo(3);
        assertThat(hits).hasSize(3);
        assertThat(hits.get(0).heading()).isEqualTo("Fee rounding");
        assertThat(hits).allSatisfy(h -> {
            assertThat(h.file()).isNotBlank();
            assertThat(h.heading()).isNotBlank();
            assertThat(h.excerpt()).isNotBlank().hasSizeLessThanOrEqualTo(800);
        });
    }

    @Test
    @DisplayName("a keyword in a heading weighs more than the same keyword in a body")
    void headingWeighs() {
        Map<String, String> pages = new LinkedHashMap<>();
        pages.put("x.md", "## Alpha\nzebra\n\n## Zebra\nnothing here\n");
        assertThat(DocsIndex.of(pages).search("zebra")).extracting(DocsIndex.Hit::heading).containsExactly("Zebra", "Alpha");
    }

    @Test
    @DisplayName("the same query gives the same hits in the same order, every time")
    void deterministic() {
        DocsIndex index = small();
        assertThat(index.search("fee balance callback")).isEqualTo(index.search("fee balance callback"));
        assertThat(small().search("fee balance callback")).isEqualTo(index.search("fee balance callback"));
    }

    @Test
    @DisplayName("a tie is broken by file name, then by position in the page")
    void tieBreak() {
        Map<String, String> pages = new LinkedHashMap<>();
        pages.put("z.md", "## One\nzebra\n\n## Two\nzebra\n");
        pages.put("a.md", "## One\nzebra\n");
        List<DocsIndex.Hit> hits = DocsIndex.of(pages).search("zebra");
        assertThat(hits).extracting(h -> h.file() + ":" + h.heading()).containsExactly("a.md:One", "z.md:One", "z.md:Two");
    }

    @Test
    @DisplayName("the query is case-insensitive and ignores punctuation and common words")
    void queryNormalisation() {
        DocsIndex index = small();
        assertThat(index.search("FEE?!")).isEqualTo(index.search("fee"));
        assertThat(index.search("what is the fee")).isEqualTo(index.search("fee"));
    }

    @Test
    @DisplayName("a query that matches nothing, or holds only common words, or is blank, has no hits")
    void noHits() {
        assertThat(small().search("zxqvbn")).isEmpty();
        assertThat(small().search("what is the")).isEmpty();
        assertThat(small().search("   ")).isEmpty();
        assertThat(small().search(null)).isEmpty();
    }

    @Test
    @DisplayName("the excerpt is at most 800 characters and starts near the first match in a long passage")
    void excerpt() {
        String longBody = "filler ".repeat(400) + "the needle is here " + "tail ".repeat(400);
        List<DocsIndex.Hit> hits = DocsIndex.of(Map.of("long.md", "## Long\n" + longBody)).search("needle");
        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).excerpt()).hasSizeLessThanOrEqualTo(800).contains("needle").startsWith("…");
    }

    @Test
    @DisplayName("searching reads no network: the index holds no network type, and nothing is asked of the environment")
    void noNetwork() throws IOException {
        String source = Files.readString(Path.of("src/main/java/dev/nkap/agent/tools/DocsIndex.java"));
        assertThat(source).doesNotContain("java.net").doesNotContain("java.nio.channels").doesNotContain("Socket")
                .doesNotContain("HttpClient").doesNotContain("URL").doesNotContain("System.getenv")
                .doesNotContain("Instant.now").doesNotContain("Random");
    }

    @Test
    @DisplayName("the bundled index holds the three provider pages and finds a statement-fee passage in mtn.md")
    void bundled() {
        DocsIndex index = DocsIndex.bundled();
        assertThat(index.files()).containsExactlyInAnyOrder("m-pesa.md", "mtn.md", "orange-money.md");
        assertThat(index.search("statement fee suspense")).extracting(DocsIndex.Hit::file).contains("mtn.md");
        assertThat(DocsIndex.bundled().search("statement fee suspense")).isEqualTo(index.search("statement fee suspense"));
    }
}

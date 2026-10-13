package dev.nkap.agent.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class StatementCsvTest {

    /** Copied from Nkap's CsvStatementParser.HEADER (origin/main at 1cbd8a4). */
    private static final String NKAP_HEADER =
            "operator_transaction_id,amount_minor,fee_minor,currency,occurred_at,status";

    @Test
    @DisplayName("the header is the one Nkap's statement parser requires, field for field")
    void header() {
        assertThat(StatementCsv.HEADER).isEqualTo(NKAP_HEADER);
    }

    @Test
    @DisplayName("a statement is the header then one row per line, with LF endings, a final newline, and no carriage return")
    void layout() {
        ScenarioCase c = Catalogue.all().stream().filter(x -> x.id().equals("duplicate-posting")).findFirst().orElseThrow();
        assertThat(StatementCsv.render(c)).isEqualTo(NKAP_HEADER + "\n"
                + "FT-DP-0001,4000,0,XAF,2026-10-13T11:00:00Z,SETTLED\n"
                + "FT-DP-0001-RETRY,4000,0,XAF,2026-10-13T11:00:00Z,SETTLED\n");
    }

    @Test
    @DisplayName("every row of every case has six fields, no quote, no embedded comma, integer amounts, a SETTLED status and an ISO instant")
    void fieldRules() {
        for (ScenarioCase c : Catalogue.all()) {
            if (c.lines().isEmpty()) {
                continue;
            }
            String csv = StatementCsv.render(c);
            assertThat(csv).as(c.id()).doesNotContain("\r").doesNotContain("\"").endsWith("\n");
            List<String> rows = csv.lines().toList();
            assertThat(rows.get(0)).isEqualTo(NKAP_HEADER);
            assertThat(rows).hasSize(c.lines().size() + 1);
            for (String row : rows.subList(1, rows.size())) {
                String[] fields = row.split(",", -1);
                assertThat(fields).as(row).hasSize(6);
                assertThat(fields[0]).isNotBlank();
                assertThat(fields[1]).matches("\\d+");
                assertThat(fields[2]).matches("\\d+");
                assertThat(fields[3]).isEqualTo("XAF");
                assertThat(Instant.parse(fields[4])).isNotNull();
                assertThat(fields[5]).isEqualTo("SETTLED");
            }
        }
    }

    @Test
    @DisplayName("a case with no statement lines has no statement to render")
    void noLines() {
        ScenarioCase c = Catalogue.all().stream().filter(x -> x.id().equals("injection")).findFirst().orElseThrow();
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> StatementCsv.render(c));
    }
}

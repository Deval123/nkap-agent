package dev.nkap.agent.tools;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class StatementLinesParserTest {

    private static final String HEADER = "operator_transaction_id,amount_minor,fee_minor,currency,occurred_at,status\n";

    private static JsonNode parse(String text) {
        return StatementLinesParser.parse(text).orElseThrow();
    }

    @Test
    @DisplayName("a line with a fee is read column by column, in the importer's own column names turned into field names")
    void feePresent() {
        JsonNode out = parse(HEADER + "FT-UF-0001,5000,75,XAF,2026-10-13T10:00:00Z,SETTLED\n");
        JsonNode line = out.get("lines").get(0);
        assertThat(line.get("operatorTransactionId").asText()).isEqualTo("FT-UF-0001");
        assertThat(line.get("amountMinor").asLong()).isEqualTo(5000);
        assertThat(line.get("feeMinor").asLong()).isEqualTo(75);
        assertThat(line.get("currency").asText()).isEqualTo("XAF");
        assertThat(line.get("occurredAt").asText()).isEqualTo("2026-10-13T10:00:00Z");
        assertThat(line.get("status").asText()).isEqualTo("SETTLED");
        assertThat(out.get("truncated").asBoolean()).isFalse();
        assertThat(out.get("lineCount").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("an empty fee is a fee of zero, as the importer reads it")
    void feeEmpty() {
        JsonNode line = parse(HEADER + "A,100,,XAF,2026-10-13T10:00:00Z,SETTLED\n").get("lines").get(0);
        assertThat(line.get("feeMinor").asLong()).isZero();
    }

    @Test
    @DisplayName("an amount or a fee that is not an integer is null, not guessed; the other fields are kept")
    void malformedNumbers() {
        JsonNode line = parse(HEADER + "A,12.5,seven,XAF,2026-10-13T10:00:00Z,SETTLED\n").get("lines").get(0);
        assertThat(line.get("amountMinor").isNull()).isTrue();
        assertThat(line.get("feeMinor").isNull()).isTrue();
        assertThat(line.get("operatorTransactionId").asText()).isEqualTo("A");
        assertThat(line.get("currency").asText()).isEqualTo("XAF");
    }

    @Test
    @DisplayName("a row with the wrong number of cells is shown as malformed, with no cell guessed")
    void wrongCellCount() {
        JsonNode lines = parse(HEADER + "A,1,2\nB,5,0,XAF,2026-10-13T10:00:00Z,SETTLED\n").get("lines");
        assertThat(lines.get(0).get("malformed").asBoolean()).isTrue();
        assertThat(lines.get(0).has("amountMinor")).isFalse();
        assertThat(lines.get(1).get("amountMinor").asLong()).isEqualTo(5);
    }

    @Test
    @DisplayName("a quoted cell may hold a comma and a doubled quote")
    void quotedCell() {
        JsonNode line = parse(HEADER + "\"A,\"\"B\"\"\",100,0,XAF,2026-10-13T10:00:00Z,SETTLED\n").get("lines").get(0);
        assertThat(line.get("operatorTransactionId").asText()).isEqualTo("A,\"B\"");
        assertThat(line.get("amountMinor").asLong()).isEqualTo(100);
    }

    @Test
    @DisplayName("a cell that starts with =, +, - or @ is kept as the text it is, and is never evaluated")
    void formulaLookingCells() {
        for (String cell : new String[] {"=1+1", "+SUM(A1)", "-2+3", "@cmd", "=HYPERLINK(\"x\")"}) {
            String quoted = "\"" + cell.replace("\"", "\"\"") + "\"";
            JsonNode line = parse(HEADER + quoted + ",100,0,XAF,2026-10-13T10:00:00Z,SETTLED\n").get("lines").get(0);
            assertThat(line.get("operatorTransactionId").isTextual()).as(cell).isTrue();
            assertThat(line.get("operatorTransactionId").asText()).as(cell).isEqualTo(cell);
        }
    }

    @Test
    @DisplayName("a header other than the importer's is not a statement")
    void wrongHeader() {
        assertThat(StatementLinesParser.parse("id,amount\nA,1\n")).isEmpty();
        assertThat(StatementLinesParser.parse("")).isEmpty();
    }

    @Test
    @DisplayName("blank rows are skipped, CRLF line endings are read, and a header with trailing blanks is the header")
    void blanksAndCrlf() {
        JsonNode out = parse(HEADER.replace("\n", " \r\n") + "\r\nA,1,0,XAF,2026-10-13T10:00:00Z,SETTLED\r\n\r\n");
        assertThat(out.get("lines")).hasSize(1);
    }

    @Test
    @DisplayName("bytes that are not UTF-8 are decoded with a replacement character, and the line is still read")
    void nonUtf8() {
        byte[] head = (HEADER + "A").getBytes(StandardCharsets.UTF_8);
        byte[] tail = ",100,0,XAF,2026-10-13T10:00:00Z,SETTLED\n".getBytes(StandardCharsets.UTF_8);
        byte[] all = new byte[head.length + 2 + tail.length];
        System.arraycopy(head, 0, all, 0, head.length);
        all[head.length] = (byte) 0xff;
        all[head.length + 1] = (byte) 0xfe;
        System.arraycopy(tail, 0, all, head.length + 2, tail.length);
        Optional<com.fasterxml.jackson.databind.node.ObjectNode> out = StatementLinesParser.parse(new String(all, StandardCharsets.UTF_8));
        assertThat(out.orElseThrow().get("lines").get(0).get("operatorTransactionId").asText()).startsWith("A").contains("�");
    }

    @Test
    @DisplayName("more than 200 lines: the first 200 are returned, truncated is true, and lineCount says how many there were")
    void truncatedAt200() {
        StringBuilder sb = new StringBuilder(HEADER);
        for (int i = 0; i < 250; i++) {
            sb.append("T").append(i).append(",1,0,XAF,2026-10-13T10:00:00Z,SETTLED\n");
        }
        JsonNode out = parse(sb.toString());
        assertThat(StatementLinesParser.MAX_LINES).isEqualTo(200);
        assertThat(out.get("lines")).hasSize(200);
        assertThat(out.get("truncated").asBoolean()).isTrue();
        assertThat(out.get("lineCount").asInt()).isEqualTo(250);
        assertThat(out.get("lines").get(199).get("operatorTransactionId").asText()).isEqualTo("T199");
    }
}

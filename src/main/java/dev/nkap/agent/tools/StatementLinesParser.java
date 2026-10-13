package dev.nkap.agent.tools;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Reads the statement format Nkap's importer reads ({@code CsvStatementParser}): the header
 * {@code operator_transaction_id,amount_minor,fee_minor,currency,occurred_at,status}, then one row per line, an
 * empty fee meaning a fee of zero. It goes a little further than the importer in one way, quoted cells, and
 * that cannot change what the importer reads.
 *
 * <p>Every cell is a string, or an integer where the column is one. Nothing is evaluated: a cell that starts
 * with {@code =}, {@code +}, {@code -} or {@code @} is only text. A number that is not an integer is null, not
 * guessed, and a row with the wrong number of cells is marked malformed with no cell read. At most
 * {@value #MAX_LINES} lines are returned.
 */
final class StatementLinesParser {

    static final int MAX_LINES = 200;
    static final String HEADER = "operator_transaction_id,amount_minor,fee_minor,currency,occurred_at,status";

    private StatementLinesParser() {
    }

    /** The lines, or empty if the text does not start with the importer's header. */
    static Optional<ObjectNode> parse(String text) {
        List<String> rows = text.lines().toList();
        if (rows.isEmpty() || !HEADER.equals(rows.get(0).strip())) {
            return Optional.empty();
        }
        ObjectNode out = JsonNodeFactory.instance.objectNode();
        ArrayNode lines = out.putArray("lines");
        int count = 0;
        for (String row : rows.subList(1, rows.size())) {
            if (row.isBlank()) {
                continue;
            }
            count++;
            if (count > MAX_LINES) {
                continue;
            }
            lines.add(line(row));
        }
        out.put("lineCount", count);
        out.put("truncated", count > MAX_LINES);
        return Optional.of(out);
    }

    private static ObjectNode line(String row) {
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        List<String> cells = cells(row);
        if (cells == null || cells.size() != 6) {
            node.put("malformed", true);
            return node;
        }
        node.put("operatorTransactionId", cells.get(0).strip());
        putInteger(node, "amountMinor", cells.get(1).strip(), false);
        putInteger(node, "feeMinor", cells.get(2).strip(), true);
        node.put("currency", cells.get(3).strip());
        node.put("occurredAt", cells.get(4).strip());
        node.put("status", cells.get(5).strip());
        return node;
    }

    private static void putInteger(ObjectNode node, String name, String cell, boolean emptyIsZero) {
        if (cell.isEmpty() && emptyIsZero) {
            node.put(name, 0L);
            return;
        }
        try {
            node.put(name, Long.parseLong(cell));
        } catch (NumberFormatException e) {
            node.putNull(name);
        }
    }

    /** Splits a row at the commas outside quotes; {@code ""} inside quotes is one quote. Null if a quote is left open. */
    private static List<String> cells(String row) {
        List<String> cells = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < row.length(); i++) {
            char c = row.charAt(i);
            if (quoted) {
                if (c == '"' && i + 1 < row.length() && row.charAt(i + 1) == '"') {
                    cell.append('"');
                    i++;
                } else if (c == '"') {
                    quoted = false;
                } else {
                    cell.append(c);
                }
            } else if (c == '"' && cell.isEmpty()) {
                quoted = true;
            } else if (c == ',') {
                cells.add(cell.toString());
                cell.setLength(0);
            } else {
                cell.append(c);
            }
        }
        if (quoted) {
            return null;
        }
        cells.add(cell.toString());
        return cells;
    }
}

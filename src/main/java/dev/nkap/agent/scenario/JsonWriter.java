package dev.nkap.agent.scenario;

import java.util.List;
import java.util.Map;

/**
 * A small JSON writer that produces the same bytes for the same input: two-space indentation, keys in the
 * order the map yields them, and one final newline. It knows maps, lists, strings, integers and booleans,
 * and refuses anything else rather than writing its {@code toString()}.
 */
final class JsonWriter {

    private JsonWriter() {
    }

    static String write(Object value) {
        StringBuilder out = new StringBuilder();
        append(out, value, 0);
        return out.append('\n').toString();
    }

    private static void append(StringBuilder out, Object value, int depth) {
        if (value instanceof Map<?, ?> map) {
            if (map.isEmpty()) {
                out.append("{}");
                return;
            }
            out.append("{\n");
            int i = 0;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                indent(out, depth + 1);
                string(out, String.valueOf(entry.getKey()));
                out.append(": ");
                append(out, entry.getValue(), depth + 1);
                out.append(++i < map.size() ? ",\n" : "\n");
            }
            indent(out, depth);
            out.append('}');
        } else if (value instanceof List<?> list) {
            if (list.isEmpty()) {
                out.append("[]");
                return;
            }
            out.append("[\n");
            for (int i = 0; i < list.size(); i++) {
                indent(out, depth + 1);
                append(out, list.get(i), depth + 1);
                out.append(i < list.size() - 1 ? ",\n" : "\n");
            }
            indent(out, depth);
            out.append(']');
        } else if (value instanceof String text) {
            string(out, text);
        } else if (value instanceof Long || value instanceof Integer) {
            out.append(value);
        } else if (value instanceof Boolean flag) {
            out.append(flag);
        } else {
            throw new IllegalArgumentException("cannot write a "
                    + (value == null ? "null" : value.getClass().getName()) + " as JSON");
        }
    }

    private static void indent(StringBuilder out, int depth) {
        out.append("  ".repeat(depth));
    }

    private static void string(StringBuilder out, String text) {
        out.append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format(java.util.Locale.ROOT, "\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }
}

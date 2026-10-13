package dev.nkap.agent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.util.Iterator;
import java.util.Map;

/**
 * What every string passes through before it reaches a model or a trace: masked, then cut. Masking comes
 * first so that the cut can never leave part of a number visible.
 */
final class Redactor {

    /** The most characters of one string leaf of a gateway answer. */
    static final int FIELD_LIMIT = 500;
    /** The most characters of a documentation excerpt, which is bounded by construction. */
    static final int DOC_LIMIT = 800;

    private Redactor() {
    }

    static String clean(String text, int limit) {
        String masked = Masking.mask(text);
        if (masked.length() <= limit) {
            return masked;
        }
        return masked.substring(0, limit) + "…[truncated " + (masked.length() - limit) + " characters]";
    }

    /** A cleaned copy of the tree: every string leaf, every key, and every number of nine digits or more. */
    static JsonNode clean(JsonNode node, int limit) {
        JsonNodeFactory factory = JsonNodeFactory.instance;
        if (node == null || node.isNull() || node.isMissingNode()) {
            return factory.nullNode();
        }
        if (node.isObject()) {
            ObjectNode out = factory.objectNode();
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                out.set(Masking.mask(field.getKey()), clean(field.getValue(), limit));
            }
            return out;
        }
        if (node.isArray()) {
            ArrayNode out = factory.arrayNode();
            node.forEach(element -> out.add(clean(element, limit)));
            return out;
        }
        if (node.isTextual()) {
            return new TextNode(clean(node.asText(), limit));
        }
        if (node.isNumber()) {
            String text = node.asText();
            String masked = Masking.mask(text);
            return masked.equals(text) ? node.deepCopy() : new TextNode(masked);
        }
        return node.deepCopy();
    }
}

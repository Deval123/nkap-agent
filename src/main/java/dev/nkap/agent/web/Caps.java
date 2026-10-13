package dev.nkap.agent.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.util.Iterator;
import java.util.Map;

/** Cuts every string a response carries to a length, so no field of model or tool text can make a response large. */
final class Caps {

    static final int STRING_LIMIT = 2000;

    private Caps() {
    }

    static JsonNode limit(JsonNode node) {
        JsonNodeFactory factory = JsonNodeFactory.instance;
        if (node == null) {
            return factory.nullNode();
        }
        if (node.isObject()) {
            ObjectNode out = factory.objectNode();
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                out.set(field.getKey(), limit(field.getValue()));
            }
            return out;
        }
        if (node.isArray()) {
            ArrayNode out = factory.arrayNode();
            node.forEach(element -> out.add(limit(element)));
            return out;
        }
        if (node.isTextual() && node.asText().length() > STRING_LIMIT) {
            String text = node.asText();
            return new TextNode(text.substring(0, STRING_LIMIT) + "…[cut " + (text.length() - STRING_LIMIT) + " characters]");
        }
        return node.deepCopy();
    }
}

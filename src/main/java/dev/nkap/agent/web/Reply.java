package dev.nkap.agent.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** What a handler answers: a status, a body with its type, and the headers that are particular to it. */
record Reply(int status, byte[] body, String contentType, Map<String, String> headers) {

    private static final ObjectMapper JSON = new ObjectMapper();

    static Reply json(int status, JsonNode body) {
        return new Reply(status, Caps.limit(body).toString().getBytes(StandardCharsets.UTF_8), "application/json; charset=utf-8", Map.of());
    }

    /** An error is a code and a fixed text, never anything of the request or of the model. */
    static Reply error(int status, String code, String text) {
        ObjectNode node = JSON.createObjectNode();
        node.put("error", code);
        node.put("text", text);
        return json(status, node);
    }

    static Reply asset(byte[] bytes, String contentType) {
        return new Reply(200, bytes, contentType, Map.of());
    }

    Reply with(String header, String value) {
        Map<String, String> all = new java.util.LinkedHashMap<>(headers);
        all.put(header, value);
        return new Reply(status, body, contentType, all);
    }
}

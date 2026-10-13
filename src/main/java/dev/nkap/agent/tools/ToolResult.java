package dev.nkap.agent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Map;

/**
 * What one tool call returned, as it is stored and as a model sees it. Everything in it has been masked
 * and truncated; the raw answer is not kept. {@code status} is the gateway's HTTP status, or {@code 0}
 * when no answer exists (nothing was asked, or the gateway did not answer), in which case {@code fields}
 * carries an {@code error}. Errors are results, never exceptions.
 */
public record ToolResult(String callId, String tool, Map<String, String> arguments, int status, JsonNode fields) {

    private static final ObjectMapper JSON = new ObjectMapper();

    public ToolResult {
        arguments = Map.copyOf(arguments);
    }

    /** The JSON a model is given for this call. */
    public String toJson() {
        ObjectNode node = JSON.createObjectNode();
        node.put("callId", callId);
        node.put("tool", tool);
        ObjectNode args = node.putObject("arguments");
        arguments.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(e -> args.put(e.getKey(), e.getValue()));
        node.put("status", status);
        node.set("fields", fields);
        return node.toString();
    }

    /**
     * The block a model is shown: the fields, and only the fields, so that a path addresses exactly what is
     * on the page. The attributes are the harness's own. {@code <}, {@code >} and {@code &} in the body are
     * written as {@code \u003c}, {@code \u003e} and {@code \u0026}, which are the same JSON value, so no
     * text a tool returned can write a closing tag.
     */
    public String toBlock() {
        String body = fields.toString().replace("&", "\\u0026").replace("<", "\\u003c").replace(">", "\\u003e");
        return "<tool_result call=\"" + callId + "\" tool=\"" + tool + "\" status=\"" + status
                + "\" trust=\"untrusted\">" + body + "</tool_result>";
    }
}

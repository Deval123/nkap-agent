package dev.nkap.agent.tools;

import com.fasterxml.jackson.core.JsonPointer;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The record of one run's tool calls. Each call gets the next id, {@code c1}, {@code c2}, ..., and is
 * stored masked and truncated. {@link #verify} is the only way a claim becomes evidence: it accepts a
 * reference only if it points at a field of a recorded result that holds exactly the cited value.
 */
public final class Trace {

    private final List<ToolResult> results = new ArrayList<>();

    /** Records a call with the standard limit on string leaves. */
    public synchronized ToolResult record(String tool, Map<String, String> arguments, int status, JsonNode fields) {
        return record(tool, arguments, status, fields, Redactor.FIELD_LIMIT);
    }

    synchronized ToolResult record(String tool, Map<String, String> arguments, int status, JsonNode fields, int limit) {
        Map<String, String> cleanArguments = new LinkedHashMap<>();
        arguments.forEach((k, v) -> cleanArguments.put(k, Redactor.clean(v, Redactor.FIELD_LIMIT)));
        ToolResult result = new ToolResult("c" + (results.size() + 1), tool, cleanArguments, status,
                Redactor.clean(fields, limit));
        results.add(result);
        return result;
    }

    public synchronized List<ToolResult> results() {
        return List.copyOf(results);
    }

    public synchronized Optional<ToolResult> find(String callId) {
        return results.stream().filter(r -> r.callId().equals(callId)).findFirst();
    }

    /**
     * The value a pointer addresses in a recorded result, as text, or empty if the call, the path or the value
     * does not exist. The path is a JSON Pointer into the fields of the result, which are what the model sees.
     */
    public synchronized Optional<String> resolve(String callId, String path) {
        if (callId == null || path == null) {
            return Optional.empty();
        }
        Optional<ToolResult> result = find(callId);
        if (result.isEmpty()) {
            return Optional.empty();
        }
        JsonNode node;
        try {
            node = result.get().fields().at(JsonPointer.compile(path));
        } catch (IllegalArgumentException notAPointer) {
            return Optional.empty();
        }
        return node.isValueNode() && !node.isNull() ? Optional.of(node.asText()) : Optional.empty();
    }

    public synchronized Verification verify(EvidenceRef ref) {
        if (ref == null || ref.callId() == null || ref.path() == null || ref.value() == null) {
            return Verification.refused("a reference needs a call id, a path and a value");
        }
        Optional<ToolResult> result = find(ref.callId());
        if (result.isEmpty()) {
            return Verification.refused("unknown call id " + Redactor.clean(ref.callId(), 40));
        }
        JsonNode node;
        try {
            node = result.get().fields().at(JsonPointer.compile(ref.path()));
        } catch (IllegalArgumentException notAPointer) {
            return Verification.refused("the path is not a JSON Pointer");
        }
        if (node.isMissingNode()) {
            return Verification.refused("no such field in " + ref.callId());
        }
        if (!node.isValueNode()) {
            return Verification.refused("the path is not a field with a value");
        }
        if (!node.asText().equals(ref.value())) {
            return Verification.refused("value differs from what " + ref.callId() + " returned");
        }
        return Verification.accepted();
    }
}

package dev.nkap.agent.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.nkap.agent.trace.TraceReplay;

/**
 * The steps of a recording or a run in one shape: {@code index, type, delayMillis, recording, mode, data}. A step
 * says whether it is a recording and which mode made it, so the page cannot show one as the other.
 */
final class StepViews {

    private static final ObjectMapper JSON = new ObjectMapper();

    private StepViews() {
    }

    static ArrayNode of(JsonNode steps, String mode, boolean recording) {
        ArrayNode out = JSON.createArrayNode();
        for (int i = 0; i < steps.size(); i++) {
            JsonNode step = steps.get(i);
            ObjectNode view = out.addObject();
            view.put("index", i);
            view.put("type", step.path("type").asText());
            view.put("delayMillis", "model".equals(step.path("type").asText())
                    ? Math.min(TraceReplay.MAX_DELAY_MILLIS, Math.max(0, step.path("millis").asLong())) : 0);
            view.put("recording", recording);
            view.put("mode", mode);
            view.set("data", step);
        }
        return out;
    }
}

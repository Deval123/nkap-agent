package dev.nkap.agent.trace;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Yields a published trace's steps in order, with their recorded timings, so that a page can show them. It reads a
 * file and nothing else: it never calls a model or a gateway. Every step it yields is marked as a recording, and the
 * first carries the provenance, so a replay cannot be mistaken for a live run.
 *
 * <p>A model step waits as long as the call took, but never more than {@link #MAX_DELAY_MILLIS}, so that the page is
 * lively; the other steps do not wait. The outcome is the last step.
 */
public final class TraceReplay {

    public static final long MAX_DELAY_MILLIS = 2000;

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> TYPES = Set.of("task", "model", "tool", "harness", "outcome");

    /** One step of a replay. {@code provenance} is set on the first only. {@code recording} is always true. */
    public record Step(int index, String type, long delayMillis, boolean recording, JsonNode data, JsonNode provenance) {
    }

    private TraceReplay() {
    }

    public static List<Step> read(Path file) throws IOException {
        return steps(JSON.readTree(Files.readString(file)));
    }

    public static List<Step> steps(JsonNode trace) {
        if (trace == null || !trace.isObject() || !TraceExport.FORMAT.equals(trace.path("format").asText())
                || !trace.path("recording").asBoolean(false) || !trace.path("provenance").isObject() || !trace.path("steps").isArray()
                || trace.get("steps").isEmpty()) {
            throw new IllegalArgumentException("this is not a published trace");
        }
        JsonNode raw = trace.get("steps");
        List<Step> steps = new ArrayList<>();
        for (int i = 0; i < raw.size(); i++) {
            JsonNode step = raw.get(i);
            String type = step.path("type").asText("");
            if (!TYPES.contains(type) || type.equals("outcome") != (i == raw.size() - 1)) {
                throw new IllegalArgumentException("a trace's steps end with exactly one outcome, and hold only known steps");
            }
            long delay = type.equals("model") ? Math.min(MAX_DELAY_MILLIS, Math.max(0, step.path("millis").asLong())) : 0;
            steps.add(new Step(i, type, delay, true, step, i == 0 ? trace.get("provenance") : null));
        }
        return steps;
    }
}

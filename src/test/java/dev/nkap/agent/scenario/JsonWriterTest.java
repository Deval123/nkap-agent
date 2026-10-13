package dev.nkap.agent.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class JsonWriterTest {

    @Test
    @DisplayName("an object is written with two-space indentation, keys in insertion order, and one final newline")
    void layout() {
        Map<String, Object> inner = new LinkedHashMap<>();
        inner.put("z", 1L);
        inner.put("a", List.of("x", "y"));
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("name", "n");
        root.put("inner", inner);
        root.put("none", List.of());
        root.put("empty", Map.of());

        assertThat(JsonWriter.write(root)).isEqualTo("""
                {
                  "name": "n",
                  "inner": {
                    "z": 1,
                    "a": [
                      "x",
                      "y"
                    ]
                  },
                  "none": [],
                  "empty": {}
                }
                """);
    }

    @Test
    @DisplayName("quotes, backslashes and control characters in a string are escaped so the output parses back to the same text")
    void escaping() throws Exception {
        String nasty = "say \"hi\" \\ and\nnew line\ttab \u0001 done";
        String json = JsonWriter.write(Map.of("k", nasty));
        JsonNode parsed = new ObjectMapper().readTree(json);
        assertThat(parsed.get("k").asText()).isEqualTo(nasty);
        assertThat(json).doesNotContain("\t").doesNotContain("\u0001");
    }

    @Test
    @DisplayName("non-ASCII text is written as it is, not as escapes, so the file is plain UTF-8")
    void unicode() {
        assertThat(JsonWriter.write(Map.of("k", "é"))).contains("\"é\"");
    }

    @Test
    @DisplayName("a value of a type the writer does not know is refused rather than written as its toString")
    void unknownType() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> JsonWriter.write(Map.of("k", new Object())));
    }
}

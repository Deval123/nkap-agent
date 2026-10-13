package dev.nkap.agent.tools;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DocsSourceTest {

    private static String resource(String name) throws IOException {
        try (InputStream in = DocsSourceTest.class.getResourceAsStream("/docs/providers/" + name)) {
            assertThat(in).as(name).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static Map<String, String> recordedHashes(String source) {
        Map<String, String> hashes = new LinkedHashMap<>();
        for (String line : source.lines().toList()) {
            Matcher m = Pattern.compile("sha256 ([0-9a-f]{64})  (\\S+)").matcher(line);
            if (m.matches()) {
                hashes.put(m.group(2), m.group(1));
            }
        }
        return hashes;
    }

    @Test
    @DisplayName("every copied page matches the SHA-256 recorded for it, so a page edited by hand fails here")
    void hashesMatch() throws Exception {
        Map<String, String> recorded = recordedHashes(resource("SOURCE"));
        assertThat(recorded).containsOnlyKeys("m-pesa.md", "mtn.md", "orange-money.md");
        for (Map.Entry<String, String> entry : recorded.entrySet()) {
            byte[] bytes = resource(entry.getKey()).getBytes(StandardCharsets.UTF_8);
            String actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            assertThat(actual).as(entry.getKey()).isEqualTo(entry.getValue());
        }
    }

    @Test
    @DisplayName("the recorded commit is the one compose.yaml builds the stack from")
    void sameCommitAsTheStack() throws IOException {
        Matcher stack = Pattern.compile("nkap\\.git#([0-9a-f]{40})").matcher(Files.readString(Path.of("compose.yaml")));
        assertThat(stack.find()).isTrue();
        Matcher source = Pattern.compile("(?m)^commit ([0-9a-f]{40})$").matcher(resource("SOURCE"));
        assertThat(source.find()).isTrue();
        assertThat(source.group(1)).isEqualTo(stack.group(1));
    }

    @Test
    @DisplayName("the pages the index reads are exactly the pages the SOURCE file lists")
    void indexReadsListedPages() throws IOException {
        assertThat(recordedHashes(resource("SOURCE")).keySet().stream().sorted().toList())
                .isEqualTo(List.copyOf(DocsIndex.bundled().files()));
    }
}

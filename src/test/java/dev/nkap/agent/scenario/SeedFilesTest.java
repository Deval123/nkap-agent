package dev.nkap.agent.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SeedFilesTest {

    @TempDir
    Path tmp;

    @Test
    @DisplayName("the case list, one simulator document and one statement per case that has lines are written, and nothing else")
    void fileSet() throws IOException {
        Path dir = Files.createDirectory(tmp.resolve("a"));
        SeedFiles.write(dir);
        assertThat(names(dir)).containsExactly(
                "cases.json", "duplicate-posting.csv", "money-without-owner.csv", "self-approval.csv",
                "simulator.json", "unrecorded-fee.csv");
    }

    @Test
    @DisplayName("generating twice in two directories gives byte-identical files")
    void determinism() throws IOException {
        Path one = Files.createDirectory(tmp.resolve("one"));
        Path two = Files.createDirectory(tmp.resolve("two"));
        SeedFiles.write(one);
        SeedFiles.write(two);
        for (String name : names(one)) {
            assertThat(Files.readAllBytes(two.resolve(name))).as(name).isEqualTo(Files.readAllBytes(one.resolve(name)));
        }
        assertThat(names(two)).isEqualTo(names(one));
    }

    @Test
    @DisplayName("files are UTF-8 without a byte order mark, with LF endings and a final newline")
    void encoding() throws IOException {
        Path dir = Files.createDirectory(tmp.resolve("enc"));
        SeedFiles.write(dir);
        for (String name : names(dir)) {
            byte[] bytes = Files.readAllBytes(dir.resolve(name));
            assertThat(bytes.length).as(name).isPositive();
            assertThat(bytes[0]).as(name).isNotEqualTo((byte) 0xEF);
            String text = new String(bytes, StandardCharsets.UTF_8);
            assertThat(text).as(name).doesNotContain("\r").endsWith("\n");
        }
    }

    @Test
    @DisplayName("the files do not depend on the time zone or the locale of the machine")
    void environmentIndependent() throws IOException {
        java.util.TimeZone zone = java.util.TimeZone.getDefault();
        java.util.Locale locale = java.util.Locale.getDefault();
        try {
            Path base = Files.createDirectory(tmp.resolve("base"));
            SeedFiles.write(base);
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Pacific/Kiritimati"));
            java.util.Locale.setDefault(java.util.Locale.forLanguageTag("ar-EG"));
            Path other = Files.createDirectory(tmp.resolve("other"));
            SeedFiles.write(other);
            for (String name : names(base)) {
                assertThat(Files.readAllBytes(other.resolve(name))).as(name).isEqualTo(Files.readAllBytes(base.resolve(name)));
            }
        } finally {
            java.util.TimeZone.setDefault(zone);
            java.util.Locale.setDefault(locale);
        }
    }

    @Test
    @DisplayName("a directory that already holds anything is refused and left untouched")
    void refusesNonEmpty() throws IOException {
        Path dir = Files.createDirectory(tmp.resolve("full"));
        Files.writeString(dir.resolve("keep.txt"), "mine");
        assertThatThrownBy(() -> SeedFiles.write(dir)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not empty");
        assertThat(names(dir)).containsExactly("keep.txt");
    }

    @Test
    @DisplayName("a directory that does not exist is refused")
    void refusesMissing() {
        assertThatThrownBy(() -> SeedFiles.write(tmp.resolve("nope"))).isInstanceOf(IllegalArgumentException.class);
    }

    private static List<String> names(Path dir) throws IOException {
        try (Stream<Path> s = Files.list(dir)) {
            return s.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }
}

package dev.nkap.agent.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The checked-in files are what the generator must produce, byte for byte. A change to the catalogue or to
 * a writer that moves any byte fails here; the fix is to regenerate the golden files on purpose and read the
 * diff: {@code mvn -q compile && java -cp target/classes dev.nkap.agent.scenario.SeedFiles <empty dir>}, then copy
 * the files into {@code src/test/resources/golden/}.
 */
class GoldenFilesTest {

    private static final List<String> GOLDEN = List.of(
            "duplicate-posting.csv", "money-without-owner.csv", "self-approval.csv",
            "simulator.json", "unrecorded-fee.csv");

    @TempDir
    Path tmp;

    @Test
    @DisplayName("the generated files equal the checked-in golden files byte for byte, and there are no others")
    void golden() throws IOException {
        SeedFiles.write(tmp);
        try (Stream<Path> files = Files.list(tmp)) {
            assertThat(files.map(p -> p.getFileName().toString()).sorted().toList()).isEqualTo(GOLDEN);
        }
        for (String name : GOLDEN) {
            try (InputStream in = getClass().getResourceAsStream("/golden/" + name)) {
                assertThat(in).as("golden/" + name).isNotNull();
                assertThat(Files.readAllBytes(tmp.resolve(name))).as(name).isEqualTo(in.readAllBytes());
            }
        }
    }
}

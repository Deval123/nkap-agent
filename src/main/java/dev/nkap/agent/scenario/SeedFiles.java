package dev.nkap.agent.scenario;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Writes the files that describe the cases, from {@link Catalogue} and nothing else: {@code simulator.json}
 * and one {@code <case-id>.csv} for each case that has statement lines. The same catalogue gives the same
 * bytes on any machine and on any day: UTF-8 without a byte order mark, LF endings, no clock.
 *
 * <p>Usage: {@code SeedFiles <directory>}. The directory must exist and be empty.
 */
public final class SeedFiles {

    static final String SIMULATOR_FILE = "simulator.json";

    private SeedFiles() {
    }

    /** Writes the files into {@code dir} and returns their paths, sorted by file name. */
    public static List<Path> write(Path dir) {
        if (!Files.isDirectory(dir)) {
            throw new IllegalArgumentException(dir + " is not a directory");
        }
        try (Stream<Path> existing = Files.list(dir)) {
            if (existing.findAny().isPresent()) {
                throw new IllegalStateException(dir + " is not empty; refusing to write into it");
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        List<ScenarioCase> cases = Catalogue.all().stream().sorted(Comparator.comparing(ScenarioCase::id)).toList();
        List<Path> written = new ArrayList<>();
        written.add(put(dir, SIMULATOR_FILE, SimulatorDocument.render(cases)));
        for (ScenarioCase c : cases) {
            if (!c.lines().isEmpty()) {
                written.add(put(dir, c.id() + ".csv", StatementCsv.render(c)));
            }
        }
        written.sort(Comparator.comparing(p -> p.getFileName().toString()));
        return List.copyOf(written);
    }

    private static Path put(Path dir, String name, String content) {
        try {
            return Files.write(dir.resolve(name), content.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static void main(String[] args) {
        if (args.length != 1) {
            System.err.println("usage: SeedFiles <empty directory>");
            System.exit(2);
        }
        write(Path.of(args[0])).forEach(p -> System.out.println(p));
    }
}

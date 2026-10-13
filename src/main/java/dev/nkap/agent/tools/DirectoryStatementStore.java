package dev.nkap.agent.tools;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Optional;
import java.util.UUID;

/**
 * Reads {@code dir/<importId>.csv}, where the name is built from the parsed UUID alone. A symlink, a directory
 * and anything else that is not a regular file reads nothing and is "not found"; a file over
 * {@value #MAX_BYTES} bytes is refused, not cut. It only reads: nothing here writes, and nothing here touches
 * the network.
 */
public final class DirectoryStatementStore implements StatementStore {

    /** Larger than any statement the demonstration holds; the importer itself sets no limit. */
    public static final int MAX_BYTES = 64 * 1024;

    private final Path dir;

    public DirectoryStatementStore(Path dir) {
        this.dir = dir;
    }

    @Override
    public Optional<String> find(UUID importId) {
        Path file = dir.resolve(importId + ".csv");
        try {
            BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isRegularFile()) {
                return Optional.empty();
            }
            if (attributes.size() > MAX_BYTES) {
                throw new StatementRefusedException("the statement file is larger than " + MAX_BYTES + " bytes");
            }
            try (InputStream in = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
                byte[] bytes = in.readNBytes(MAX_BYTES + 1);
                if (bytes.length > MAX_BYTES) {
                    throw new StatementRefusedException("the statement file is larger than " + MAX_BYTES + " bytes");
                }
                return Optional.of(new String(bytes, StandardCharsets.UTF_8));
            }
        } catch (IOException notReadable) {
            // missing, a symlink refused by NOFOLLOW_LINKS, no permission: nothing readable under that name
            return Optional.empty();
        }
    }

    @Override
    public String toString() {
        return "DirectoryStatementStore[" + dir + "]";
    }
}

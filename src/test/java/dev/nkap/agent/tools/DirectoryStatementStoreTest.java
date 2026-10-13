package dev.nkap.agent.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DirectoryStatementStoreTest {

    private static final UUID ID = UUID.fromString("16de24bf-b9fe-425d-a28e-b0d4d57912b4");

    @TempDir
    Path dir;
    @TempDir
    Path elsewhere;

    private DirectoryStatementStore store;

    @BeforeEach
    void open() {
        store = new DirectoryStatementStore(dir);
    }

    @Test
    @DisplayName("the file is dir/<uuid>.csv, built from the parsed UUID alone")
    void reads() throws IOException {
        Files.writeString(dir.resolve(ID + ".csv"), "hello");
        assertThat(store.find(ID)).contains("hello");
    }

    @Test
    @DisplayName("a missing file is not found, not an error")
    void missing() {
        assertThat(store.find(ID)).isEmpty();
    }

    @Test
    @DisplayName("a symlink named <uuid>.csv that points at another file reads nothing, even a file that exists")
    void symlink() throws IOException {
        Path secret = elsewhere.resolve("secret.txt");
        Files.writeString(secret, "do not read");
        Files.createSymbolicLink(dir.resolve(ID + ".csv"), secret);
        assertThat(store.find(ID)).isEmpty();
    }

    @Test
    @DisplayName("a directory with the right name, and a file that is not regular, read nothing")
    void notRegular() throws IOException {
        Files.createDirectory(dir.resolve(ID + ".csv"));
        assertThat(store.find(ID)).isEmpty();
    }

    @Test
    @DisplayName("a file over 64 KB is refused, not read, and not cut: 64 KB exactly is read")
    void oversized() throws IOException {
        assertThat(DirectoryStatementStore.MAX_BYTES).isEqualTo(64 * 1024);
        Files.write(dir.resolve(ID + ".csv"), new byte[DirectoryStatementStore.MAX_BYTES]);
        assertThat(store.find(ID)).isPresent();
        Files.write(dir.resolve(ID + ".csv"), new byte[DirectoryStatementStore.MAX_BYTES + 1]);
        assertThatThrownBy(() -> store.find(ID)).isInstanceOf(StatementRefusedException.class);
    }

    @Test
    @DisplayName("non-UTF-8 bytes are decoded with a replacement character")
    void decodes() throws IOException {
        Files.write(dir.resolve(ID + ".csv"), new byte[] {'a', (byte) 0xff, 'b'});
        assertThat(store.find(ID)).contains("a�b");
    }

    @Test
    @DisplayName("the store has no way to be asked for a path: its one method takes a UUID")
    void takesAUuid() throws Exception {
        assertThat(StatementStore.class.getMethod("find", UUID.class)).isNotNull();
        assertThat(StatementStore.class.getMethods()).hasSize(1);
    }
}

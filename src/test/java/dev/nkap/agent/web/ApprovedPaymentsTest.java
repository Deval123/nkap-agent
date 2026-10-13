package dev.nkap.agent.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ApprovedPaymentsTest {

    private static final String PAYMENT = "0985e8df-93aa-4949-b7aa-185e19ea41d0";
    private static final UUID ADJUSTMENT = UUID.fromString("5d3c1f0e-6a51-4c2b-9d7e-1f2a3b4c5d6e");

    @TempDir
    Path dir;

    private ApprovedPayments store() {
        return new ApprovedPayments(dir.resolve("state/approved-payments.json"));
    }

    @Test
    @DisplayName("a payment with no approved adjustment on record is known to have none; one that was recorded is found, also after a restart")
    void recordAndLookup() {
        ApprovedPayments store = store();
        assertThat(store.lookup(PAYMENT).available()).isTrue();
        assertThat(store.lookup(PAYMENT).adjustment()).isEmpty();
        assertThat(store.record(PAYMENT, ADJUSTMENT)).isTrue();
        assertThat(store().lookup(PAYMENT).adjustment()).contains(ADJUSTMENT);
        assertThat(store().lookup("11111111-1111-4111-8111-111111111111").adjustment()).isEmpty();
    }

    @Test
    @DisplayName("an entry can be forgotten, when Nkap says the adjustment it names is no longer approved")
    void forget() {
        ApprovedPayments store = store();
        store.record(PAYMENT, ADJUSTMENT);
        assertThat(store.forget(PAYMENT)).isTrue();
        assertThat(store().lookup(PAYMENT).adjustment()).isEmpty();
    }

    @Test
    @DisplayName("a file that is corrupt or unreadable fails closed: nothing is known, nothing is written, and the file is left as it was")
    void corruptFailsClosed() throws IOException {
        Path file = dir.resolve("state/approved-payments.json");
        Files.createDirectories(file.getParent());
        for (String bad : List.of("garbage", "", "[]", "{}", "{\"payments\":[]}", "{\"payments\":{\"not-a-uuid\":\"" + ADJUSTMENT + "\"}}",
                "{\"payments\":{\"" + PAYMENT + "\":\"x\"}}", "{\"payments\":{\"" + PAYMENT + "\":5}}",
                "{\"payments\":{},\"extra\":1}", "{\"payments\":{}} trailing")) {
            Files.writeString(file, bad);
            ApprovedPayments store = store();
            assertThat(store.lookup(PAYMENT).available()).as(bad).isFalse();
            assertThat(store.record(PAYMENT, ADJUSTMENT)).as(bad).isFalse();
            assertThat(store.forget(PAYMENT)).as(bad).isFalse();
            assertThat(Files.readString(file)).as("left as it was").isEqualTo(bad);
        }
        Files.delete(file);
        Files.createDirectory(file);
        assertThat(store().lookup(PAYMENT).available()).as("a directory where the file should be").isFalse();
    }

    @Test
    @DisplayName("the file holds payment references and adjustment ids and nothing else, is private, and is replaced whole")
    void fileContent() throws IOException {
        ApprovedPayments store = store();
        store.record(PAYMENT, ADJUSTMENT);
        Path file = dir.resolve("state/approved-payments.json");
        assertThat(Files.readString(file)).isEqualTo("{\"payments\":{\"" + PAYMENT + "\":\"" + ADJUSTMENT + "\"}}\n");
        assertThat(Files.getPosixFilePermissions(file)).hasSize(2);
        try (var files = Files.list(file.getParent())) {
            assertThat(files.map(p -> p.getFileName().toString()).toList()).containsExactly("approved-payments.json");
        }
    }

    @Test
    @DisplayName("a reference or an id that is not a UUID is not recorded")
    void onlyUuids() {
        ApprovedPayments store = store();
        assertThat(store.record("../x", ADJUSTMENT)).isFalse();
        assertThat(store.record(null, ADJUSTMENT)).isFalse();
        assertThat(store.record(PAYMENT, null)).isFalse();
        assertThat(store.lookup("../x").adjustment()).isEmpty();
    }

    @Test
    @DisplayName("concurrent records all land: none overwrites another")
    void concurrentRecords() throws Exception {
        ApprovedPayments store = store();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Future<Boolean>> results = new ArrayList<>();
        List<String> payments = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            String payment = UUID.randomUUID().toString();
            payments.add(payment);
            results.add(pool.submit(() -> store.record(payment, UUID.randomUUID())));
        }
        for (Future<Boolean> f : results) {
            assertThat(f.get()).isTrue();
        }
        pool.shutdownNow();
        for (String payment : payments) {
            assertThat(store().lookup(payment).adjustment()).as(payment).isPresent();
        }
    }
}

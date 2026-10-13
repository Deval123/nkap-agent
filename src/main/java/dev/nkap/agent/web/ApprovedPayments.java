package dev.nkap.agent.web;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Which payments this service has seen Nkap approve an adjustment for, and which adjustment: a small file of payment
 * references and adjustment ids and nothing else. Nkap accepts and approves a second adjustment for the same payment
 * and writes a second ledger entry; this record is how the service declines to send that second approval.
 *
 * <p>Nkap stays the source of truth: the caller asks Nkap whether the adjustment named here is still approved, and
 * {@link #forget}s the entry if it is not. Nkap's adjustment routes cannot be asked "which adjustments does this
 * payment have": {@code GET /adjustments} lists every one, with no filter, and its answer outgrows the clients'
 * 64 KB cap after a few dozen proposals.
 *
 * <p>It fails closed, as {@code LiveBudget} does: a file that is corrupt or cannot be read or written makes
 * {@link #lookup} say it does not know, and {@link #record} and {@link #forget} do nothing; the file is left as it was.
 * It is replaced whole by an atomic move. An approval made by anyone else is not in it.
 */
final class ApprovedPayments {

    /** What the file says about a payment: whether it could be read, and the approved adjustment if there is one. */
    record Lookup(boolean available, Optional<UUID> adjustment) {
    }

    private static final ObjectMapper JSON = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final String UUID_RE = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";

    private final Path file;

    ApprovedPayments(Path file) {
        this.file = file;
    }

    synchronized Lookup lookup(String payment) {
        Map<String, String> all = read();
        if (all == null) {
            return new Lookup(false, Optional.empty());
        }
        String id = payment == null ? null : all.get(payment.toLowerCase());
        return new Lookup(true, id == null ? Optional.empty() : Optional.of(UUID.fromString(id)));
    }

    /** Notes that Nkap approved {@code adjustment} for {@code payment}. False if it could not be written. */
    synchronized boolean record(String payment, UUID adjustment) {
        if (payment == null || adjustment == null || !payment.matches(UUID_RE)) {
            return false;
        }
        Map<String, String> all = read();
        if (all == null) {
            return false;
        }
        all.put(payment.toLowerCase(), adjustment.toString());
        return write(all);
    }

    synchronized boolean forget(String payment) {
        Map<String, String> all = read();
        if (all == null) {
            return false;
        }
        all.remove(payment == null ? "" : payment.toLowerCase());
        return write(all);
    }

    /** The entries, an empty map if there is no file, or null if the file cannot be trusted. */
    private Map<String, String> read() {
        if (!Files.exists(file)) {
            return new TreeMap<>();
        }
        try {
            JsonNode node = JSON.readTree(Files.readString(file, StandardCharsets.UTF_8));
            if (node == null || !node.isObject() || node.size() != 1 || !node.path("payments").isObject()) {
                return null;
            }
            Map<String, String> all = new TreeMap<>();
            Iterator<Map.Entry<String, JsonNode>> fields = node.get("payments").fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                if (!entry.getKey().matches(UUID_RE) || !entry.getValue().isTextual() || !entry.getValue().asText().matches(UUID_RE)) {
                    return null;
                }
                all.put(entry.getKey().toLowerCase(), entry.getValue().asText().toLowerCase());
            }
            return all;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private boolean write(Map<String, String> all) {
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            ObjectNode node = JSON.createObjectNode();
            ObjectNode payments = node.putObject("payments");
            all.forEach(payments::put);
            Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(temp, node + "\n", StandardCharsets.UTF_8);
            try {
                Files.setPosixFilePermissions(temp, PosixFilePermissions.fromString("rw-------"));
            } catch (UnsupportedOperationException notPosix) {
                // nothing to set
            }
            try {
                Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }
}

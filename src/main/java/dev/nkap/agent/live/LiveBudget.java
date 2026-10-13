package dev.nkap.agent.live;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.function.Supplier;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Limits live runs by code: a cap per UTC day, and a cooldown per visitor. The service sets both from its
 * configuration; a visitor cannot.
 *
 * <p>The state is one small JSON file, so a restart does not reset it: the day, the count, and for each visitor
 * the time of the last granted run. A visitor is the HMAC-SHA256 of what the service can see of the client, keyed
 * with a secret salt, in hex. Neither the client's address nor the salt is written, logged or returned.
 *
 * <p>It fails closed. A state file that is corrupt or cannot be read or written, a missing or blank salt, and a
 * blank visitor all refuse the run as {@code unavailable}; a corrupt file is left as it was for someone to look
 * at. A refused request does not use up the cap. The file is updated under a lock and replaced atomically, so
 * concurrent callers cannot grant more than the cap.
 */
public final class LiveBudget {

    public static final int DEFAULT_DAILY_CAP = 40;
    public static final Duration DEFAULT_COOLDOWN = Duration.ofMinutes(5);

    private static final System.Logger LOG = System.getLogger("dev.nkap.agent.live");
    private static final ObjectMapper JSON = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    /** The answer to a request for a live run. */
    public sealed interface Decision {

        record Granted(int remainingToday) implements Decision {
        }

        /** {@code reason} is {@code cap-reached}, {@code cooldown} or {@code unavailable}; {@code text} is fixed. */
        record Refused(String reason, String text, Duration retryAfter) implements Decision {
        }
    }

    private record State(String day, int count, Map<String, Long> visitors) {
    }

    private final Path file;
    private final int dailyCap;
    private final Duration cooldown;
    private final Supplier<String> salt;
    private final Clock clock;

    public LiveBudget(Path file, int dailyCap, Duration cooldown, Supplier<String> salt, Clock clock) {
        if (dailyCap < 1) {
            throw new IllegalArgumentException("the daily cap is at least one");
        }
        if (cooldown == null || cooldown.isNegative()) {
            throw new IllegalArgumentException("the cooldown is not negative");
        }
        this.file = file;
        this.dailyCap = dailyCap;
        this.cooldown = cooldown;
        this.salt = salt;
        this.clock = clock;
    }

    public synchronized Decision tryAcquire(String visitorKey) {
        String visitor = hash(visitorKey);
        if (visitor == null) {
            return unavailable();
        }
        Path lock = file.resolveSibling(file.getFileName() + ".lock");
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            try (FileChannel channel = FileChannel.open(lock, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                    var held = channel.lock()) {
                return decide(visitor);
            }
        } catch (IOException | RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "live budget unavailable ({0})", e.getClass().getSimpleName());
            return unavailable();
        }
    }

    private Decision decide(String visitor) throws IOException {
        Instant now = clock.instant();
        LocalDate today = LocalDate.ofInstant(now, ZoneOffset.UTC);
        State state = read(today);
        if (state == null) {
            return unavailable();
        }
        int count = state.day().equals(today.toString()) ? state.count() : 0;
        Map<String, Long> visitors = new HashMap<>();
        state.visitors().forEach((hash, at) -> {
            if (cooldown.compareTo(Duration.between(Instant.ofEpochMilli(at), now)) > 0) {
                visitors.put(hash, at);
            }
        });
        if (count >= dailyCap) {
            Duration untilTomorrow = Duration.between(now, today.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC));
            return new Decision.Refused("cap-reached", "Today's live runs are used up. The recorded runs are still there.", untilTomorrow);
        }
        Long last = visitors.get(visitor);
        if (last != null) {
            Duration left = cooldown.minus(Duration.between(Instant.ofEpochMilli(last), now));
            return new Decision.Refused("cooldown", "A live run was started from here a moment ago. Wait a little, or play a recording.", left);
        }
        count++;
        if (!cooldown.isZero()) {
            visitors.put(visitor, now.toEpochMilli());
        }
        write(new State(today.toString(), count, visitors));
        return new Decision.Granted(dailyCap - count);
    }

    /** The state, a fresh one if there is no file, or null if the file cannot be trusted. */
    private State read(LocalDate today) throws IOException {
        if (!Files.exists(file)) {
            return new State(today.toString(), 0, Map.of());
        }
        JsonNode node;
        try {
            node = JSON.readTree(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException corrupt) {
            if (Files.isRegularFile(file)) {
                LOG.log(System.Logger.Level.WARNING, "live budget state file is corrupt");
                return null;
            }
            throw corrupt instanceof IOException io ? io : new IOException("unreadable");
        }
        if (node == null || !node.isObject() || node.size() != 3 || !node.path("day").isTextual() || !node.path("count").isInt()
                || node.get("count").asInt() < 0 || !node.path("visitors").isObject()) {
            LOG.log(System.Logger.Level.WARNING, "live budget state file is corrupt");
            return null;
        }
        try {
            LocalDate.parse(node.get("day").asText());
        } catch (DateTimeParseException e) {
            return null;
        }
        Map<String, Long> visitors = new HashMap<>();
        Iterator<Map.Entry<String, JsonNode>> fields = node.get("visitors").fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            if (!entry.getKey().matches("[0-9a-f]{64}") || !entry.getValue().isIntegralNumber() || !entry.getValue().canConvertToLong()) {
                return null;
            }
            visitors.put(entry.getKey(), entry.getValue().asLong());
        }
        return new State(node.get("day").asText(), node.get("count").asInt(), visitors);
    }

    private void write(State state) throws IOException {
        ObjectNode node = JSON.createObjectNode();
        node.put("day", state.day());
        node.put("count", state.count());
        ObjectNode visitors = node.putObject("visitors");
        state.visitors().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e -> visitors.put(e.getKey(), e.getValue()));
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
    }

    private String hash(String visitorKey) {
        if (visitorKey == null || visitorKey.isBlank()) {
            return null;
        }
        String secret;
        try {
            secret = salt.get();
        } catch (RuntimeException e) {
            return null;
        }
        if (secret == null || secret.isBlank()) {
            return null;
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal(visitorKey.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (java.security.GeneralSecurityException e) {
            return null;
        }
    }

    private static Decision unavailable() {
        return new Decision.Refused("unavailable", "Live runs are not available right now. The recorded runs are.", Duration.ZERO);
    }

    @Override
    public String toString() {
        return "LiveBudget[cap=" + dailyCap + ", cooldown=" + cooldown + "]";
    }
}

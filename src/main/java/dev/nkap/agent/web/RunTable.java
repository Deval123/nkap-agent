package dev.nkap.agent.web;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The runs the service remembers, in memory only: an id of 128 random bits in hex, kept for a time and at most so
 * many at once, the oldest forgotten first. The id is the only way to a run, so a visitor can act on a run they
 * started and cannot name any other.
 */
final class RunTable<T> {

    private static final SecureRandom RANDOM = new SecureRandom();

    private record Entry<T>(T value, Instant at) {
    }

    private final int max;
    private final Duration ttl;
    private final Clock clock;
    private final Map<String, Entry<T>> entries = new LinkedHashMap<>();

    RunTable(int max, Duration ttl, Clock clock) {
        this.max = max;
        this.ttl = ttl;
        this.clock = clock;
    }

    synchronized String put(T value) {
        forgetExpired();
        while (entries.size() >= max) {
            entries.remove(entries.keySet().iterator().next());
        }
        byte[] bytes = new byte[16];
        String id;
        do {
            RANDOM.nextBytes(bytes);
            StringBuilder hex = new StringBuilder();
            for (byte b : bytes) {
                hex.append(String.format("%02x", b));
            }
            id = hex.toString();
        } while (entries.containsKey(id));
        entries.put(id, new Entry<>(value, clock.instant()));
        return id;
    }

    synchronized Optional<T> get(String id) {
        forgetExpired();
        if (id == null || !id.matches("[0-9a-f]{32}")) {
            return Optional.empty();
        }
        Entry<T> entry = entries.get(id);
        return entry == null ? Optional.empty() : Optional.of(entry.value());
    }

    synchronized int size() {
        forgetExpired();
        return entries.size();
    }

    private void forgetExpired() {
        Instant now = clock.instant();
        entries.values().removeIf(e -> Duration.between(e.at(), now).compareTo(ttl) >= 0);
    }
}

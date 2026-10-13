package dev.nkap.agent.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RunTableTest {

    private final MovingClock clock = new MovingClock("2026-10-14T09:00:00Z");

    @Test
    @DisplayName("a run id is 128 random bits in hex, different every time, and finds what was put under it")
    void ids() {
        RunTable<String> table = new RunTable<>(50, Duration.ofMinutes(30), clock);
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < 40; i++) {
            String id = table.put("run " + i);
            assertThat(id).matches("[0-9a-f]{32}");
            assertThat(table.get(id)).contains("run " + i);
            ids.add(id);
        }
        assertThat(ids).hasSize(40);
    }

    @Test
    @DisplayName("an id that was never given, or is not in the form of one, finds nothing")
    void guesses() {
        RunTable<String> table = new RunTable<>(50, Duration.ofMinutes(30), clock);
        table.put("x");
        for (String guess : new String[] {"", "0".repeat(32), "../x", "A".repeat(32), "g".repeat(32), "0".repeat(31), "0".repeat(33), null}) {
            assertThat(table.get(guess)).as(String.valueOf(guess)).isEmpty();
        }
    }

    @Test
    @DisplayName("a run is kept for 30 minutes and then forgotten")
    void expiry() {
        RunTable<String> table = new RunTable<>(50, Duration.ofMinutes(30), clock);
        String id = table.put("x");
        clock.advance(Duration.ofMinutes(29));
        assertThat(table.get(id)).contains("x");
        clock.advance(Duration.ofMinutes(2));
        assertThat(table.get(id)).isEmpty();
        assertThat(table.size()).isZero();
    }

    @Test
    @DisplayName("at most 50 runs are kept: the oldest is forgotten first")
    void bounded() {
        RunTable<Integer> table = new RunTable<>(50, Duration.ofMinutes(30), clock);
        String first = table.put(0);
        for (int i = 1; i <= 50; i++) {
            clock.advance(Duration.ofSeconds(1));
            table.put(i);
        }
        assertThat(table.size()).isEqualTo(50);
        assertThat(table.get(first)).isEmpty();
    }
}

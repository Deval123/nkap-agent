package dev.nkap.agent.live;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LiveBudgetTest {

    /** Obviously not a secret. */
    private static final String SALT = "TEST-ONLY-SALT-6d1e";

    /** A clock a test moves. */
    static final class MovingClock extends Clock {
        private volatile Instant now;

        MovingClock(String start) {
            now = Instant.parse(start);
        }

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @TempDir
    Path dir;

    private final MovingClock clock = new MovingClock("2026-10-14T09:00:00Z");

    private LiveBudget budget(int cap, Duration cooldown) {
        return new LiveBudget(dir.resolve("state/budget.json"), cap, cooldown, () -> SALT, clock);
    }

    private static boolean granted(LiveBudget.Decision d) {
        return d instanceof LiveBudget.Decision.Granted;
    }

    private static LiveBudget.Decision.Refused refused(LiveBudget.Decision d) {
        assertThat(d).isInstanceOf(LiveBudget.Decision.Refused.class);
        return (LiveBudget.Decision.Refused) d;
    }

    @Test
    @DisplayName("live runs are granted until the daily cap, then stopped with a fixed text and the time until the next day")
    void capStopsLiveRuns() {
        LiveBudget budget = budget(3, Duration.ZERO);
        for (int i = 0; i < 3; i++) {
            assertThat(granted(budget.tryAcquire("visitor-" + i))).as("run " + i).isTrue();
        }
        LiveBudget.Decision.Refused refusal = refused(budget.tryAcquire("visitor-9"));
        assertThat(refusal.reason()).isEqualTo("cap-reached");
        assertThat(refusal.text()).isNotBlank();
        assertThat(refusal.retryAfter()).isEqualTo(Duration.ofHours(15));
        assertThat(refused(budget.tryAcquire("visitor-0")).reason()).as("a repeat visitor too").isEqualTo("cap-reached");
    }

    @Test
    @DisplayName("a second run from the same visitor inside the cooldown is refused with the time left, and does not use up the cap")
    void cooldown() {
        LiveBudget budget = budget(10, Duration.ofMinutes(5));
        assertThat(granted(budget.tryAcquire("a"))).isTrue();
        clock.advance(Duration.ofMinutes(2));
        LiveBudget.Decision.Refused refusal = refused(budget.tryAcquire("a"));
        assertThat(refusal.reason()).isEqualTo("cooldown");
        assertThat(refusal.retryAfter()).isEqualTo(Duration.ofMinutes(3));
        assertThat(granted(budget.tryAcquire("b"))).as("another visitor is not held up").isTrue();
        clock.advance(Duration.ofMinutes(3));
        assertThat(granted(budget.tryAcquire("a"))).isTrue();
        LiveBudget.Decision.Granted last = (LiveBudget.Decision.Granted) budget.tryAcquire("c");
        assertThat(last.remainingToday()).as("10 - a, b, a, c; the refused one cost nothing").isEqualTo(6);
    }

    @Test
    @DisplayName("a restart keeps the count and the cooldowns: the state is in the file")
    void restartKeepsTheCount() {
        LiveBudget first = budget(2, Duration.ofMinutes(5));
        assertThat(granted(first.tryAcquire("a"))).isTrue();
        assertThat(granted(first.tryAcquire("b"))).isTrue();
        LiveBudget second = budget(2, Duration.ofMinutes(5));
        assertThat(refused(second.tryAcquire("c")).reason()).isEqualTo("cap-reached");
        LiveBudget third = budget(5, Duration.ofMinutes(5));
        assertThat(refused(third.tryAcquire("a")).reason()).as("the cooldown survived too").isEqualTo("cooldown");
    }

    @Test
    @DisplayName("the next day resets the count")
    void nextDay() {
        LiveBudget budget = budget(1, Duration.ZERO);
        assertThat(granted(budget.tryAcquire("a"))).isTrue();
        assertThat(refused(budget.tryAcquire("b")).reason()).isEqualTo("cap-reached");
        clock.advance(Duration.ofHours(15));
        assertThat(granted(budget.tryAcquire("b"))).isTrue();
        assertThat(refused(budget.tryAcquire("c")).reason()).isEqualTo("cap-reached");
    }

    @Test
    @DisplayName("a state file that is corrupt or unreadable fails closed: no live run, for every call, and the file is left as it was")
    void corruptFailsClosed() throws IOException {
        Path file = dir.resolve("state/budget.json");
        Files.createDirectories(file.getParent());
        for (String bad : List.of("not json", "", "[]", "{\"day\":\"2026-10-14\"}", "{\"day\":\"yesterday\",\"count\":1,\"visitors\":{}}",
                "{\"day\":\"2026-10-14\",\"count\":-1,\"visitors\":{}}", "{\"day\":\"2026-10-14\",\"count\":\"many\",\"visitors\":{}}",
                "{\"day\":\"2026-10-14\",\"count\":0,\"visitors\":[]}", "{\"day\":\"2026-10-14\",\"count\":0,\"visitors\":{\"x\":\"soon\"}}",
                "{\"day\":\"2026-10-14\",\"count\":99999,\"visitors\":{}} trailing")) {
            Files.writeString(file, bad);
            LiveBudget budget = budget(5, Duration.ofMinutes(5));
            for (int i = 0; i < 3; i++) {
                assertThat(refused(budget.tryAcquire("v" + i)).reason()).as(bad).isEqualTo("unavailable");
            }
            assertThat(Files.readString(file)).as("left as it was").isEqualTo(bad);
        }
        Files.delete(file);
        Files.createDirectory(file);
        assertThat(refused(budget(5, Duration.ZERO).tryAcquire("a")).reason()).as("a directory where the file should be").isEqualTo("unavailable");
    }

    @Test
    @DisplayName("with no salt, or a blank one, there is no live run: a visitor cannot be told apart safely")
    void noSaltFailsClosed() {
        for (String salt : new String[] {null, "", "  "}) {
            LiveBudget budget = new LiveBudget(dir.resolve("b.json"), 5, Duration.ZERO, () -> salt, clock);
            assertThat(refused(budget.tryAcquire("a")).reason()).isEqualTo("unavailable");
        }
        LiveBudget throwing = new LiveBudget(dir.resolve("b.json"), 5, Duration.ZERO, () -> {
            throw new IllegalStateException("no salt");
        }, clock);
        assertThat(refused(throwing.tryAcquire("a")).reason()).isEqualTo("unavailable");
        assertThat(Files.exists(dir.resolve("b.json"))).isFalse();
    }

    @Test
    @DisplayName("a visitor is a salted hash: the file holds neither what the service saw of the client nor the salt, and the log holds none of them")
    void visitorIsAHash() throws IOException {
        Logger logger = Logger.getLogger("dev.nkap.agent.live");
        List<String> logs = new CopyOnWriteArrayList<>();
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord r) {
                logs.add(new java.util.logging.SimpleFormatter().formatMessage(r));
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        logger.setLevel(Level.ALL);
        logger.addHandler(handler);
        try {
            LiveBudget budget = budget(5, Duration.ofMinutes(5));
            String address = "203.0.113.77|Mozilla/5.0";
            budget.tryAcquire(address);
            budget.tryAcquire(address);
            String state = Files.readString(dir.resolve("state/budget.json"));
            assertThat(state).doesNotContain("203.0.113.77").doesNotContain("Mozilla").doesNotContain(SALT);
            assertThat(String.join("\n", logs)).doesNotContain("203.0.113.77").doesNotContain("Mozilla").doesNotContain(SALT);
            assertThat(budget.toString()).doesNotContain(SALT);
            assertThat(state).containsPattern("[0-9a-f]{64}");

            Path other = dir.resolve("other.json");
            new LiveBudget(other, 5, Duration.ofMinutes(5), () -> "ANOTHER-TEST-SALT", clock).tryAcquire(address);
            assertThat(Files.readString(other)).as("the hash depends on the salt").doesNotContain(extractHash(state));
        } finally {
            logger.removeHandler(handler);
        }
    }

    private static String extractHash(String state) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("[0-9a-f]{64}").matcher(state);
        assertThat(m.find()).isTrue();
        return m.group();
    }

    @Test
    @DisplayName("the cap holds under concurrent callers: exactly the cap is granted")
    void concurrent() throws Exception {
        LiveBudget budget = budget(10, Duration.ZERO);
        ExecutorService pool = Executors.newFixedThreadPool(16);
        List<Future<Boolean>> results = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            String visitor = "v" + i;
            results.add(pool.submit(() -> granted(budget.tryAcquire(visitor))));
        }
        int granted = 0;
        for (Future<Boolean> f : results) {
            granted += f.get() ? 1 : 0;
        }
        pool.shutdownNow();
        assertThat(granted).isEqualTo(10);
        assertThat(refused(budget(10, Duration.ZERO).tryAcquire("late")).reason()).isEqualTo("cap-reached");
    }

    @Test
    @DisplayName("the cap and the cooldown are the service's to set: a cap below one or a negative cooldown is refused at construction")
    void construction() {
        assertThatThrownBy(() -> budget(0, Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> budget(5, Duration.ofSeconds(-1))).isInstanceOf(IllegalArgumentException.class);
        assertThat(LiveBudget.DEFAULT_DAILY_CAP).isEqualTo(40);
        assertThat(LiveBudget.DEFAULT_COOLDOWN).isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    @DisplayName("a blank visitor is refused as unavailable, not granted as everyone")
    void blankVisitor() {
        LiveBudget budget = budget(5, Duration.ofMinutes(5));
        assertThat(refused(budget.tryAcquire("")).reason()).isEqualTo("unavailable");
        assertThat(refused(budget.tryAcquire(null)).reason()).isEqualTo("unavailable");
    }

    @Test
    @DisplayName("what is left today can be read without using any of it, and is empty when the file cannot be trusted")
    void remainingToday() throws IOException {
        LiveBudget budget = budget(3, Duration.ZERO);
        assertThat(budget.remainingToday()).contains(3);
        budget.tryAcquire("a");
        assertThat(budget.remainingToday()).contains(2);
        assertThat(budget.remainingToday()).as("reading costs nothing").contains(2);
        clock.advance(Duration.ofHours(15));
        assertThat(budget.remainingToday()).as("a new day").contains(3);
        Files.writeString(dir.resolve("state/budget.json"), "garbage");
        assertThat(budget.remainingToday()).isEmpty();
        assertThat(Files.readString(dir.resolve("state/budget.json"))).isEqualTo("garbage");
    }
}

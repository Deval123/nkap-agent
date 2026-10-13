package dev.nkap.agent.tools;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MaskingTest {

    private static final Pattern LONG_RUN = Pattern.compile("\\d{9,}");

    @Test
    @DisplayName("a run of nine or more digits keeps its first four and last two digits, and the rest become stars")
    void contiguousRun() {
        assertThat(Masking.mask("447700900004")).isEqualTo("4477******04");
        assertThat(Masking.mask("123456789")).isEqualTo("1234***89");
    }

    @Test
    @DisplayName("a run of eight digits or fewer is left alone")
    void shortRun() {
        assertThat(Masking.mask("12345678")).isEqualTo("12345678");
        assertThat(Masking.mask("5000")).isEqualTo("5000");
    }

    @Test
    @DisplayName("an amount in minor units is left alone, and one of nine digits or more is masked like any long number")
    void amounts() {
        assertThat(Masking.mask("250000")).isEqualTo("250000");
        assertThat(Masking.mask("100000000")).isEqualTo("1000***00");
    }

    @Test
    @DisplayName("a phone number inside a sentence is masked and the sentence around it is untouched")
    void insideSentence() {
        assertThat(Masking.mask("Call 447700900004 now, or 447700900005 later."))
                .isEqualTo("Call 4477******04 now, or 4477******05 later.");
    }

    @Test
    @DisplayName("two numbers in one string are both masked")
    void twoNumbers() {
        assertThat(Masking.mask("447700900001/447700900002")).isEqualTo("4477******01/4477******02");
    }

    @Test
    @DisplayName("a number split by single spaces, dashes or dots is masked as one number, keeping its separators")
    void splitNumbers() {
        assertThat(Masking.mask("4477 0090 0004")).isEqualTo("4477 **** **04");
        assertThat(Masking.mask("4477-0090-0004")).isEqualTo("4477-****-**04");
        assertThat(Masking.mask("4477.0090.0004")).isEqualTo("4477.****.**04");
        assertThat(Masking.mask("+44 7700 900004")).isEqualTo("+44 77** ****04");
    }

    @Test
    @DisplayName("a split number with fewer than nine digits in all is left alone")
    void shortSplitNumber() {
        assertThat(Masking.mask("12 345 678")).isEqualTo("12 345 678");
    }

    @Test
    @DisplayName("a UUID is never damaged, even when its last group is all digits")
    void uuid() {
        String plain = "0985e8df-93aa-4949-b7aa-185e19ea41d0";
        String allDigits = "0985e8df-9322-4949-1234-123456789012";
        assertThat(Masking.mask(plain)).isEqualTo(plain);
        assertThat(Masking.mask(allDigits)).isEqualTo(allDigits);
        assertThat(Masking.mask("adjustment:" + allDigits)).isEqualTo("adjustment:" + allDigits);
        assertThat(Masking.mask("ref " + allDigits.toUpperCase() + " and 447700900004"))
                .isEqualTo("ref " + allDigits.toUpperCase() + " and 4477******04");
    }

    @Test
    @DisplayName("an ISO timestamp is never damaged, whatever the number of fractional digits")
    void timestamps() {
        for (String t : List.of("2026-10-13T12:25:38.802249Z", "2026-10-13T12:25:38.802249123Z",
                "2026-10-13T12:25:38Z", "2026-10-13T12:25:38.5+01:00", "2026-10-13")) {
            assertThat(Masking.mask(t)).isEqualTo(t);
            assertThat(Masking.mask("at " + t + " ok")).isEqualTo("at " + t + " ok");
        }
    }

    @Test
    @DisplayName("an account id and a statement transaction id are left alone")
    void identifiers() {
        for (String id : List.of("fees:mtn-cm:XAF", "provider:mtn-cm:float:XAF", "FT-DP-0001-RETRY", "suspense:mtn-cm:FT-UF-0001")) {
            assertThat(Masking.mask(id)).isEqualTo(id);
        }
    }

    @Test
    @DisplayName("masking is idempotent")
    void idempotent() {
        for (String s : List.of("447700900004", "4477 0090 0004", "Call 447700900004 now", "plain", "",
                "0985e8df-9322-4949-1234-123456789012", "2026-10-13T12:25:38.802249123Z 447700900004")) {
            assertThat(Masking.mask(Masking.mask(s))).isEqualTo(Masking.mask(s));
        }
    }

    @Test
    @DisplayName("null becomes the empty string and an empty string stays empty")
    void nullAndEmpty() {
        assertThat(Masking.mask(null)).isEmpty();
        assertThat(Masking.mask("")).isEmpty();
    }

    @Test
    @DisplayName("no random digit string of nine to forty digits leaves a run of nine digits, and masking it again changes nothing")
    void randomDigits() {
        Random random = new Random(42);
        for (int i = 0; i < 2000; i++) {
            int length = 9 + random.nextInt(32);
            StringBuilder digits = new StringBuilder();
            for (int j = 0; j < length; j++) {
                digits.append((char) ('0' + random.nextInt(10)));
            }
            String text = "x " + digits + " y";
            String masked = Masking.mask(text);
            assertThat(LONG_RUN.matcher(masked).find()).as(text).isFalse();
            assertThat(masked).as(text).startsWith("x " + digits.substring(0, 4)).endsWith(digits.substring(length - 2) + " y");
            assertThat(masked).hasSize(text.length());
            assertThat(Masking.mask(masked)).isEqualTo(masked);
        }
    }

    @Test
    @DisplayName("no random mix of digits, spaces, dashes and dots leaves nine digits in a row, or nine digits joined by single separators")
    void randomSeparated() {
        Random random = new Random(7);
        Pattern joined = Pattern.compile("\\d+(?:[ .-]\\d+)*");
        String alphabet = "0123456789   --..";
        for (int i = 0; i < 2000; i++) {
            StringBuilder text = new StringBuilder();
            int length = 5 + random.nextInt(60);
            for (int j = 0; j < length; j++) {
                text.append(alphabet.charAt(random.nextInt(alphabet.length())));
            }
            String s = text.toString();
            if (s.matches("(?s).*\\d{4}-\\d{2}-\\d{2}.*")) {
                continue; // a date is protected on purpose; the case below is about everything else
            }
            String masked = Masking.mask(s);
            Matcher m = joined.matcher(masked);
            while (m.find()) {
                // A protected date such as 2026-10-13 has eight digits, so it cannot reach nine on its own.
                assertThat(m.group().replaceAll("\\D", "").length()).as(s + " -> " + masked).isLessThan(9);
            }
            assertThat(masked).hasSize(s.length());
        }
    }
}

package dev.nkap.agent.tools;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Hides telephone numbers, and anything else that looks like one, from whatever is shown to a model or
 * written to a trace. A number is a run of digits; when its digits total nine or more, every digit but
 * the first four and the last two becomes a star. Separators and everything around the number are kept.
 *
 * <p>Decisions, each pinned by a test:
 * <ul>
 *   <li>Digit groups joined by a single space, dash or dot count as one number ({@code 4477 0090 0004}),
 *       and their separators are kept in the output.</li>
 *   <li>A UUID, an ISO date and an ISO timestamp are protected whole, because they are identifiers the
 *       model must be able to cite: the last group of a UUID can be twelve digits by chance, and a
 *       timestamp can carry nine fractional digits.</li>
 *   <li>The failure direction is over-masking: an amount of nine digits or more, a version number such as
 *       {@code 1.2.3.4.5.6.7.8.9}, or {@code 10 000 000} are masked too.</li>
 *   <li>It is idempotent: the stars break a number into runs shorter than nine digits.</li>
 * </ul>
 */
final class Masking {

    private static final int MIN_DIGITS = 9;
    private static final int KEEP_FIRST = 4;
    private static final int KEEP_LAST = 2;

    private static final Pattern PROTECTED = Pattern.compile(
            "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?(?:Z|[+-]\\d{2}:\\d{2})?"
                    + "|(?<![0-9a-fA-F])[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}(?![0-9a-fA-F])"
                    + "|\\d{4}-\\d{2}-\\d{2}");

    private static final Pattern NUMBER = Pattern.compile("\\d+(?:[ .-]\\d+)*");

    private Masking() {
    }

    static String mask(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder(text.length());
        Matcher protectedSpan = PROTECTED.matcher(text);
        int from = 0;
        while (protectedSpan.find()) {
            out.append(maskPlain(text.substring(from, protectedSpan.start())));
            out.append(protectedSpan.group());
            from = protectedSpan.end();
        }
        out.append(maskPlain(text.substring(from)));
        return out.toString();
    }

    private static String maskPlain(String segment) {
        Matcher number = NUMBER.matcher(segment);
        StringBuilder out = new StringBuilder(segment.length());
        int from = 0;
        while (number.find()) {
            out.append(segment, from, number.start());
            out.append(maskNumber(number.group()));
            from = number.end();
        }
        return out.append(segment, from, segment.length()).toString();
    }

    private static String maskNumber(String number) {
        long digits = number.chars().filter(Character::isDigit).count();
        if (digits < MIN_DIGITS) {
            return number;
        }
        StringBuilder out = new StringBuilder(number.length());
        int index = 0;
        for (int i = 0; i < number.length(); i++) {
            char c = number.charAt(i);
            if (Character.isDigit(c)) {
                out.append(index < KEEP_FIRST || index >= digits - KEEP_LAST ? c : '*');
                index++;
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }
}

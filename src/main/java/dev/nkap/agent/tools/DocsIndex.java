package dev.nkap.agent.tools;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A search over a local copy of Nkap's provider pages. It is offline and deterministic: pages are split at
 * {@code ##} and {@code ###} headings (never inside a code fence), a passage is scored by how often the
 * query's keywords occur in it, and at most three come back. There is no network, no embedding and no clock.
 */
public final class DocsIndex {

    static final int MAX_HITS = 3;
    static final int EXCERPT_LIMIT = 800;
    private static final int LEAD = 100;
    private static final int HEADING_WEIGHT = 5;
    private static final int OCCURRENCE_CAP = 10;
    private static final Set<String> COMMON = Set.of("the", "is", "of", "to", "and", "in", "an", "for", "on", "it",
            "does", "what", "how", "why", "are", "was", "be", "by", "or", "at", "as", "with", "this", "that");
    private static final Pattern HEADING = Pattern.compile("^(#{2,3})\\s+(.*?)\\s*$");

    /** One section of one page. */
    public record Passage(String file, String heading, String body) {
    }

    /** One search hit: where it is, and a window of the passage. */
    public record Hit(String file, String heading, String excerpt) {
    }

    private record Scored(Passage passage, int position, int score, int firstMatch) {
    }

    private final List<Passage> passages;
    private final List<String> files;

    private DocsIndex(List<Passage> passages, List<String> files) {
        this.passages = passages;
        this.files = files;
    }

    public static DocsIndex of(Map<String, String> pagesByFile) {
        TreeMap<String, String> sorted = new TreeMap<>(pagesByFile);
        List<Passage> all = new ArrayList<>();
        sorted.forEach((file, text) -> all.addAll(split(file, text)));
        return new DocsIndex(List.copyOf(all), List.copyOf(sorted.keySet()));
    }

    /** The pages listed in {@code docs/providers/SOURCE}, as bundled with the application. */
    public static DocsIndex bundled() {
        String source = resource("SOURCE");
        Map<String, String> pages = new TreeMap<>();
        Matcher listed = Pattern.compile("(?m)^sha256 [0-9a-f]{64}  (\\S+)$").matcher(source);
        while (listed.find()) {
            pages.put(listed.group(1), resource(listed.group(1)));
        }
        return of(pages);
    }

    private static String resource(String name) {
        try (InputStream in = DocsIndex.class.getResourceAsStream("/docs/providers/" + name)) {
            if (in == null) {
                throw new IllegalStateException("the bundled page " + name + " is missing");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public List<String> files() {
        return files;
    }

    public List<Passage> passages() {
        return passages;
    }

    public List<Hit> search(String query) {
        List<String> keywords = keywords(query);
        if (keywords.isEmpty()) {
            return List.of();
        }
        List<Scored> scored = new ArrayList<>();
        for (int i = 0; i < passages.size(); i++) {
            Passage p = passages.get(i);
            String body = p.body().toLowerCase(Locale.ROOT);
            String heading = p.heading().toLowerCase(Locale.ROOT);
            int score = 0;
            int first = -1;
            for (String keyword : keywords) {
                int count = 0;
                for (int at = body.indexOf(keyword); at >= 0 && count < OCCURRENCE_CAP; at = body.indexOf(keyword, at + 1)) {
                    count++;
                    if (first < 0 || at < first) {
                        first = at;
                    }
                }
                score += count;
                if (heading.contains(keyword)) {
                    score += HEADING_WEIGHT;
                }
            }
            if (score > 0) {
                scored.add(new Scored(p, i, score, first));
            }
        }
        scored.sort(Comparator.comparingInt(Scored::score).reversed()
                .thenComparing(s -> s.passage().file())
                .thenComparingInt(Scored::position));
        return scored.stream().limit(MAX_HITS)
                .map(s -> new Hit(s.passage().file(), s.passage().heading(), excerpt(s.passage().body(), s.firstMatch())))
                .toList();
    }

    private static List<String> keywords(String query) {
        if (query == null) {
            return List.of();
        }
        Set<String> out = new LinkedHashSet<>();
        for (String token : query.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
            if (token.length() >= 2 && !COMMON.contains(token)) {
                out.add(token);
            }
            if (out.size() == 20) {
                break;
            }
        }
        return List.copyOf(out);
    }

    private static String excerpt(String body, int firstMatch) {
        String text = body.strip();
        int offset = body.length() - body.stripLeading().length();
        int start = firstMatch < 0 ? 0 : Math.max(0, firstMatch - offset - LEAD);
        boolean cutFront = start > 0;
        int room = EXCERPT_LIMIT - (cutFront ? 1 : 0);
        String window = text.substring(Math.min(start, text.length()), Math.min(text.length(), start + room));
        return cutFront ? "…" + window : window;
    }

    private static List<Passage> split(String file, String text) {
        List<Passage> out = new ArrayList<>();
        String heading = "(start of page)";
        StringBuilder body = new StringBuilder();
        boolean fenced = false;
        for (String line : text.split("\n", -1)) {
            if (line.stripLeading().startsWith("```")) {
                fenced = !fenced;
            }
            Matcher h = fenced ? null : HEADING.matcher(line);
            if (h != null && h.matches()) {
                addIfNotBlank(out, file, heading, body);
                heading = h.group(2);
                body = new StringBuilder();
            } else {
                body.append(line).append('\n');
            }
        }
        addIfNotBlank(out, file, heading, body);
        return out;
    }

    private static void addIfNotBlank(List<Passage> out, String file, String heading, StringBuilder body) {
        if (!body.toString().isBlank() || !heading.equals("(start of page)")) {
            out.add(new Passage(file, heading, body.toString()));
        }
    }
}

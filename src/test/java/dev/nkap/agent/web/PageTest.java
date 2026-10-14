package dev.nkap.agent.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PageTest {

    private static final Path WEB = Path.of("src/main/resources/web");

    private static String read(String name) throws IOException {
        return Files.readString(WEB.resolve(name));
    }

    @Test
    @DisplayName("the page, its script and its style hold no innerHTML, no eval, no document.write, no external address and no other way to write markup")
    void noDangerousApis() throws IOException {
        for (String name : List.of("index.html", "app.js", "app.css")) {
            String text = read(name);
            assertThat(text).as(name).doesNotContain("innerHTML").doesNotContain("outerHTML").doesNotContain("insertAdjacentHTML")
                    .doesNotContain("eval(").doesNotContain("new Function").doesNotContain("document.write").doesNotContain("http://")
                    .doesNotContain("https://").doesNotContain("//cdn").doesNotContain("@import").doesNotContain("url(")
                    .doesNotContain("setTimeout(\"").doesNotContain("srcdoc").doesNotContain("javascript:");
        }
    }

    @Test
    @DisplayName("the HTML has no inline script, no inline style and no event-handler attribute: the policy allows only files from this service")
    void htmlIsInert() throws IOException {
        String html = read("index.html");
        Matcher scripts = Pattern.compile("<script([^>]*)>(.*?)</script>", Pattern.DOTALL).matcher(html);
        int found = 0;
        while (scripts.find()) {
            found++;
            assertThat(scripts.group(1)).contains("src=\"/app.js\"");
            assertThat(scripts.group(2).strip()).isEmpty();
        }
        assertThat(found).isEqualTo(1);
        assertThat(html).doesNotContain("<style").doesNotContainPattern("(?i)\\sstyle\\s*=").doesNotContainPattern("(?i)\\son[a-z]+\\s*=");
        assertThat(html).containsPattern("<link[^>]*href=\"/app.css\"");
    }

    @Test
    @DisplayName("every request the script makes is to a path on this service, and none to anything else")
    void onlyThisOrigin() throws IOException {
        String js = read("app.js");
        Matcher calls = Pattern.compile("(?:fetch|get|post)\\(\\s*[`'\"]([^`'\"]*)").matcher(js);
        int found = 0;
        while (calls.find()) {
            found++;
            assertThat(calls.group(1)).startsWith("/api/");
        }
        assertThat(found).isGreaterThan(3);
        assertThat(js).doesNotContain("XMLHttpRequest").doesNotContain("WebSocket").doesNotContain("EventSource").doesNotContain("sendBeacon")
                .doesNotContain("localStorage").doesNotContain("sessionStorage").doesNotContain("document.cookie").doesNotContain("import(");
    }

    @Test
    @DisplayName("model text and tool output reach the page through textContent, and the three modes are labelled in words")
    void honestLabels() throws IOException {
        String js = read("app.js");
        assertThat(js).contains("textContent");
        assertThat(js).contains("RECORDING").contains("SCRIPTED ANSWERS, NOT A MODEL").contains("LIVE");
        assertThat(js).contains("text written by the model, not checked");
        assertThat(js).contains("Approve with the approver's key").contains("Try to approve with the agent's own key");
    }

    @Test
    @DisplayName("the approval buttons are created only for a run that has a run id, which the service gives only for a proposal Nkap recorded")
    void buttonsNeedARunId() throws IOException {
        String js = read("app.js");
        int guard = js.indexOf("run.runId");
        int button = js.indexOf("Approve with the approver's key");
        assertThat(guard).isPositive();
        assertThat(button).isGreaterThan(guard);
        assertThat(js).containsPattern("if\\s*\\(\\s*run\\.runId\\s*\\)");
    }

    @Test
    @DisplayName("the page says, before a click, when approval is not available for the payment, and keeps the self-approve button; a refusal leaves the buttons for the next attempt")
    void approvalUnavailableAndAttempts() throws IOException {
        String js = read("app.js");
        assertThat(js).contains("run.approval").contains("available === false").contains("approval.text");
        assertThat(js).contains("attemptsLeft");
        assertThat(js).containsPattern("approve\\.disabled\\s*=");
        int block = js.indexOf("available === false");
        int selfButton = js.indexOf("Try to approve with the agent's own key");
        assertThat(block).isPositive();
        assertThat(selfButton).isPositive();
        assertThat(js).as("a refused attempt does not disable the buttons for good").contains("reply.body.result === 'decided'");
    }

    /** The text between the parenthesis that opens at {@code open} and its match. */
    private static String balanced(String text, int open) {
        int depth = 0;
        for (int i = open; i < text.length(); i++) {
            if (text.charAt(i) == '(') {
                depth++;
            } else if (text.charAt(i) == ')' && --depth == 0) {
                return text.substring(open, i + 1);
            }
        }
        throw new IllegalStateException("unbalanced parenthesis");
    }

    /** The text of the block that opens at the first brace after {@code header}. */
    private static String block(String css, String header) {
        int start = css.indexOf(header);
        assertThat(start).as(header).isNotNegative();
        int open = css.indexOf('{', start);
        int depth = 0;
        for (int i = open; i < css.length(); i++) {
            if (css.charAt(i) == '{') {
                depth++;
            } else if (css.charAt(i) == '}' && --depth == 0) {
                return css.substring(open + 1, i);
            }
        }
        throw new IllegalStateException("unbalanced brace");
    }

    @Test
    @DisplayName("the style holds no font file and no url(), and every animation lives in the reduced-motion block or is switched off by it")
    void styleHasNoFontsAndNoUncheckedMotion() throws IOException {
        String css = read("app.css");
        assertThat(css).doesNotContain("@font-face").doesNotContain("url(");
        assertThat(css).contains("prefers-reduced-motion: reduce").contains("prefers-reduced-motion: no-preference");
        String allowed = block(css, "@media (prefers-reduced-motion: no-preference)");
        assertThat(allowed).contains("@keyframes").contains("animation:");
        assertThat(css.replace(allowed, "")).as("no animation or keyframes outside the no-preference block").doesNotContain("@keyframes")
                .doesNotContainPattern("animation(-name)?\\s*:\\s*(?!none)[a-z]");
        assertThat(block(css, "@media (prefers-reduced-motion: reduce)")).containsPattern("animation:\\s*none");
    }

    @Test
    @DisplayName("the working state says what is happening in each mode, is a status region, and the buttons keep their texts")
    void workingState() throws IOException {
        String js = read("app.js");
        assertThat(js).contains("Running the real checks.").contains("The model is working. A live run takes about 15 seconds.");
        assertThat(js).contains("setAttribute('role', 'status')");
        assertThat(js).contains("'Running'").containsPattern("button\\.disabled\\s*=\\s*true");
        assertThat(js).contains("Try again in about");
    }

    @Test
    @DisplayName("the only setInterval is the local counter, it is cleared in a finally, and no timer calls the service")
    void timers() throws IOException {
        String js = read("app.js");
        Matcher intervals = Pattern.compile("setInterval\\(").matcher(js);
        int count = 0;
        while (intervals.find()) {
            count++;
            String call = balanced(js, intervals.end() - 1);
            assertThat(call).as("the counter only writes the elapsed text").contains("elapsed.textContent")
                    .doesNotContainPattern("\\b(fetch|get|post|refreshStatus|runCase|loadRecording)\\s*\\(");
        }
        assertThat(count).isEqualTo(1);
        assertThat(js).containsPattern("finally\\s*\\{[^}]*clearInterval\\(");
        Matcher timeouts = Pattern.compile("setTimeout\\(").matcher(js);
        int sleeps = 0;
        while (timeouts.find()) {
            sleeps++;
            assertThat(balanced(js, timeouts.end() - 1)).as("the replay delay only resolves a promise")
                    .doesNotContainPattern("\\b(fetch|get|post|refreshStatus)\\s*\\(");
        }
        assertThat(sleeps).as("the replay delay is the only timeout").isEqualTo(1);
    }

    @Test
    @DisplayName("the page has its landmarks, the two-option mode group, the metadata and the focusable slip")
    void structure() throws IOException {
        String html = read("index.html");
        assertThat(html).contains("<header").contains("<main").contains("<fieldset").contains("<legend>Mode</legend>")
                .containsPattern("type=\"radio\"[^>]*value=\"scripted\"").containsPattern("type=\"radio\"[^>]*value=\"live\"")
                .contains("name=\"description\"").contains("name=\"theme-color\" content=\"#0E2B25\"")
                .containsPattern("<section[^>]*id=\"slip\"[^>]*tabindex=\"-1\"[^>]*aria-labelledby=\"slip-title\"")
                .doesNotContain("autofocus");
        assertThat(html).containsPattern("<select id=\"case\"");
    }
}

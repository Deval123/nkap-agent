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
}

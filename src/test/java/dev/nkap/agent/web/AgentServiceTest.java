package dev.nkap.agent.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import dev.nkap.agent.approval.ScriptedAnswers;
import dev.nkap.agent.model.AgentModel;
import dev.nkap.agent.model.Message;
import dev.nkap.agent.model.ModelResult;
import dev.nkap.agent.scenario.Catalogue;
import dev.nkap.agent.scenario.ScenarioCase;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AgentServiceTest {

    private static final String CSP = "default-src 'none'; script-src 'self'; style-src 'self'; connect-src 'self'; base-uri 'none'; "
            + "form-action 'none'; frame-ancestors 'none'";

    @TempDir
    Path dir;

    private ServiceHarness h;
    private final AtomicReference<List<String>> liveAnswers = new AtomicReference<>(List.of());
    private final MovingClock clock = new MovingClock("2026-10-14T09:00:00Z");

    private void start(Consumer<Map<String, String>> tweak) throws Exception {
        h = new ServiceHarness(dir, tweak, () -> new ScriptedAnswers(liveAnswers.get()), clock);
    }

    private void start() throws Exception {
        start(env -> { });
    }

    @AfterEach
    void stop() {
        if (h != null) {
            h.close();
        }
    }

    private static ScenarioCase scenario(String id) {
        return Catalogue.all().stream().filter(c -> c.id().equals(id)).findFirst().orElseThrow();
    }

    private List<String> answersFor(String caseId) throws Exception {
        JsonNode manifest = ServiceHarness.JSON.readTree(dir.resolve("manifest.json").toFile());
        for (JsonNode n : manifest.get("cases")) {
            if (n.get("id").asText().equals(caseId)) {
                String imp = n.get("statementImportId").isNull() ? "00000000-0000-0000-0000-000000000000" : n.get("statementImportId").asText();
                return ScriptedAnswers.forCase(scenario(caseId), n.get("paymentReference").asText(), imp, null);
            }
        }
        throw new AssertionError(caseId);
    }

    // --- the routes

    @Test
    @DisplayName("the page, its script and its style are served from the service itself, with the right types")
    void staticRoutes() throws Exception {
        start();
        ServiceHarness.Reply page = h.get("/");
        assertThat(page.status()).isEqualTo(200);
        assertThat(page.header("content-type")).startsWith("text/html");
        assertThat(page.body()).contains("<script src=\"/app.js\"").contains("/app.css");
        assertThat(h.get("/app.js").header("content-type")).startsWith("application/javascript").isNotNull();
        assertThat(h.get("/app.css").header("content-type")).startsWith("text/css");
        assertThat(h.get("/app.js").status()).isEqualTo(200);
    }

    @Test
    @DisplayName("the status says whether live is on and how many live runs are left today, and nothing else")
    void status() throws Exception {
        start();
        JsonNode off = h.get("/api/status").json();
        assertThat(off.get("liveEnabled").asBoolean()).isFalse();
        assertThat(off.get("liveRunsLeftToday").isNull()).isTrue();
        assertThat(off.size()).isEqualTo(2);
        h.close();
        h = null;
        start(env -> {
            env.put("NEBIUS_API_KEY", ServiceHarness.MODEL_KEY);
            env.put("NEBIUS_BASE_URL", "http://127.0.0.1:9/v1/");
            env.put("NKAP_AGENT_LIVE_DAILY_CAP", "7");
        });
        JsonNode on = h.get("/api/status").json();
        assertThat(on.get("liveEnabled").asBoolean()).isTrue();
        assertThat(on.get("liveRunsLeftToday").asInt()).isEqualTo(7);
        assertThat(on.size()).isEqualTo(2);
        assertThat(h.get("/api/status").body()).doesNotContain(ServiceHarness.MODEL_KEY).doesNotContain(ServiceHarness.AGENT_KEY);
    }

    @Test
    @DisplayName("the recordings are the published traces: id, case, title, outcome, matched")
    void recordings() throws Exception {
        start();
        JsonNode list = h.get("/api/recordings").json();
        assertThat(list).hasSize(7);
        List<String> ids = new ArrayList<>();
        list.forEach(n -> ids.add(n.get("id").asText()));
        assertThat(ids).contains("unrecorded-fee-1", "duplicate-posting-2");
        JsonNode rejected = null;
        for (JsonNode n : list) {
            assertThat(n.size()).isEqualTo(5);
            if (n.get("id").asText().equals("duplicate-posting-2")) {
                rejected = n;
            }
        }
        assertThat(rejected.get("outcome").asText()).isEqualTo("REJECTED");
        assertThat(rejected.get("matched").asBoolean()).isFalse();
        assertThat(rejected.get("case").asText()).isEqualTo("duplicate-posting");
    }

    @Test
    @DisplayName("a recording is replayed: its steps in order, each marked as a recording, the provenance first, and no model, gateway or Nkap is touched")
    void recording() throws Exception {
        start();
        JsonNode r = h.get("/api/recordings/unrecorded-fee-1").json();
        assertThat(r.get("mode").asText()).isEqualTo("recording");
        assertThat(r.get("provenance").get("case").asText()).isEqualTo("unrecorded-fee");
        JsonNode steps = r.get("steps");
        assertThat(steps.get(0).get("type").asText()).isEqualTo("task");
        assertThat(steps.get(steps.size() - 1).get("type").asText()).isEqualTo("outcome");
        steps.forEach(s -> {
            assertThat(s.get("recording").asBoolean()).isTrue();
            assertThat(s.get("mode").asText()).isEqualTo("recording");
        });
        assertThat(h.nkap.seen).isEmpty();
    }

    @Test
    @DisplayName("a recording id is looked up in the list of published traces, never made into a path: a traversal, an encoded slash and an unknown id are 404")
    void recordingIdIsNeverAPath() throws Exception {
        start();
        for (String id : List.of("nope", "..%2FREADME", "%2e%2e%2f%2e%2e%2fpom.xml", "unrecorded-fee-1.json", "unrecorded-fee-1%00", "UNRECORDED-FEE-1", "unrecorded-fee-9")) {
            assertThat(h.get("/api/recordings/" + id).status()).as(id).isEqualTo(404);
        }
        assertThat(h.get("/api/recordings/../../pom.xml").status()).isEqualTo(404);
    }

    @Test
    @DisplayName("a wrong method on a known route is 405 with Allow, and an unknown route is 404; no other route exists")
    void methodsAndUnknownRoutes() throws Exception {
        start();
        Map<String, List<String>> allowed = Map.of("/", List.of("GET"), "/app.js", List.of("GET"), "/app.css", List.of("GET"),
                "/api/status", List.of("GET"), "/api/recordings", List.of("GET"), "/api/recordings/unrecorded-fee-1", List.of("GET"),
                "/api/runs", List.of("POST"), "/api/runs/" + "a".repeat(32) + "/approve", List.of("POST"),
                "/api/runs/" + "a".repeat(32) + "/self-approve", List.of("POST"));
        for (Map.Entry<String, List<String>> route : allowed.entrySet()) {
            for (String method : List.of("GET", "POST", "PUT", "DELETE", "PATCH")) {
                if (route.getValue().contains(method)) {
                    continue;
                }
                ServiceHarness.Reply r = h.call(method, route.getKey(), method.equals("GET") ? null : "{}", "Content-Type", "application/json");
                assertThat(r.status()).as(method + " " + route.getKey()).isEqualTo(405);
                assertThat(r.header("allow")).as(route.getKey()).contains(route.getValue().get(0));
            }
        }
        for (String path : List.of("/admin", "/api", "/api/", "/api/runs/x", "/api/runs/" + "a".repeat(32), "/api/runs/" + "a".repeat(32) + "/reject",
                "/api/try-unchecked", "/actuator/health", "/etc/passwd", "/app.js/x", "/index.html", "/api/recordings/", "/favicon.ico", "/api/status/x")) {
            assertThat(h.get(path).status()).as(path).isEqualTo(404);
        }
        assertThat(h.call("OPTIONS", "/api/runs", null).status()).isIn(404, 405);
        assertThat(h.get("//").status()).as("the JDK server itself refuses this request line").isIn(400, 404);
        assertThat(h.nkap.seen).isEmpty();
    }

    // --- the headers

    @Test
    @DisplayName("every response, an error too, has the security headers; /api/* is also no-store")
    void headers() throws Exception {
        start();
        List<ServiceHarness.Reply> replies = List.of(h.get("/"), h.get("/app.js"), h.get("/app.css"), h.get("/api/status"), h.get("/api/recordings"),
                h.get("/api/recordings/nope"), h.get("/nowhere"), h.call("DELETE", "/api/runs", null), h.post("/api/runs", "not json"),
                h.run("unrecorded-fee", "scripted"));
        for (ServiceHarness.Reply r : replies) {
            assertThat(r.header("content-security-policy")).isEqualTo(CSP);
            assertThat(r.header("x-content-type-options")).isEqualTo("nosniff");
            assertThat(r.header("referrer-policy")).isEqualTo("no-referrer");
        }
        for (ServiceHarness.Reply r : replies.subList(3, replies.size()).stream().filter(x -> x != replies.get(6)).toList()) {
            assertThat(r.header("cache-control")).isEqualTo("no-store");
        }
        assertThat(replies.get(6).header("cache-control")).as("an unknown route is no-store too, as it may be an API route that failed").isNotNull();
    }

    // --- limits

    @Test
    @DisplayName("a POST needs Content-Type application/json: another type, none, and a form are 415")
    void contentType() throws Exception {
        start();
        for (String type : new String[] {"text/plain", "application/x-www-form-urlencoded", "application/jsonx", "multipart/form-data"}) {
            assertThat(h.call("POST", "/api/runs", "{\"case\":\"unrecorded-fee\",\"mode\":\"scripted\"}", "Content-Type", type).status()).as(type).isEqualTo(415);
        }
        assertThat(h.call("POST", "/api/runs", "{\"case\":\"unrecorded-fee\",\"mode\":\"scripted\"}").status()).as("none").isEqualTo(415);
        assertThat(h.call("POST", "/api/runs", "{\"case\":\"injection\",\"mode\":\"scripted\"}", "Content-Type", "application/json; charset=utf-8").status()).isEqualTo(200);
    }

    @Test
    @DisplayName("a body over 1 KB is 413 and is not read further; at 1 KB it is read")
    void bodySize() throws Exception {
        start();
        String big = "{\"case\":\"unrecorded-fee\",\"mode\":\"scripted\",\"x\":\"" + "a".repeat(1100) + "\"}";
        assertThat(h.post("/api/runs", big).status()).isEqualTo(413);
        String request = "{\"case\":\"injection\",\"mode\":\"scripted\"}";
        String padded = request + " ".repeat(1024 - request.length());
        assertThat(padded.length()).isEqualTo(1024);
        assertThat(h.post("/api/runs", padded).status()).isEqualTo(200);
    }

    @Test
    @DisplayName("an Origin that does not match the Host is 403; one that matches, or none, is accepted")
    void origin() throws Exception {
        start();
        String body = "{\"case\":\"injection\",\"mode\":\"scripted\"}";
        assertThat(h.post("/api/runs", body, "Origin", "http://evil.example").status()).isEqualTo(403);
        assertThat(h.post("/api/runs", body, "Origin", "null").status()).isEqualTo(403);
        assertThat(h.post("/api/runs", body, "Origin", "http://127.0.0.1:1").status()).as("same host, other port").isEqualTo(403);
        assertThat(h.get("/api/status", "Origin", "http://evil.example").status()).as("any method").isEqualTo(403);
        assertThat(h.post("/api/runs", body, "Origin", "http://127.0.0.1:" + h.port()).status()).isEqualTo(200);
        assertThat(h.post("/api/runs", body).status()).isEqualTo(200);
    }

    @Test
    @DisplayName("the run request must be exactly a catalogue case and a mode: an unknown case, a bad mode, extra fields, wrong types and bad JSON are 400")
    void runRequest() throws Exception {
        start();
        for (String bad : List.of("{\"case\":\"nope\",\"mode\":\"scripted\"}", "{\"case\":\"unrecorded-fee\",\"mode\":\"magic\"}",
                "{\"case\":\"unrecorded-fee\"}", "{\"mode\":\"scripted\"}", "{\"case\":\"unrecorded-fee\",\"mode\":\"scripted\",\"extra\":1}",
                "{\"case\":1,\"mode\":\"scripted\"}", "{\"case\":\"../../x\",\"mode\":\"scripted\"}", "[]", "\"x\"", "not json", "", "{\"case\":null,\"mode\":null}")) {
            ServiceHarness.Reply r = h.post("/api/runs", bad);
            assertThat(r.status()).as(bad).isEqualTo(400);
            assertThat(r.json().get("error").asText()).isEqualTo("bad-request");
            assertThat(r.body()).as("the request is not echoed").doesNotContain("../../x");
        }
        assertThat(h.nkap.seen).isEmpty();
    }

    // --- scripted runs, approve and self-approve

    @Test
    @DisplayName("a scripted run is the real loop: it proposes with the agent's key, labels every step scripted, and shows no adjustment id")
    void scriptedRun() throws Exception {
        start();
        ServiceHarness.Reply reply = h.run("unrecorded-fee", "scripted");
        assertThat(reply.status()).isEqualTo(200);
        JsonNode run = reply.json();
        assertThat(run.get("mode").asText()).isEqualTo("scripted");
        assertThat(run.get("runId").asText()).matches("[0-9a-f]{32}");
        assertThat(run.get("outcome").get("kind").asText()).isEqualTo("PROPOSED");
        assertThat(run.get("proposal").get("status").asText()).isEqualTo("created");
        run.get("steps").forEach(s -> {
            assertThat(s.get("recording").asBoolean()).isFalse();
            assertThat(s.get("mode").asText()).isEqualTo("scripted");
        });
        assertThat(h.proposedBy.values()).containsExactly(ServiceHarness.AGENT_KEY);
        String adjustmentId = h.proposedBy.keySet().iterator().next();
        assertThat(reply.body()).doesNotContain(adjustmentId).doesNotContain(ServiceHarness.AGENT_KEY).doesNotContain(ServiceHarness.APPROVER_KEY);
        assertThat(run.get("totals").get("modelCalls").asInt()).isEqualTo(3);
        assertThat(h.proposalBodies).hasSize(1);
        assertThat(ServiceHarness.JSON.readTree(h.proposalBodies.get(0)).get("cites").toString()).contains("c2/lines/0/feeMinor");
    }

    @Test
    @DisplayName("a case that expects no proposal ends in no_proposal, sends nothing to the adjustment routes, and has no runId to decide on")
    void noProposalRun() throws Exception {
        start();
        for (String c : List.of("money-without-owner", "injection", "i-do-not-know")) {
            JsonNode run = h.run(c, "scripted").json();
            assertThat(run.get("outcome").get("kind").asText()).as(c).isEqualTo("NO_PROPOSAL");
            assertThat(run.get("proposal").get("status").asText()).isEqualTo("none");
            assertThat(run.has("runId")).isFalse();
        }
        assertThat(h.proposalBodies).isEmpty();
    }

    @Test
    @DisplayName("approve uses the approver's key and shows the ledger entry; a second decision on the run is 409")
    void approve() throws Exception {
        start();
        String runId = h.run("unrecorded-fee", "scripted").json().get("runId").asText();
        ServiceHarness.Reply reply = h.post("/api/runs/" + runId + "/approve", "{}");
        assertThat(reply.status()).isEqualTo(200);
        JsonNode decision = reply.json();
        assertThat(decision.get("decision").asText()).isEqualTo("approve");
        assertThat(decision.get("result").asText()).isEqualTo("decided");
        assertThat(decision.get("ledgerEntryId").asText()).startsWith("adjustment:");
        assertThat(h.ledgerEntries).hasValue(1);
        assertThat(h.post("/api/runs/" + runId + "/approve", "{}").status()).isEqualTo(409);
        assertThat(h.post("/api/runs/" + runId + "/self-approve", "{}").status()).as("one decision per run").isEqualTo(409);
        assertThat(h.ledgerEntries).hasValue(1);
    }

    @Test
    @DisplayName("self-approve uses the agent's own key and is refused by Nkap, with a fixed text and not the server's words; nothing is written, and the run stays open")
    void selfApprove() throws Exception {
        start();
        String runId = h.run("self-approval", "scripted").json().get("runId").asText();
        ServiceHarness.Reply reply = h.post("/api/runs/" + runId + "/self-approve", "{}");
        assertThat(reply.status()).isEqualTo(200);
        JsonNode decision = reply.json();
        assertThat(decision.get("decision").asText()).isEqualTo("self-approve");
        assertThat(decision.get("result").asText()).isEqualTo("refused");
        assertThat(decision.get("code").asText()).isEqualTo("adjustment-self-approval");
        assertThat(decision.get("text").asText()).contains("different key");
        assertThat(decision.get("attemptsLeft").asInt()).isEqualTo(2);
        assertThat(reply.body()).doesNotContain("SERVER-DETAIL");
        assertThat(h.ledgerEntries).hasValue(0);
        ServiceHarness.Reply approved = h.post("/api/runs/" + runId + "/approve", "{}");
        assertThat(approved.status()).as("a refusal did not use up the decision").isEqualTo(200);
        assertThat(approved.json().get("result").asText()).isEqualTo("decided");
        assertThat(h.ledgerEntries).hasValue(1);
        assertThat(h.post("/api/runs/" + runId + "/approve", "{}").status()).as("now it is closed").isEqualTo(409);
        assertThat(h.post("/api/runs/" + runId + "/self-approve", "{}").status()).isEqualTo(409);
    }

    @Test
    @DisplayName("a run allows three attempts that Nkap refused; after that every decision on it is 429 with a fixed text, and Nkap is not asked")
    void threeAttempts() throws Exception {
        start();
        String runId = h.run("self-approval", "scripted").json().get("runId").asText();
        for (int left = 2; left >= 0; left--) {
            JsonNode refused = h.post("/api/runs/" + runId + "/self-approve", "{}").json();
            assertThat(refused.get("result").asText()).isEqualTo("refused");
            assertThat(refused.get("attemptsLeft").asInt()).isEqualTo(left);
        }
        int posts = h.decisionPosts.size();
        ServiceHarness.Reply fourth = h.post("/api/runs/" + runId + "/self-approve", "{}");
        assertThat(fourth.status()).isEqualTo(429);
        assertThat(fourth.json().get("error").asText()).isEqualTo("too-many-attempts");
        assertThat(fourth.json().get("text").asText()).isNotBlank();
        assertThat(h.post("/api/runs/" + runId + "/approve", "{}").status()).isEqualTo(429);
        assertThat(h.decisionPosts).as("nothing more reached Nkap").hasSize(posts);
        assertThat(h.ledgerEntries).hasValue(0);
    }

    @Test
    @DisplayName("several requests at once on one run: exactly one reaches Nkap as a decision, the others are 409, and one ledger entry is written")
    void concurrentDecisionsOnOneRun() throws Exception {
        start();
        String runId = h.run("unrecorded-fee", "scripted").json().get("runId").asText();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<ServiceHarness.Reply>> replies = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                replies.add(pool.submit(() -> h.post("/api/runs/" + runId + "/approve", "{}")));
            }
            int decided = 0;
            int conflicts = 0;
            for (Future<ServiceHarness.Reply> f : replies) {
                ServiceHarness.Reply r = f.get(30, TimeUnit.SECONDS);
                if (r.status() == 200 && r.json().get("result").asText().equals("decided")) {
                    decided++;
                } else if (r.status() == 409) {
                    conflicts++;
                }
            }
            assertThat(decided).isEqualTo(1);
            assertThat(conflicts).isEqualTo(7);
        } finally {
            pool.shutdownNow();
        }
        assertThat(h.decisionPosts).hasSize(1);
        assertThat(h.ledgerEntries).hasValue(1);
    }

    // --- one approved adjustment per payment

    private static final String ALREADY = "An earlier visitor already approved an adjustment for this payment in this demo ledger. "
            + "Nkap would accept a second one; this service does not send it.";

    @Test
    @DisplayName("a second approval for a payment that already has an approved adjustment is 409 with the fixed text, and is not sent to Nkap")
    void noDoubleBooking() throws Exception {
        start();
        String first = h.run("unrecorded-fee", "scripted").json().get("runId").asText();
        assertThat(h.post("/api/runs/" + first + "/approve", "{}").status()).isEqualTo(200);
        JsonNode second = h.run("unrecorded-fee", "scripted").json();
        assertThat(second.get("proposal").get("status").asText()).as("the proposal is still created").isEqualTo("created");
        String secondId = second.get("runId").asText();
        int posts = h.decisionPosts.size();
        ServiceHarness.Reply refused = h.post("/api/runs/" + secondId + "/approve", "{}");
        assertThat(refused.status()).isEqualTo(409);
        assertThat(refused.json().get("error").asText()).isEqualTo("payment-already-approved");
        assertThat(refused.json().get("text").asText()).isEqualTo(ALREADY);
        assertThat(h.decisionPosts).as("it was not sent").hasSize(posts);
        assertThat(h.ledgerEntries).hasValue(1);
        assertThat(h.post("/api/runs/" + secondId + "/approve", "{}").status()).as("still the run's to try, not closed or counted").isEqualTo(409);
        JsonNode self = h.post("/api/runs/" + secondId + "/self-approve", "{}").json();
        assertThat(self.get("result").asText()).as("self-approve and its refusal stay available").isEqualTo("refused");
        assertThat(self.get("code").asText()).isEqualTo("adjustment-self-approval");
    }

    @Test
    @DisplayName("the run says, when it ends, whether approval is available, so the page can say it before the visitor clicks")
    void runEndSaysWhetherApprovalIsAvailable() throws Exception {
        start();
        JsonNode first = h.run("unrecorded-fee", "scripted").json();
        assertThat(first.get("approval").get("available").asBoolean()).isTrue();
        assertThat(h.post("/api/runs/" + first.get("runId").asText() + "/approve", "{}").status()).isEqualTo(200);
        JsonNode second = h.run("unrecorded-fee", "scripted").json();
        assertThat(second.get("approval").get("available").asBoolean()).isFalse();
        assertThat(second.get("approval").get("code").asText()).isEqualTo("payment-already-approved");
        assertThat(second.get("approval").get("text").asText()).isEqualTo(ALREADY);
        assertThat(h.run("self-approval", "scripted").json().get("approval").get("available").asBoolean()).as("another payment").isTrue();
        assertThat(h.run("injection", "scripted").json().has("approval")).as("nothing proposed, nothing to approve").isFalse();
    }

    @Test
    @DisplayName("two visitors approving at once for the same payment cannot both pass: one is decided, the other is 409, and the ledger gets one entry")
    void concurrentVisitorsSamePayment() throws Exception {
        start();
        List<String> runs = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            runs.add(h.run("unrecorded-fee", "scripted").json().get("runId").asText());
        }
        ExecutorService pool = Executors.newFixedThreadPool(6);
        try {
            List<Future<ServiceHarness.Reply>> replies = new ArrayList<>();
            for (String run : runs) {
                replies.add(pool.submit(() -> h.post("/api/runs/" + run + "/approve", "{}")));
            }
            int decided = 0;
            int refused = 0;
            for (Future<ServiceHarness.Reply> f : replies) {
                ServiceHarness.Reply r = f.get(30, TimeUnit.SECONDS);
                if (r.status() == 200) {
                    decided++;
                } else if (r.status() == 409 && r.json().get("error").asText().equals("payment-already-approved")) {
                    refused++;
                }
            }
            assertThat(decided).isEqualTo(1);
            assertThat(refused).isEqualTo(5);
        } finally {
            pool.shutdownNow();
        }
        assertThat(h.ledgerEntries).hasValue(1);
        assertThat(h.decisionPosts).hasSize(1);
    }

    @Test
    @DisplayName("different payments do not hold each other up: each case can be approved once")
    void otherPaymentsAreFree() throws Exception {
        start();
        for (String c : List.of("unrecorded-fee", "duplicate-posting", "self-approval")) {
            String run = h.run(c, "scripted").json().get("runId").asText();
            assertThat(h.post("/api/runs/" + run + "/approve", "{}").status()).as(c).isEqualTo(200);
        }
        assertThat(h.ledgerEntries).hasValue(3);
    }

    @Test
    @DisplayName("the rule survives a restart of the service, because the state is in a file and Nkap confirms it")
    void survivesARestart() throws Exception {
        start();
        String first = h.run("unrecorded-fee", "scripted").json().get("runId").asText();
        h.post("/api/runs/" + first + "/approve", "{}");
        h.service.close();
        ServiceHarness.Reply after = restartAndRun();
        assertThat(after.status()).isEqualTo(409);
        assertThat(after.json().get("error").asText()).isEqualTo("payment-already-approved");
    }

    private ServiceHarness.Reply restartAndRun() throws Exception {
        ServiceHarness old = h;
        Map<String, String> env = new java.util.HashMap<>();
        env.put("NKAP_AGENT_PORT", "0");
        env.put("NKAP_AGENT_MANIFEST", dir.resolve("manifest.json").toString());
        env.put("NKAP_GATEWAY_URL", old.nkap.url());
        env.put("NKAP_AGENT_KEY", ServiceHarness.AGENT_KEY);
        env.put("NKAP_APPROVER_KEY", ServiceHarness.APPROVER_KEY);
        env.put("NKAP_AGENT_SALT", ServiceHarness.SALT);
        env.put("NKAP_AGENT_STATE_DIR", dir.resolve("state").toString());
        env.put("NKAP_AGENT_TRACES_DIR", "demo/traces");
        env.put("NKAP_AGENT_SCRIPTED_COOLDOWN_SECONDS", "0");
        AgentService restarted = AgentService.start(WebConfig.from(env), null, clock);
        try {
            java.net.http.HttpClient http = java.net.http.HttpClient.newHttpClient();
            java.util.function.BiFunction<String, String, java.net.http.HttpResponse<String>> post = (path, body) -> {
                try {
                    return http.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:" + restarted.port() + path))
                            .header("Content-Type", "application/json").POST(java.net.http.HttpRequest.BodyPublishers.ofString(body)).build(),
                            java.net.http.HttpResponse.BodyHandlers.ofString());
                } catch (Exception e) {
                    throw new AssertionError(e);
                }
            };
            String run = ServiceHarness.JSON.readTree(post.apply("/api/runs", "{\"case\":\"unrecorded-fee\",\"mode\":\"scripted\"}").body()).get("runId").asText();
            java.net.http.HttpResponse<String> r = post.apply("/api/runs/" + run + "/approve", "{}");
            return new ServiceHarness.Reply(r.statusCode(), r.body(), r.headers().map());
        } finally {
            restarted.close();
        }
    }

    @Test
    @DisplayName("Nkap is the source of truth: if it no longer knows the adjustment on record (a reset), the payment is free again; if it cannot be asked, the approval is refused")
    void nkapConfirmsTheRecord() throws Exception {
        start();
        String first = h.run("unrecorded-fee", "scripted").json().get("runId").asText();
        h.post("/api/runs/" + first + "/approve", "{}");
        String second = h.run("unrecorded-fee", "scripted").json().get("runId").asText();

        h.readsFail = true;
        ServiceHarness.Reply unreadable = h.post("/api/runs/" + second + "/approve", "{}");
        assertThat(unreadable.status()).isEqualTo(503);
        assertThat(unreadable.json().get("error").asText()).isEqualTo("unavailable");
        assertThat(h.ledgerEntries).hasValue(1);
        h.readsFail = false;

        h.gone.addAll(h.decided.keySet());
        ServiceHarness.Reply free = h.post("/api/runs/" + second + "/approve", "{}");
        assertThat(free.status()).as("the earlier adjustment is gone, so this payment has none approved").isEqualTo(200);
        assertThat(h.ledgerEntries).hasValue(2);
    }

    @Test
    @DisplayName("a corrupt state file fails closed: approval is refused as unavailable, the run says so, the file is left as it was; self-approve still shows its refusal")
    void corruptStateFile() throws Exception {
        Files.createDirectories(dir.resolve("state"));
        Files.writeString(dir.resolve("state/approved-payments.json"), "garbage");
        start();
        JsonNode run = h.run("unrecorded-fee", "scripted").json();
        assertThat(run.get("approval").get("available").asBoolean()).isFalse();
        assertThat(run.get("approval").get("code").asText()).isEqualTo("unavailable");
        String runId = run.get("runId").asText();
        ServiceHarness.Reply approve = h.post("/api/runs/" + runId + "/approve", "{}");
        assertThat(approve.status()).isEqualTo(503);
        assertThat(h.ledgerEntries).hasValue(0);
        assertThat(h.post("/api/runs/" + runId + "/self-approve", "{}").json().get("code").asText()).isEqualTo("adjustment-self-approval");
        assertThat(Files.readString(dir.resolve("state/approved-payments.json"))).isEqualTo("garbage");
    }

    @Test
    @DisplayName("there is no route that resets, deletes or clears anything")
    void noResetRoute() throws Exception {
        start();
        for (String path : List.of("/api/reset", "/api/runs/reset", "/api/approved", "/api/state", "/api/ledger", "/api/admin/reset")) {
            for (String method : List.of("GET", "POST", "DELETE", "PUT")) {
                assertThat(h.call(method, path, method.equals("GET") || method.equals("DELETE") ? null : "{}", "Content-Type", "application/json").status())
                        .as(method + " " + path).isEqualTo(404);
            }
        }
    }

    @Test
    @DisplayName("a decision needs a run this service made: a guessed id, a malformed one and an expired one are 404, all alike")
    void runIdGuessing() throws Exception {
        start();
        String runId = h.run("unrecorded-fee", "scripted").json().get("runId").asText();
        ServiceHarness.Reply guess = h.post("/api/runs/" + "0".repeat(32) + "/approve", "{}");
        assertThat(guess.status()).isEqualTo(404);
        for (String bad : List.of("short", "../x", "G".repeat(32), "0".repeat(33), runId.toUpperCase(), runId + "x")) {
            ServiceHarness.Reply r = h.post("/api/runs/" + bad + "/approve", "{}");
            assertThat(r.status()).as(bad).isEqualTo(404);
            assertThat(r.body()).as("the same answer as a guess").isEqualTo(guess.body());
        }
        clock.advance(Duration.ofMinutes(31));
        assertThat(h.post("/api/runs/" + runId + "/approve", "{}").status()).as("after 30 minutes").isEqualTo(404);
        assertThat(h.ledgerEntries).hasValue(0);
    }

    @Test
    @DisplayName("a decision request also needs the content type, the size limit and a matching Origin")
    void decisionLimits() throws Exception {
        start();
        String runId = h.run("unrecorded-fee", "scripted").json().get("runId").asText();
        assertThat(h.call("POST", "/api/runs/" + runId + "/approve", "{}", "Content-Type", "text/plain").status()).isEqualTo(415);
        assertThat(h.post("/api/runs/" + runId + "/approve", "{\"x\":\"" + "a".repeat(2000) + "\"}").status()).isEqualTo(413);
        assertThat(h.post("/api/runs/" + runId + "/approve", "{}", "Origin", "http://evil.example").status()).isEqualTo(403);
        assertThat(h.ledgerEntries).hasValue(0);
        assertThat(h.post("/api/runs/" + runId + "/approve", "{}").status()).isEqualTo(200);
    }

    @Test
    @DisplayName("a proposal Nkap refuses is shown with its fixed text, and the run has nothing to decide on")
    void nkapRefusesTheProposal() throws Exception {
        start();
        var original = h.nkap.handler;
        h.nkap.handler = r -> r.method().equals("POST") && r.path().equals("/adjustments")
                ? new dev.nkap.agent.approval.FakeNkap.Answer(400, "{\"type\":\"https://nkap.dev/problems/adjustment-over-ceiling\",\"detail\":\"SERVER-DETAIL\"}")
                : original.apply(r);
        ServiceHarness.Reply reply = h.run("unrecorded-fee", "scripted");
        JsonNode run = reply.json();
        assertThat(run.get("proposal").get("status").asText()).isEqualTo("refused");
        assertThat(run.get("proposal").get("code").asText()).isEqualTo("adjustment-over-ceiling");
        assertThat(run.get("proposal").get("text").asText()).contains("more than");
        assertThat(reply.body()).doesNotContain("SERVER-DETAIL");
        assertThat(run.has("runId")).isFalse();
    }

    // --- budgets, concurrency, live

    @Test
    @DisplayName("scripted runs have a cooldown per visitor (20 s by default) and a daily cap of their own; a refused run is 429 with a fixed text")
    void scriptedBudget() throws Exception {
        start(env -> {
            env.put("NKAP_AGENT_SCRIPTED_COOLDOWN_SECONDS", "20");
            env.put("NKAP_AGENT_SCRIPTED_DAILY_CAP", "3");
        });
        assertThat(h.run("injection", "scripted").status()).isEqualTo(200);
        ServiceHarness.Reply again = h.run("injection", "scripted");
        assertThat(again.status()).isEqualTo(429);
        assertThat(again.json().get("error").asText()).isEqualTo("cooldown");
        assertThat(again.json().get("retryAfterSeconds").asInt()).isBetween(1, 20);
        clock.advance(Duration.ofSeconds(21));
        assertThat(h.run("injection", "scripted").status()).isEqualTo(200);
        clock.advance(Duration.ofSeconds(21));
        assertThat(h.run("injection", "scripted").status()).isEqualTo(200);
        clock.advance(Duration.ofSeconds(21));
        ServiceHarness.Reply capped = h.run("injection", "scripted");
        assertThat(capped.status()).isEqualTo(429);
        assertThat(capped.json().get("error").asText()).isEqualTo("cap-reached");
    }

    @Test
    @DisplayName("live is off without a model key: a live run is refused, nothing runs, and the live budget is not used")
    void liveOff() throws Exception {
        start();
        ServiceHarness.Reply reply = h.run("unrecorded-fee", "live");
        assertThat(reply.status()).isEqualTo(503);
        assertThat(reply.json().get("error").asText()).isEqualTo("live-off");
        assertThat(h.nkap.seen).isEmpty();
        h.close();
        h = null;
        start(env -> {
            env.put("NEBIUS_API_KEY", ServiceHarness.MODEL_KEY);
            env.put("NEBIUS_BASE_URL", "http://example.invalid/v1/");
        });
        assertThat(h.run("unrecorded-fee", "live").status()).as("a base URL that is not https or loopback").isEqualTo(503);
    }

    @Test
    @DisplayName("a live run uses the live budget: the loop runs the model, the cap and the per-visitor cooldown hold, and every step says live")
    void liveRun() throws Exception {
        start(env -> {
            env.put("NEBIUS_API_KEY", ServiceHarness.MODEL_KEY);
            env.put("NEBIUS_BASE_URL", "http://127.0.0.1:9/v1/");
            env.put("NKAP_AGENT_LIVE_DAILY_CAP", "2");
            env.put("NKAP_AGENT_LIVE_COOLDOWN_SECONDS", "300");
        });
        liveAnswers.set(answersFor("unrecorded-fee"));
        ServiceHarness.Reply first = h.run("unrecorded-fee", "live");
        assertThat(first.status()).isEqualTo(200);
        assertThat(first.json().get("mode").asText()).isEqualTo("live");
        first.json().get("steps").forEach(s -> assertThat(s.get("mode").asText()).isEqualTo("live"));
        assertThat(first.json().get("outcome").get("kind").asText()).isEqualTo("PROPOSED");
        assertThat(h.get("/api/status").json().get("liveRunsLeftToday").asInt()).isEqualTo(1);
        ServiceHarness.Reply cooldown = h.run("unrecorded-fee", "live");
        assertThat(cooldown.status()).isEqualTo(429);
        assertThat(cooldown.json().get("error").asText()).isEqualTo("cooldown");
        assertThat(h.get("/api/status").json().get("liveRunsLeftToday").asInt()).as("a refused run costs nothing").isEqualTo(1);
        assertThat(h.run("injection", "scripted").status()).as("scripted runs have their own budget").isEqualTo(200);
    }

    @Test
    @DisplayName("a live run whose model fails ends as FAILED with the reason, shown as a result, never as the model's words or a key")
    void liveFailure() throws Exception {
        h = new ServiceHarness(dir, env -> {
            env.put("NEBIUS_API_KEY", ServiceHarness.MODEL_KEY);
            env.put("NEBIUS_BASE_URL", "http://127.0.0.1:9/v1/");
        }, () -> messages -> new ModelResult.Failed(ModelResult.Reason.HTTP_STATUS, "HTTP 503", null), clock);
        ServiceHarness.Reply reply = h.run("unrecorded-fee", "live");
        assertThat(reply.status()).isEqualTo(200);
        assertThat(reply.json().get("outcome").get("kind").asText()).isEqualTo("FAILED");
        assertThat(reply.json().get("outcome").get("reason").asText()).isEqualTo("HTTP_STATUS");
        assertThat(reply.body()).doesNotContain(ServiceHarness.MODEL_KEY);
        assertThat(reply.json().has("runId")).isFalse();
    }

    @Test
    @DisplayName("at most two runs at once: a third gets 429 busy, and runs again when one has finished")
    void concurrency() throws Exception {
        CountDownLatch entered = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        AgentModel blocking = messages -> {
            entered.countDown();
            try {
                release.await(20, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return new ModelResult.Failed(ModelResult.Reason.TIMEOUT, "released", null);
        };
        h = new ServiceHarness(dir, env -> {
            env.put("NEBIUS_API_KEY", ServiceHarness.MODEL_KEY);
            env.put("NEBIUS_BASE_URL", "http://127.0.0.1:9/v1/");
        }, () -> blocking, clock);
        ExecutorService pool = Executors.newFixedThreadPool(3);
        try {
            List<Future<ServiceHarness.Reply>> running = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                running.add(pool.submit(() -> h.run("unrecorded-fee", "live")));
            }
            assertThat(entered.await(20, TimeUnit.SECONDS)).isTrue();
            ServiceHarness.Reply third = h.run("injection", "scripted");
            assertThat(third.status()).isEqualTo(429);
            assertThat(third.json().get("error").asText()).isEqualTo("busy");
            release.countDown();
            for (Future<ServiceHarness.Reply> f : running) {
                assertThat(f.get(30, TimeUnit.SECONDS).status()).isEqualTo(200);
            }
            assertThat(h.run("injection", "scripted").status()).isEqualTo(200);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    // --- the visitor, the log, the boundary

    @Test
    @DisplayName("a visitor behind the trusted proxy is the last forwarded-for entry; without the proxy configured a forwarded-for header changes nothing")
    void trustedProxyOverHttp() throws Exception {
        start(env -> {
            env.put("NKAP_AGENT_TRUSTED_PROXY", "127.0.0.1");
            env.put("NKAP_AGENT_SCRIPTED_COOLDOWN_SECONDS", "20");
        });
        assertThat(h.run("injection", "scripted", "X-Forwarded-For", "9.9.9.9, 203.0.113.1").status()).isEqualTo(200);
        assertThat(h.run("injection", "scripted", "X-Forwarded-For", "8.8.8.8, 203.0.113.2").status()).as("another visitor").isEqualTo(200);
        assertThat(h.run("injection", "scripted", "X-Forwarded-For", "7.7.7.7, 203.0.113.1").status()).as("the same last entry, whatever precedes it").isEqualTo(429);
        h.close();
        h = null;
        start(env -> env.put("NKAP_AGENT_SCRIPTED_COOLDOWN_SECONDS", "20"));
        assertThat(h.run("injection", "scripted", "X-Forwarded-For", "203.0.113.1").status()).isEqualTo(200);
        assertThat(h.run("injection", "scripted", "X-Forwarded-For", "203.0.113.2").status()).as("the header is ignored").isEqualTo(429);
    }

    @Test
    @DisplayName("the log holds the method, the route shape and the status, and no address, body, id or model text")
    void logLines() throws Exception {
        Logger logger = Logger.getLogger("dev.nkap.agent.web");
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
            start();
            String runId = h.run("unrecorded-fee", "scripted", "X-Forwarded-For", "198.51.100.77").json().get("runId").asText();
            h.post("/api/runs/" + runId + "/approve", "{}");
            h.get("/api/recordings/unrecorded-fee-1");
            h.get("/some/secret/path?x=1");
            h.post("/api/runs", "{\"case\":\"BODY-TEXT-SENTINEL\",\"mode\":\"x\"}");
            String all = String.join("\n", logs);
            assertThat(all).contains("POST /api/runs 200").contains("POST /api/runs/{runId}/approve 200").contains("GET /api/recordings/{id} 200")
                    .contains("POST /api/runs 400");
            assertThat(all).doesNotContain("127.0.0.1").doesNotContain("198.51.100.77").doesNotContain(runId).doesNotContain("BODY-TEXT-SENTINEL")
                    .doesNotContain("secret").doesNotContain(ServiceHarness.AGENT_KEY).doesNotContain(ServiceHarness.APPROVER_KEY)
                    .doesNotContain(ServiceHarness.SALT).doesNotContain("unrecorded-fee-1");
        } finally {
            logger.removeHandler(handler);
        }
    }

    @Test
    @DisplayName("nothing in the web package can reach try-unchecked or the approval command: its classes refer to neither")
    void noRouteReachesTheCommand() throws IOException {
        Path classes = Path.of("target/classes/dev/nkap/agent/web");
        List<String> offenders = new ArrayList<>();
        try (var files = Files.list(classes)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".class")).toList()) {
                String bytes = new String(Files.readAllBytes(file), java.nio.charset.StandardCharsets.ISO_8859_1);
                if (bytes.contains("ApprovalCli") || bytes.contains("try-unchecked") || bytes.contains("tryUnchecked")) {
                    offenders.add(file.getFileName().toString());
                }
            }
        }
        assertThat(offenders).isEmpty();
    }
}

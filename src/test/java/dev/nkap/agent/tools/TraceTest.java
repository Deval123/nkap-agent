package dev.nkap.agent.tools;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TraceTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static JsonNode json(String text) {
        try {
            return JSON.readTree(text);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static Map<String, String> args(String k, String v) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put(k, v);
        return m;
    }

    private final Trace trace = new Trace();

    private ToolResult paymentResult() {
        return trace.record("get_payment", args("reference", "0985e8df-93aa-4949-b7aa-185e19ea41d0"), 200, json("""
                {"state":"UNKNOWN","counterpartyMsisdn":"447700900004","amountMinorUnits":5000,
                 "escalatedAt":"2026-10-13T12:25:38.802249Z",
                 "history":[{"from":"CREATED","to":"UNKNOWN","cause":"SUBMIT_RESPONSE","operatorCode":""}]}
                """));
    }

    @Test
    @DisplayName("call ids are c1, c2, c3 in the order of the calls, and start again from c1 in a new trace")
    void ids() {
        assertThat(paymentResult().callId()).isEqualTo("c1");
        assertThat(paymentResult().callId()).isEqualTo("c2");
        assertThat(paymentResult().callId()).isEqualTo("c3");
        assertThat(new Trace().record("search_docs", Map.of(), 200, json("{}")).callId()).isEqualTo("c1");
    }

    @Test
    @DisplayName("the trace keeps every result, in order, and a result can be found by its call id")
    void keepsResults() {
        ToolResult a = paymentResult();
        ToolResult b = paymentResult();
        assertThat(trace.results()).containsExactly(a, b);
        assertThat(trace.find("c2")).contains(b);
        assertThat(trace.find("c9")).isEmpty();
    }

    @Test
    @DisplayName("what is stored is masked: the trace never holds a long digit run, in arguments or in fields")
    void storesMasked() {
        ToolResult r = trace.record("search_docs", args("query", "who is 447700900004"), 200,
                json("{\"counterpartyMsisdn\":\"447700900004\"}"));
        assertThat(r.fields().at("/counterpartyMsisdn").asText()).isEqualTo("4477******04");
        assertThat(r.arguments().get("query")).isEqualTo("who is 4477******04");
        assertThat(r.toString()).doesNotContain("447700900004");
    }

    @Test
    @DisplayName("a reference that points at a field the result holds, with exactly its value, is accepted")
    void accepts() {
        ToolResult r = paymentResult();
        assertThat(trace.verify(new EvidenceRef(r.callId(), "/state", "UNKNOWN")).ok()).isTrue();
        assertThat(trace.verify(new EvidenceRef(r.callId(), "/history/0/cause", "SUBMIT_RESPONSE")).ok()).isTrue();
        assertThat(trace.verify(new EvidenceRef(r.callId(), "/escalatedAt", "2026-10-13T12:25:38.802249Z")).ok()).isTrue();
        assertThat(trace.verify(new EvidenceRef(r.callId(), "/amountMinorUnits", "5000")).ok()).isTrue();
        assertThat(trace.verify(new EvidenceRef(r.callId(), "/history/0/operatorCode", "")).ok()).isTrue();
    }

    @Test
    @DisplayName("a reference to a path the result does not hold is refused")
    void wrongPath() {
        ToolResult r = paymentResult();
        Verification v = trace.verify(new EvidenceRef(r.callId(), "/history/1/cause", "SUBMIT_RESPONSE"));
        assertThat(v.ok()).isFalse();
        assertThat(v.reason()).contains("no such field");
        assertThat(trace.verify(new EvidenceRef(r.callId(), "/nope", "x")).ok()).isFalse();
    }

    @Test
    @DisplayName("a reference with another value than the field holds is refused")
    void wrongValue() {
        ToolResult r = paymentResult();
        Verification v = trace.verify(new EvidenceRef(r.callId(), "/state", "FAILED"));
        assertThat(v.ok()).isFalse();
        assertThat(v.reason()).contains("value differs");
        assertThat(trace.verify(new EvidenceRef(r.callId(), "/amountMinorUnits", "5001")).ok()).isFalse();
    }

    @Test
    @DisplayName("a reference to a call id that was never made is refused")
    void unknownCall() {
        paymentResult();
        Verification v = trace.verify(new EvidenceRef("c7", "/state", "UNKNOWN"));
        assertThat(v.ok()).isFalse();
        assertThat(v.reason()).contains("unknown call");
    }

    @Test
    @DisplayName("a value that only matches before masking is refused, because the trace holds the masked value")
    void rawValueRefused() {
        ToolResult r = paymentResult();
        assertThat(trace.verify(new EvidenceRef(r.callId(), "/counterpartyMsisdn", "447700900004")).ok()).isFalse();
        assertThat(trace.verify(new EvidenceRef(r.callId(), "/counterpartyMsisdn", "4477******04")).ok()).isTrue();
    }

    @Test
    @DisplayName("a reference to an object or an array, not to a field with a value, is refused")
    void notALeaf() {
        ToolResult r = paymentResult();
        Verification v = trace.verify(new EvidenceRef(r.callId(), "/history/0", "anything"));
        assertThat(v.ok()).isFalse();
        assertThat(v.reason()).contains("not a field with a value");
        assertThat(trace.verify(new EvidenceRef(r.callId(), "", "anything")).ok()).isFalse();
    }

    @Test
    @DisplayName("a path that is not a JSON Pointer is refused, not thrown at the caller")
    void badPointer() {
        ToolResult r = paymentResult();
        Verification v = trace.verify(new EvidenceRef(r.callId(), "history.0.cause", "SUBMIT_RESPONSE"));
        assertThat(v.ok()).isFalse();
        assertThat(v.reason()).contains("not a JSON Pointer");
    }

    @Test
    @DisplayName("a reference with a missing part is refused")
    void nulls() {
        paymentResult();
        assertThat(trace.verify(new EvidenceRef(null, "/state", "UNKNOWN")).ok()).isFalse();
        assertThat(trace.verify(new EvidenceRef("c1", null, "UNKNOWN")).ok()).isFalse();
        assertThat(trace.verify(new EvidenceRef("c1", "/state", null)).ok()).isFalse();
    }

    @Test
    @DisplayName("a truncated value is cited with its marker, exactly as stored")
    void truncatedValue() {
        ToolResult r = trace.record("get_payment", Map.of(), 200,
                json("{\"history\":[{\"operatorCode\":\"" + "z".repeat(900) + "\"}]}"));
        String stored = r.fields().at("/history/0/operatorCode").asText();
        assertThat(stored).endsWith("…[truncated 400 characters]");
        assertThat(trace.verify(new EvidenceRef(r.callId(), "/history/0/operatorCode", stored)).ok()).isTrue();
        assertThat(trace.verify(new EvidenceRef(r.callId(), "/history/0/operatorCode", "z".repeat(900))).ok()).isFalse();
    }

    @Test
    @DisplayName("a result can be rendered as the JSON a model would be given, with the call id, the tool, the status and the fields")
    void rendersForTheModel() throws Exception {
        ToolResult r = paymentResult();
        JsonNode seen = JSON.readTree(r.toJson());
        assertThat(seen.get("callId").asText()).isEqualTo("c1");
        assertThat(seen.get("tool").asText()).isEqualTo("get_payment");
        assertThat(seen.get("status").asInt()).isEqualTo(200);
        assertThat(seen.at("/fields/state").asText()).isEqualTo("UNKNOWN");
        assertThat(seen.at("/arguments/reference").asText()).isEqualTo("0985e8df-93aa-4949-b7aa-185e19ea41d0");
    }
}

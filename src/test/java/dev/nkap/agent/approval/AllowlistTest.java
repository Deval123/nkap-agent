package dev.nkap.agent.approval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AllowlistTest {

    private static final String KEY = "TEST-ONLY-KEY-2b7d";
    private static final String ID = "5d3c1f0e-6a51-4c2b-9d7e-1f2a3b4c5d6e";

    @Test
    @DisplayName("the proposer sends POST /adjustments and GET /adjustments and /adjustments/{uuid}, and nothing else: it throws before any request")
    void proposerAllowlist() throws Exception {
        try (FakeNkap nkap = new FakeNkap()) {
            AdjustmentProposer proposer = new AdjustmentProposer(nkap.url(), () -> KEY);
            List<String[]> refused = List.of(
                    new String[] {"DELETE", "/adjustments"}, new String[] {"PUT", "/adjustments/" + ID},
                    new String[] {"POST", "/adjustments/" + ID + "/approve"}, new String[] {"POST", "/adjustments/" + ID + "/reject"},
                    new String[] {"GET", "/payments/" + ID}, new String[] {"GET", "/statements/imports/" + ID},
                    new String[] {"POST", "/webhooks/events/" + ID + "/replay"}, new String[] {"GET", "/balance"},
                    new String[] {"GET", "/adjustments/"}, new String[] {"GET", "/adjustments?x=1"}, new String[] {"GET", "/adjustments/../payments"},
                    new String[] {"GET", "/adjustments/" + ID + "/"}, new String[] {"GET", "/adjustments/%2e%2e/x"},
                    new String[] {"GET", "/adjustments/" + ID + "%2fapprove"}, new String[] {"GET", "/adjustments/not-a-uuid"},
                    new String[] {"GET", "//adjustments"}, new String[] {"GET", "adjustments"}, new String[] {"GET", "/adjustments/" + ID + "\r\nX: y"},
                    new String[] {"POST", "/adjustments/" + ID}, new String[] {"GET", "/Adjustments"}, new String[] {"HEAD", "/adjustments"});
            for (String[] request : refused) {
                assertThatThrownBy(() -> proposer.send(request[0], request[1], null)).as(request[0] + " " + request[1])
                        .isInstanceOf(IllegalArgumentException.class);
            }
            assertThat(nkap.seen).isEmpty();
            nkap.handler = r -> new FakeNkap.Answer(200, "[]");
            proposer.send("GET", "/adjustments", null);
            proposer.send("GET", "/adjustments/" + ID.toUpperCase(), null);
            proposer.send("POST", "/adjustments", "{}");
            assertThat(nkap.seen).hasSize(3);
        }
    }

    @Test
    @DisplayName("the approver sends POST /adjustments/{uuid}/approve and /reject, and nothing else")
    void approverAllowlist() throws Exception {
        try (FakeNkap nkap = new FakeNkap()) {
            AdjustmentApprover approver = new AdjustmentApprover(nkap.url(), () -> KEY);
            List<String[]> refused = List.of(
                    new String[] {"GET", "/adjustments/" + ID + "/approve"}, new String[] {"POST", "/adjustments"},
                    new String[] {"GET", "/adjustments"}, new String[] {"GET", "/adjustments/" + ID}, new String[] {"POST", "/adjustments/" + ID},
                    new String[] {"POST", "/adjustments/" + ID + "/replay"}, new String[] {"POST", "/adjustments/../approve"},
                    new String[] {"POST", "/adjustments/%2e%2e/approve"}, new String[] {"POST", "/adjustments/x/approve"},
                    new String[] {"POST", "/adjustments/" + ID + "/approve/"}, new String[] {"POST", "/adjustments/" + ID + "/approve?x=1"},
                    new String[] {"POST", "/adjustments/" + ID + "%2fapprove"}, new String[] {"POST", "/payments"},
                    new String[] {"DELETE", "/adjustments/" + ID + "/approve"}, new String[] {"POST", "/webhooks/events/" + ID + "/replay"},
                    new String[] {"POST", "/adjustments/" + ID + "/approve\r\nX: y"});
            for (String[] request : refused) {
                assertThatThrownBy(() -> approver.send(request[0], request[1], null)).as(request[0] + " " + request[1])
                        .isInstanceOf(IllegalArgumentException.class);
            }
            assertThat(nkap.seen).isEmpty();
        }
    }

    @Test
    @DisplayName("the public methods take a parsed UUID, so an id from the model is a string that was parsed first")
    void idsAreUuids() throws Exception {
        assertThat(AdjustmentApprover.class.getMethod("approve", UUID.class)).isNotNull();
        assertThat(AdjustmentApprover.class.getMethod("reject", UUID.class)).isNotNull();
        assertThat(AdjustmentProposer.class.getMethod("get", UUID.class)).isNotNull();
    }
}

package dev.nkap.agent.loop;

import static org.assertj.core.api.Assertions.assertThat;

import dev.nkap.agent.model.Message;
import dev.nkap.agent.model.ModelAnswer;
import dev.nkap.agent.model.ModelResult;
import dev.nkap.agent.model.Usage;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CappedModelTest {

    private static final List<Message> M = List.of(new Message(Message.Role.USER, "x"));
    private static final ModelResult OK = new ModelResult.Answered(new ModelAnswer.CallTool("search_docs", "fee"), new Usage(1, 1));
    private static final ModelResult BAD = new ModelResult.Failed(ModelResult.Reason.HTTP_STATUS, "HTTP 500", null);

    @Test
    @DisplayName("the call after the cap is not passed on: it comes back as CAP_REACHED, and the count says so")
    void cap() {
        ScriptedModel inner = new ScriptedModel().thenForever(new ModelAnswer.CallTool("search_docs", "fee"));
        CappedModel capped = new CappedModel(inner, 3, 3);
        for (int i = 0; i < 3; i++) {
            assertThat(capped.next(M)).isInstanceOf(ModelResult.Answered.class);
        }
        assertThat(capped.stopped()).isFalse();
        ModelResult refused = capped.next(M);
        assertThat(refused).isInstanceOf(ModelResult.Failed.class);
        assertThat(((ModelResult.Failed) refused).reason()).isEqualTo(ModelResult.Reason.CAP_REACHED);
        assertThat(inner.seen).hasSize(3);
        assertThat(capped.calls()).isEqualTo(3);
        assertThat(capped.stopped()).isTrue();
        assertThat(capped.stopReason()).contains("call cap of 3");
    }

    @Test
    @DisplayName("three failed calls in a row stop it; a success in between starts the count again")
    void failuresInARow() {
        ScriptedModel inner = new ScriptedModel().then(BAD).then(BAD).then(OK).then(BAD).then(BAD).then(BAD).then(OK);
        CappedModel capped = new CappedModel(inner, 100, 3);
        for (int i = 0; i < 6; i++) {
            capped.next(M);
        }
        assertThat(capped.stopped()).isTrue();
        assertThat(capped.stopReason()).contains("3 failed calls in a row");
        ModelResult refused = capped.next(M);
        assertThat(((ModelResult.Failed) refused).reason()).isEqualTo(ModelResult.Reason.CAP_REACHED);
        assertThat(inner.seen).as("the seventh was not sent").hasSize(6);
    }
}

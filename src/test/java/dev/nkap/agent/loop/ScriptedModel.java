package dev.nkap.agent.loop;

import dev.nkap.agent.model.AgentModel;
import dev.nkap.agent.model.Message;
import dev.nkap.agent.model.ModelAnswer;
import dev.nkap.agent.model.ModelResult;
import dev.nkap.agent.model.Usage;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/** A model that says what it was told to say, in order, and remembers what it was shown. */
final class ScriptedModel implements AgentModel {

    final List<List<Message>> seen = new ArrayList<>();
    private final Deque<ModelResult> script = new ArrayDeque<>();

    ScriptedModel then(ModelAnswer answer) {
        return then(answer, new Usage(100, 50));
    }

    ScriptedModel then(ModelAnswer answer, Usage usage) {
        script.add(new ModelResult.Answered(answer, usage));
        return this;
    }

    ScriptedModel then(ModelResult result) {
        script.add(result);
        return this;
    }

    /** After the script runs out, every further call gets this answer. */
    private ModelAnswer forever;

    ScriptedModel thenForever(ModelAnswer answer) {
        forever = answer;
        return this;
    }

    @Override
    public ModelResult next(List<Message> messages) {
        seen.add(List.copyOf(messages));
        ModelResult next = script.poll();
        if (next != null) {
            return next;
        }
        if (forever != null) {
            return new ModelResult.Answered(forever, new Usage(100, 50));
        }
        throw new AssertionError("the script has run out after " + seen.size() + " call(s)");
    }
}

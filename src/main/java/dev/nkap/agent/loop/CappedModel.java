package dev.nkap.agent.loop;

import dev.nkap.agent.model.AgentModel;
import dev.nkap.agent.model.Message;
import dev.nkap.agent.model.ModelResult;
import java.util.List;

/**
 * A model that a runner puts a hard cap on: at most {@code maxCalls} calls reach the inner model, and after
 * {@code maxFailuresInARow} failed calls in a row none does. A call refused here comes back as a failure
 * with the reason {@code CAP_REACHED}, never as an exception, and nothing is sent.
 */
public final class CappedModel implements AgentModel {

    private final AgentModel inner;
    private final int maxCalls;
    private final int maxFailuresInARow;
    private int calls;
    private int failuresInARow;
    private String stopReason;

    public CappedModel(AgentModel inner, int maxCalls, int maxFailuresInARow) {
        this.inner = inner;
        this.maxCalls = maxCalls;
        this.maxFailuresInARow = maxFailuresInARow;
    }

    @Override
    public synchronized ModelResult next(List<Message> messages) {
        if (stopReason == null && calls >= maxCalls) {
            stopReason = "the call cap of " + maxCalls + " was reached";
        }
        if (stopReason == null && failuresInARow >= maxFailuresInARow) {
            stopReason = maxFailuresInARow + " failed calls in a row";
        }
        if (stopReason != null) {
            return new ModelResult.Failed(ModelResult.Reason.CAP_REACHED, stopReason, null);
        }
        calls++;
        ModelResult result = inner.next(messages);
        failuresInARow = result instanceof ModelResult.Failed ? failuresInARow + 1 : 0;
        if (failuresInARow >= maxFailuresInARow) {
            stopReason = maxFailuresInARow + " failed calls in a row";
        }
        return result;
    }

    public synchronized int calls() {
        return calls;
    }

    /** True once a call has been refused or the next one would be: the runner should stop starting runs. */
    public synchronized boolean stopped() {
        return stopReason != null;
    }

    public synchronized String stopReason() {
        return stopReason;
    }
}

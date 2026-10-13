package dev.nkap.agent.scenario;

/**
 * The hypotheses Nkap's {@code POST /adjustments} accepts, by the code it expects. The list is Nkap's own
 * ({@code server/.../adjustment/Hypothesis.java}); the catalogue only expects two of them, but a model may
 * name any of the four.
 */
public enum Hypothesis {
    UNRECORDED_FEE("unrecorded_fee"),
    UNATTRIBUTED_MOVEMENT("unattributed_movement"),
    FLOAT_DRIFT("float_drift"),
    DUPLICATE_POSTING("duplicate_posting");

    private final String code;

    Hypothesis(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }
}

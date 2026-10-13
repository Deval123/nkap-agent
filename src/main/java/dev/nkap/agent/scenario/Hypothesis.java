package dev.nkap.agent.scenario;

/** The hypotheses Nkap's {@code POST /adjustments} accepts, by the code it expects. */
public enum Hypothesis {
    UNRECORDED_FEE("unrecorded_fee"),
    DUPLICATE_POSTING("duplicate_posting");

    private final String code;

    Hypothesis(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }
}

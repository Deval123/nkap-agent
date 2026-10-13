package dev.nkap.agent.scenario;

/** What the agent is expected to do in a case. */
public enum Outcome {
    /** Propose the adjustment of {@link ScenarioCase#proposal()}. */
    PROPOSE,
    /** Propose nothing, and say why. */
    NO_PROPOSAL,
    /** Propose as in {@link #PROPOSE}; then the proposing key tries to approve it and is refused. */
    SELF_APPROVAL_REFUSED
}

package dev.nkap.agent.scenario;

/** Where a payment must end up on the gateway once the simulator has done what its rule says. */
public enum Ending {
    /** No operator answer; the reconciler's window runs out and a human is asked to look. */
    ESCALATED,
    /** The operator confirms it. */
    SETTLED,
    /** The operator refuses the submission. */
    FAILED
}

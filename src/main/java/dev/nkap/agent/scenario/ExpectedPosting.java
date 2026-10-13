package dev.nkap.agent.scenario;

/** One side of the adjustment the agent is expected to propose; debits are positive, credits negative. */
public record ExpectedPosting(String account, long amountMinor) {
}

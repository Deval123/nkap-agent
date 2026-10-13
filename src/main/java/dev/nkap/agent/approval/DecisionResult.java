package dev.nkap.agent.approval;

/** What came of an approval or a rejection: decided (for an approval, with the ledger entry the server wrote), or refused. */
public sealed interface DecisionResult {

    record Decided(String ledgerEntryId) implements DecisionResult {
    }

    record Refused(String code, String text) implements DecisionResult {
    }
}

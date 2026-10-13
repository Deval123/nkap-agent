package dev.nkap.agent.scenario;

import java.time.Instant;

/** One line of a statement, with the fields Nkap's CSV format has. */
public record StatementLine(String operatorTransactionId, long amountMinor, long feeMinor, String currency,
                            Instant occurredAt, String status) {

    public StatementLine {
        if (operatorTransactionId == null || !operatorTransactionId.matches("[A-Za-z0-9._-]+")) {
            throw new IllegalArgumentException("a transaction id has no comma, quote or space: " + operatorTransactionId);
        }
    }
}

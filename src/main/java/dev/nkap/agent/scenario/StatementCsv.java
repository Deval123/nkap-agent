package dev.nkap.agent.scenario;

/**
 * Renders a case's statement in the format Nkap's {@code CsvStatementParser} reads: the header below,
 * then one row per line. No quoting, no embedded commas, LF line endings.
 */
public final class StatementCsv {

    /** Copied from {@code CsvStatementParser.HEADER} in Nkap. */
    static final String HEADER = "operator_transaction_id,amount_minor,fee_minor,currency,occurred_at,status";

    private StatementCsv() {
    }

    public static String render(ScenarioCase scenario) {
        if (scenario.lines().isEmpty()) {
            throw new IllegalArgumentException("case " + scenario.id() + " has no statement lines");
        }
        StringBuilder out = new StringBuilder(HEADER).append('\n');
        for (StatementLine line : scenario.lines()) {
            out.append(line.operatorTransactionId()).append(',')
                    .append(line.amountMinor()).append(',')
                    .append(line.feeMinor()).append(',')
                    .append(line.currency()).append(',')
                    .append(line.occurredAt()).append(',')
                    .append(line.status()).append('\n');
        }
        return out.toString();
    }
}

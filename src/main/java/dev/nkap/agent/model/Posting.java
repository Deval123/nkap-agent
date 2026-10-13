package dev.nkap.agent.model;

/** One line of a proposed adjustment: an account, a signed amount in minor units (debits positive), a currency. */
public record Posting(String account, long amountMinor, String currency) {
}

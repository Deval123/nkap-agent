package dev.nkap.agent.scenario;

import java.util.Arrays;
import java.util.Optional;

/**
 * The three families of account an adjustment may touch, which are Nkap's own: the provider's suspense, fee
 * and float accounts, in the payment's currency. A merchant's payable account is never one of them.
 */
public enum AccountKind {
    SUSPENSE("suspense:%s:%s"),
    FEES("fees:%s:%s"),
    PROVIDER_FLOAT("provider:%s:float:%s");

    private final String format;

    AccountKind(String format) {
        this.format = format;
    }

    public String account(String provider, String currency) {
        return format.formatted(provider, currency);
    }

    /** The kind of {@code account} if it is exactly one of the three accounts of this provider and currency. */
    public static Optional<AccountKind> of(String account, String provider, String currency) {
        return Arrays.stream(values()).filter(k -> k.account(provider, currency).equals(account)).findFirst();
    }
}

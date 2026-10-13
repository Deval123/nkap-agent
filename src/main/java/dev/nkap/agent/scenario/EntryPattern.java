package dev.nkap.agent.scenario;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * The shape of the entry a hypothesis stands for: which accounts, and which side of each, with one amount.
 * It is derived from the entry the catalogue expects for that hypothesis, so no amount or account is typed a
 * second time.
 *
 * @param lines each account kind with its sign: {@code 1} a debit, {@code -1} a credit
 */
public record EntryPattern(List<Line> lines) {

    public record Line(AccountKind kind, int sign) {
    }

    public EntryPattern {
        lines = List.copyOf(lines);
    }

    /** The pattern of an expected proposal: its accounts by kind, its amounts by sign, all of one size. */
    public static EntryPattern of(ExpectedProposal proposal) {
        List<Line> lines = new ArrayList<>();
        long size = Math.abs(proposal.postings().get(0).amountMinor());
        for (ExpectedPosting posting : proposal.postings()) {
            if (Math.abs(posting.amountMinor()) != size || size == 0) {
                throw new IllegalArgumentException("a pattern has one amount, and it is not zero");
            }
            lines.add(new Line(kindOf(posting.account()), Long.signum(posting.amountMinor())));
        }
        return new EntryPattern(lines);
    }

    private static AccountKind kindOf(String account) {
        if (account.startsWith("suspense:")) {
            return AccountKind.SUSPENSE;
        }
        if (account.startsWith("fees:")) {
            return AccountKind.FEES;
        }
        if (account.startsWith("provider:") && account.contains(":float:")) {
            return AccountKind.PROVIDER_FLOAT;
        }
        throw new IllegalArgumentException("not an account of a known kind: " + account);
    }

    /**
     * The amount X if {@code postings} are exactly this pattern for this provider and currency, in any order:
     * the same accounts, the same signs, every amount of size X, X above zero. Otherwise empty.
     */
    public Optional<Long> match(List<ExpectedPosting> postings, String provider, String currency) {
        if (postings.size() != lines.size()) {
            return Optional.empty();
        }
        long size = Math.abs(postings.get(0).amountMinor());
        List<Line> found = new ArrayList<>();
        for (ExpectedPosting posting : postings) {
            Optional<AccountKind> kind = AccountKind.of(posting.account(), provider, currency);
            if (kind.isEmpty() || Math.abs(posting.amountMinor()) != size || size == 0) {
                return Optional.empty();
            }
            found.add(new Line(kind.get(), Long.signum(posting.amountMinor())));
        }
        Comparator<Line> order = Comparator.comparing((Line l) -> l.kind().ordinal()).thenComparingInt(Line::sign);
        List<Line> want = new ArrayList<>(lines);
        want.sort(order);
        found.sort(order);
        return want.equals(found) ? Optional.of(size) : Optional.empty();
    }
}

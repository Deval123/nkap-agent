package dev.nkap.agent.loop;

import com.fasterxml.jackson.databind.JsonNode;
import dev.nkap.agent.model.ModelAnswer;
import dev.nkap.agent.model.Posting;
import dev.nkap.agent.scenario.AccountKind;
import dev.nkap.agent.scenario.Catalogue;
import dev.nkap.agent.scenario.EntryPattern;
import dev.nkap.agent.scenario.ExpectedPosting;
import dev.nkap.agent.scenario.Hypothesis;
import dev.nkap.agent.tools.EvidencePointer;
import dev.nkap.agent.tools.EvidenceRef;
import dev.nkap.agent.tools.ToolResult;
import dev.nkap.agent.tools.Trace;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Holds a final answer to rules that need no model. It does not check that the hypothesis is the true one: a
 * wrong but well-formed proposal passes. The human who approves, with a different key, is the last check.
 *
 * <p>What it does check: the accounts are the payment's provider's suspense, fees and float accounts in its
 * currency; the postings balance, are whole and non-zero, and debit no more than the ceiling; the entry is
 * the entry of the hypothesis, read from the catalogue; there is evidence, every pointer resolves in the
 * trace, and the amount of the entry appears as a whole number in a cited value. The payment is the run's.
 */
public final class ProposalValidator {

    /** Nkap's default for {@code nkap.adjustments.max-amount-minor}, which it also checks, on the sum of the debits. */
    public static final long CEILING_MINOR = 100_000;

    private static final Map<Hypothesis, EntryPattern> PATTERNS = Catalogue.patterns();

    /** What the validator concluded. */
    public sealed interface Verdict {
        record Accepted(Proposal proposal) implements Verdict {
        }

        record NoProposal(String explanation) implements Verdict {
        }

        record Rejected(List<Violation> violations) implements Verdict {
            public Rejected {
                violations = List.copyOf(violations);
            }
        }
    }

    private ProposalValidator() {
    }

    public static Verdict validate(ModelAnswer.Final answer, Trace trace, String paymentReference) {
        List<Violation> violations = new ArrayList<>();
        List<EvidenceRef> evidence = resolve(answer.evidence(), trace, violations);

        if (answer.outcome() == ModelAnswer.Verdict.NO_PROPOSAL) {
            if (answer.hypothesis() != null || !answer.postings().isEmpty()) {
                violations.add(Rule.NO_PROPOSAL_CARRIES_ENTRY.violation());
            }
            return violations.isEmpty() ? new Verdict.NoProposal(answer.explanation()) : new Verdict.Rejected(violations);
        }

        Optional<JsonNode> payment = payment(trace, paymentReference);
        String provider = payment.map(p -> p.path("provider").asText("")).orElse("");
        String currency = payment.map(p -> p.path("currency").asText("")).orElse("");
        boolean known = !provider.isBlank() && !currency.isBlank();
        if (!known) {
            violations.add(Rule.NO_PAYMENT_RESULT.violation());
        }

        List<Posting> postings = answer.postings();
        boolean accountsOk = true;
        if (postings.size() < 2) {
            violations.add(Rule.TOO_FEW_POSTINGS.violation());
        }
        long sum = 0;
        long debits = 0;
        boolean zero = false;
        for (Posting posting : postings) {
            sum += posting.amountMinor();
            debits += Math.max(0, posting.amountMinor());
            zero |= posting.amountMinor() == 0;
        }
        if (sum != 0) {
            violations.add(Rule.UNBALANCED.violation());
        }
        if (zero) {
            violations.add(Rule.ZERO_AMOUNT.violation());
        }
        if (debits > CEILING_MINOR) {
            violations.add(Rule.ABOVE_CEILING.violation());
        }
        if (known) {
            if (postings.stream().anyMatch(p -> AccountKind.of(p.account(), provider, currency).isEmpty())) {
                violations.add(Rule.ACCOUNT_NOT_ALLOWED.violation());
                accountsOk = false;
            }
            if (postings.stream().anyMatch(p -> !currency.equals(p.currency()))) {
                violations.add(Rule.CURRENCY_MISMATCH.violation());
            }
        }

        Optional<Long> amount = Optional.empty();
        Hypothesis hypothesis = answer.hypothesis();
        if (hypothesis == null || !PATTERNS.containsKey(hypothesis)) {
            violations.add(hypothesis == null ? Rule.HYPOTHESIS_NO_PATTERN.violation()
                    : hypothesis == Hypothesis.FLOAT_DRIFT
                    ? Rule.HYPOTHESIS_NOT_SUPPORTED.violation(hypothesis.code()
                            + " is a hypothesis Nkap accepts, but this agent does not propose it.")
                    : Rule.HYPOTHESIS_NO_PATTERN.violation(hypothesis.code()
                            + " has no entry pattern here, so its entry cannot be checked."));
        } else if (known && accountsOk && postings.size() >= 2) {
            List<ExpectedPosting> entry = postings.stream().map(p -> new ExpectedPosting(p.account(), p.amountMinor())).toList();
            amount = PATTERNS.get(hypothesis).match(entry, provider, currency);
            if (amount.isEmpty()) {
                violations.add(Rule.PATTERN_MISMATCH.violation(
                        "The entry does not match the " + hypothesis.code() + " pattern."));
            }
        }

        if (evidence.isEmpty() && answer.evidence().isEmpty()) {
            violations.add(Rule.EVIDENCE_MISSING.violation());
        } else if (amount.isPresent() && violations.stream().noneMatch(v -> v.code().equals(Rule.EVIDENCE_UNRESOLVED.code()))
                && !appearsIn(amount.get(), evidence)) {
            violations.add(Rule.EVIDENCE_AMOUNT_ABSENT.violation());
        }

        if (!violations.isEmpty()) {
            return new Verdict.Rejected(unique(violations));
        }
        return new Verdict.Accepted(new Proposal(paymentReference, hypothesis, postings, evidence, answer.explanation()));
    }

    /** The pointers that resolve, as references holding the trace's value. Each one that does not is a violation. */
    private static List<EvidenceRef> resolve(List<EvidencePointer> pointers, Trace trace, List<Violation> violations) {
        List<EvidenceRef> out = new ArrayList<>();
        boolean unresolved = false;
        for (EvidencePointer pointer : pointers) {
            Optional<String> value = trace.resolve(pointer.callId(), pointer.path());
            if (value.isPresent()) {
                out.add(new EvidenceRef(pointer.callId(), pointer.path(), value.get()));
            } else {
                unresolved = true;
            }
        }
        if (unresolved) {
            violations.add(Rule.EVIDENCE_UNRESOLVED.violation());
        }
        return out;
    }

    private static Optional<JsonNode> payment(Trace trace, String reference) {
        return trace.results().stream()
                .filter((ToolResult r) -> r.tool().equals("get_payment") && r.status() == 200
                        && reference.equals(r.arguments().get("reference")))
                .map(ToolResult::fields).findFirst();
    }

    private static boolean appearsIn(long amount, List<EvidenceRef> evidence) {
        Pattern whole = Pattern.compile("(?<![\\d.,])" + amount + "(?!\\d|[.,]\\d)");
        return evidence.stream().anyMatch(e -> whole.matcher(e.value()).find());
    }

    private static List<Violation> unique(List<Violation> violations) {
        return new ArrayList<>(new LinkedHashSet<>(violations));
    }
}

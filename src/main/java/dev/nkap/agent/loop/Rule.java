package dev.nkap.agent.loop;

/**
 * The rules a final answer is held to, each with a fixed code and a fixed text. A violation is made of these
 * and of nothing the model wrote, so what is shown to a human, logged or sent back to the model for repair
 * can never carry the model's own words.
 */
enum Rule {
    NO_PROPOSAL_CARRIES_ENTRY("no-proposal-carries-entry", "A no_proposal answer must not carry a hypothesis or postings."),
    NO_PAYMENT_RESULT("no-payment-result",
            "No successful get_payment result for the payment under investigation is in the trace."),
    HYPOTHESIS_NOT_SUPPORTED("hypothesis-not-supported", "This hypothesis is not supported here."),
    HYPOTHESIS_NO_PATTERN("hypothesis-no-pattern", "This hypothesis has no entry pattern here, so its entry cannot be checked."),
    ACCOUNT_NOT_ALLOWED("account-not-allowed",
            "An account is not the suspense, fees or provider float account of the payment's provider and currency."),
    CURRENCY_MISMATCH("currency-mismatch", "A posting's currency is not the payment's currency."),
    TOO_FEW_POSTINGS("too-few-postings", "An entry has at least two postings."),
    UNBALANCED("unbalanced", "The postings do not sum to zero."),
    ZERO_AMOUNT("zero-amount", "A posting has a zero amount."),
    ABOVE_CEILING("above-ceiling", "The debits exceed the ceiling of " + ProposalValidator.CEILING_MINOR + " minor units."),
    PATTERN_MISMATCH("pattern-mismatch", "The entry does not match the pattern of the hypothesis."),
    EVIDENCE_MISSING("evidence-missing", "A proposal cites at least one piece of evidence."),
    EVIDENCE_UNRESOLVED("evidence-unresolved", "A cited call or path does not resolve in the trace."),
    EVIDENCE_AMOUNT_ABSENT("evidence-amount-absent", "The amount of the entry appears in no cited value.");

    private final String code;
    private final String text;

    Rule(String code, String text) {
        this.code = code;
        this.text = text;
    }

    String code() {
        return code;
    }

    Violation violation() {
        return new Violation(code, text);
    }

    Violation violation(String text) {
        return new Violation(code, text);
    }
}

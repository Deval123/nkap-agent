package dev.nkap.agent.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class EntryPatternTest {

    private static final String P = Catalogue.PROVIDER;
    private static final String C = Catalogue.CURRENCY;

    @Test
    @DisplayName("a pattern is derived for each hypothesis a catalogue case expects, and for no other")
    void derivedFromTheCatalogue() {
        Map<Hypothesis, EntryPattern> patterns = Catalogue.patterns();
        assertThat(patterns.keySet()).containsExactlyInAnyOrder(Hypothesis.UNRECORDED_FEE, Hypothesis.DUPLICATE_POSTING);
        assertThat(patterns.get(Hypothesis.UNRECORDED_FEE).lines()).containsExactlyInAnyOrder(
                new EntryPattern.Line(AccountKind.FEES, 1), new EntryPattern.Line(AccountKind.PROVIDER_FLOAT, -1));
        assertThat(patterns.get(Hypothesis.DUPLICATE_POSTING).lines()).containsExactlyInAnyOrder(
                new EntryPattern.Line(AccountKind.SUSPENSE, 1), new EntryPattern.Line(AccountKind.PROVIDER_FLOAT, -1));
    }

    @Test
    @DisplayName("every catalogue case with a proposal matches the pattern of its hypothesis, with its own amount")
    void everyExpectedEntryMatches() {
        Map<Hypothesis, EntryPattern> patterns = Catalogue.patterns();
        for (ScenarioCase c : Catalogue.all()) {
            c.proposal().ifPresent(p -> {
                Optional<Long> amount = patterns.get(p.hypothesis()).match(p.postings(), P, C);
                assertThat(amount).as(c.id()).isPresent();
                assertThat(amount.get()).as(c.id()).isEqualTo(p.postings().get(0).amountMinor());
            });
        }
    }

    @Test
    @DisplayName("the entry the model booked in the measured run, suspense +75 and fees -75, does not match unrecorded_fee")
    void theMeasuredWrongEntry() {
        List<ExpectedPosting> wrong = List.of(new ExpectedPosting("suspense:" + P + ":" + C, 75),
                new ExpectedPosting("fees:" + P + ":" + C, -75));
        assertThat(Catalogue.patterns().get(Hypothesis.UNRECORDED_FEE).match(wrong, P, C)).isEmpty();
        assertThat(Catalogue.patterns().get(Hypothesis.DUPLICATE_POSTING).match(wrong, P, C)).isEmpty();
    }

    @Test
    @DisplayName("a pattern needs the payment's own provider and currency in the accounts, one amount, and exactly its lines")
    void matchIsExact() {
        EntryPattern fee = Catalogue.patterns().get(Hypothesis.UNRECORDED_FEE);
        String fees = "fees:" + P + ":" + C;
        String fl = "provider:" + P + ":float:" + C;
        assertThat(fee.match(List.of(new ExpectedPosting(fees, 80), new ExpectedPosting(fl, -80)), P, C)).contains(80L);
        assertThat(fee.match(List.of(new ExpectedPosting(fl, -80), new ExpectedPosting(fees, 80)), P, C)).as("order").contains(80L);
        assertThat(fee.match(List.of(new ExpectedPosting(fees, 80), new ExpectedPosting(fl, -81)), P, C)).as("two amounts").isEmpty();
        assertThat(fee.match(List.of(new ExpectedPosting(fees, 80), new ExpectedPosting(fl, -80)), "other", C)).as("provider").isEmpty();
        assertThat(fee.match(List.of(new ExpectedPosting(fees, 80), new ExpectedPosting(fl, -80)), P, "XOF")).as("currency").isEmpty();
        assertThat(fee.match(List.of(new ExpectedPosting(fees, 80)), P, C)).as("one line").isEmpty();
        assertThat(fee.match(List.of(new ExpectedPosting(fees, 40), new ExpectedPosting(fees, 40),
                new ExpectedPosting(fl, -80)), P, C)).as("three lines").isEmpty();
        assertThat(fee.match(List.of(new ExpectedPosting(fees, 0), new ExpectedPosting(fl, 0)), P, C)).as("zero").isEmpty();
    }

    @Test
    @DisplayName("an account kind names its account for a provider and a currency, and recognises only that")
    void accountKinds() {
        assertThat(AccountKind.SUSPENSE.account("p", "XAF")).isEqualTo("suspense:p:XAF");
        assertThat(AccountKind.FEES.account("p", "XAF")).isEqualTo("fees:p:XAF");
        assertThat(AccountKind.PROVIDER_FLOAT.account("p", "XAF")).isEqualTo("provider:p:float:XAF");
        assertThat(AccountKind.of("fees:p:XAF", "p", "XAF")).contains(AccountKind.FEES);
        assertThat(AccountKind.of("fees:q:XAF", "p", "XAF")).isEmpty();
        assertThat(AccountKind.of("payable:acme:XAF", "p", "XAF")).isEmpty();
    }
}

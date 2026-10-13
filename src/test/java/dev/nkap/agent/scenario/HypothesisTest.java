package dev.nkap.agent.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class HypothesisTest {

    @Test
    @DisplayName("the hypotheses are the four codes Nkap's database accepts, no more and no fewer")
    void fourCodes() {
        assertThat(Arrays.stream(Hypothesis.values()).map(Hypothesis::code))
                .containsExactly("unrecorded_fee", "unattributed_movement", "float_drift", "duplicate_posting");
    }
}

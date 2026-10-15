package com.team.rag.agent.context;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TokenEstimatorTest {

    private final TokenEstimator estimator = new TokenEstimator();

    @Test
    void estimatesChineseAndAsciiWithoutReturningZero() {
        assertThat(estimator.estimate("什么是 next-POI recommendation?"))
                .isGreaterThan(5)
                .isLessThan(40);
    }

    @Test
    void returnsZeroForBlankInput() {
        assertThat(estimator.estimate(null)).isZero();
        assertThat(estimator.estimate("   ")).isZero();
    }
}

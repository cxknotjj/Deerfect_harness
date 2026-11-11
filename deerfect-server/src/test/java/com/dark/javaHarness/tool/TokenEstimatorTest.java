package com.dark.javaHarness.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TokenEstimatorTest {

    @Test
    void truncateToTokens_respectsLimit() {
        String text = "x".repeat(100);
        String truncated = TokenEstimator.truncateToTokens(text, 10);

        assertTrue(TokenEstimator.estimateTokens(truncated) <= 10);
        assertTrue(truncated.length() < text.length());
    }

    @Test
    void truncateToTokens_nonPositiveLimit_returnsEmpty() {
        assertEquals("", TokenEstimator.truncateToTokens("abc", 0));
    }
}

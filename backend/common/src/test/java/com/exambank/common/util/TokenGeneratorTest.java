package com.exambank.common.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TokenGeneratorTest {
    @Test
    void producesRandomTokensAndStableNonReversibleDigests() {
        String first = TokenGenerator.generate();
        String second = TokenGenerator.generate();
        assertEquals(64, first.length());
        assertNotEquals(first, second);
        assertEquals(TokenGenerator.digest(first), TokenGenerator.digest(first));
        assertNotEquals(first, TokenGenerator.digest(first));
    }
}

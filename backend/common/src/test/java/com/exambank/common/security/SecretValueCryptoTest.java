package com.exambank.common.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SecretValueCryptoTest {
    private static final String KEY = java.util.Base64.getEncoder().encodeToString(new java.security.SecureRandom().generateSeed(32));

    @Test
    void encryptsWithRandomNonceAndAuthenticatesCiphertext() {
        SecretValueCrypto crypto = new SecretValueCrypto(KEY);
        String first = crypto.encrypt("smtp-authorization-code");
        String second = crypto.encrypt("smtp-authorization-code");

        assertTrue(first.startsWith(SecretValueCrypto.PREFIX));
        assertNotEquals(first, second);
        assertEquals("smtp-authorization-code", crypto.decrypt(first));
        char replacement = first.endsWith("A") ? 'B' : 'A';
        String tampered = first.substring(0, first.length() - 1) + replacement;
        assertThrows(IllegalStateException.class, () -> crypto.decrypt(tampered));
    }

    @Test
    void refusesPlaintextAndMissingKeys() {
        SecretValueCrypto crypto = new SecretValueCrypto("");
        assertFalse(crypto.isConfigured());
        assertThrows(IllegalStateException.class, () -> crypto.encrypt("secret"));
        assertThrows(IllegalStateException.class, () -> crypto.decrypt("plaintext"));
    }
}

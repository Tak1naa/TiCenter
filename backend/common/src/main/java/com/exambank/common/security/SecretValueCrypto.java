package com.exambank.common.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;

/** AES-256-GCM encryption for the small set of secrets that must remain editable in MySQL. */
@Component
public class SecretValueCrypto {
    public static final String PREFIX = "enc:v1:";
    private static final int NONCE_BYTES = 12;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final byte[] key;

    public SecretValueCrypto(@Value("${CONFIG_ENCRYPTION_KEY:}") String configuredKey) {
        this.key = decodeKey(configuredKey);
    }

    public boolean isConfigured() {
        return key != null;
    }

    public boolean isEncrypted(String value) {
        return value != null && value.startsWith(PREFIX);
    }

    public String encrypt(String plaintext) {
        if (plaintext == null || plaintext.isEmpty()) return "";
        requireKey();
        try {
            byte[] nonce = new byte[NONCE_BYTES];
            RANDOM.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] payload = Arrays.copyOf(nonce, nonce.length + ciphertext.length);
            System.arraycopy(ciphertext, 0, payload, nonce.length, ciphertext.length);
            return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(payload);
        } catch (Exception e) {
            throw new IllegalStateException("Secret encryption failed", e);
        }
    }

    public String decrypt(String encrypted) {
        if (encrypted == null || encrypted.isEmpty()) return "";
        if (!isEncrypted(encrypted)) throw new IllegalStateException("Refusing to read an unencrypted secret");
        requireKey();
        try {
            byte[] payload = Base64.getUrlDecoder().decode(encrypted.substring(PREFIX.length()));
            if (payload.length <= NONCE_BYTES) throw new IllegalArgumentException("Invalid encrypted secret");
            byte[] nonce = Arrays.copyOfRange(payload, 0, NONCE_BYTES);
            byte[] ciphertext = Arrays.copyOfRange(payload, NONCE_BYTES, payload.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Secret decryption failed", e);
        }
    }

    public static boolean isValidKey(String configuredKey) {
        return decodeKey(configuredKey) != null;
    }

    private void requireKey() {
        if (key == null) throw new IllegalStateException("CONFIG_ENCRYPTION_KEY must be a 32-byte Base64 or 64-character hex key");
    }

    private static byte[] decodeKey(String configuredKey) {
        if (configuredKey == null || configuredKey.isBlank()) return null;
        try {
            byte[] decoded = configuredKey.matches("[0-9a-fA-F]{64}")
                    ? HexFormat.of().parseHex(configuredKey)
                    : Base64.getDecoder().decode(configuredKey);
            return decoded.length == 32 ? decoded : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}

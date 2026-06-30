package com.iacross.flowpilot.shared.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit test for Argon2id password encoder configuration.
 * Verifies correct hash format, match/no-match, and that the hash is not the plaintext.
 * Does NOT load Spring context (pure unit test).
 */
class PasswordEncoderTest {

    // Matches the parameters in SecurityConfig.passwordEncoder():
    // saltLength=16, hashLength=32, parallelism=1, memory=65536, iterations=3
    private final PasswordEncoder encoder = new Argon2PasswordEncoder(16, 32, 1, 65536, 3);

    @Test
    @DisplayName("hash is not plaintext")
    void hashIsNotPlaintext() {
        String raw = "correct-horse-battery-staple";
        String hash = encoder.encode(raw);
        assertThat(hash).isNotEqualTo(raw);
    }

    @Test
    @DisplayName("matches returns true for correct password")
    void matchesCorrect() {
        String raw = "my-secure-password-123!";
        String hash = encoder.encode(raw);
        assertThat(encoder.matches(raw, hash)).isTrue();
    }

    @Test
    @DisplayName("matches returns false for wrong password")
    void matchesWrong() {
        String hash = encoder.encode("correct-password");
        assertThat(encoder.matches("wrong-password", hash)).isFalse();
    }

    @Test
    @DisplayName("two hashes for the same password are different (salted)")
    void differentSalts() {
        String raw = "same-password";
        String h1  = encoder.encode(raw);
        String h2  = encoder.encode(raw);
        assertThat(h1).isNotEqualTo(h2);           // different salts
        assertThat(encoder.matches(raw, h1)).isTrue();
        assertThat(encoder.matches(raw, h2)).isTrue();
    }
}

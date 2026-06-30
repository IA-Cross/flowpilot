package com.iacross.flowpilot.shared;

import com.iacross.flowpilot.shared.security.AesGcmEncryptor;
import com.iacross.flowpilot.shared.security.EncryptionProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class AesGcmEncryptorTest {

    private AesGcmEncryptor encryptor;

    @BeforeEach
    void setUp() {
        // 32 zero-bytes hex-encoded (same as application-test.yml)
        encryptor = new AesGcmEncryptor(new EncryptionProperties("0".repeat(64)));
    }

    @Test
    void roundTrip_string() {
        String plaintext = "super-secret-bot-token-1234567890";
        byte[] ciphertext = encryptor.encrypt(plaintext);
        assertThat(encryptor.decryptToString(ciphertext)).isEqualTo(plaintext);
    }

    @Test
    void roundTrip_bytes() {
        byte[] data = "some raw bytes data".getBytes();
        byte[] ciphertext = encryptor.encrypt(data);
        assertThat(encryptor.decrypt(ciphertext)).isEqualTo(data);
    }

    @Test
    void eachEncryptProducesDifferentIv() {
        byte[] c1 = encryptor.encrypt("hello");
        byte[] c2 = encryptor.encrypt("hello");
        // Same plaintext → different ciphertext because IV is random
        assertThat(c1).isNotEqualTo(c2);
        // But both decrypt to the same value
        assertThat(encryptor.decryptToString(c1)).isEqualTo("hello");
        assertThat(encryptor.decryptToString(c2)).isEqualTo("hello");
    }

    @Test
    void tamperDetected() {
        byte[] ciphertext = encryptor.encrypt("value");
        // Flip a byte in the ciphertext portion (beyond the 12-byte IV)
        ciphertext[13] ^= 0xFF;
        assertThatThrownBy(() -> encryptor.decrypt(ciphertext))
            .isInstanceOf(RuntimeException.class);
    }

    @Test
    void differentKeys_cannotDecrypt() {
        byte[] ciphertext = encryptor.encrypt("secret");
        var other = new AesGcmEncryptor(new EncryptionProperties("A".repeat(64)));
        assertThatThrownBy(() -> other.decrypt(ciphertext))
            .isInstanceOf(RuntimeException.class);
    }
}

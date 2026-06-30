package com.iacross.flowpilot.shared.security;

import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HexFormat;

/**
 * AES-256-GCM encryption for secrets at rest (NFR-SEC-2).
 *
 * Wire format: [12-byte random IV][ciphertext+16-byte GCM tag]
 * The master key is hex-encoded 32 bytes from {@link EncryptionProperties}.
 *
 * Satisfies: bot token and webhook secret stored only as BYTEA ciphertext.
 */
@Component
public class AesGcmEncryptor {

    private static final String ALGORITHM  = "AES/GCM/NoPadding";
    private static final int    IV_LEN     = 12;
    private static final int    TAG_BITS   = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecretKeySpec keySpec;

    public AesGcmEncryptor(EncryptionProperties props) {
        if (props.masterKey() == null || props.masterKey().isBlank()) {
            throw new IllegalStateException(
                "app.security.encryption.master-key must be set (64 hex chars = 32 bytes)");
        }
        byte[] key = HexFormat.of().parseHex(props.masterKey());
        if (key.length != 32) {
            throw new IllegalStateException(
                "Encryption master key must be exactly 32 bytes (64 hex chars)");
        }
        this.keySpec = new SecretKeySpec(key, "AES");
    }

    /** Encrypt plaintext bytes; returns [IV || ciphertext+tag]. */
    public byte[] encrypt(byte[] plaintext) {
        try {
            byte[] iv = new byte[IV_LEN];
            RANDOM.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext);

            byte[] result = new byte[IV_LEN + ciphertext.length];
            System.arraycopy(iv, 0, result, 0, IV_LEN);
            System.arraycopy(ciphertext, 0, result, IV_LEN, ciphertext.length);
            return result;
        } catch (Exception e) {
            throw new EncryptionException("Encryption failed", e);
        }
    }

    /** Convenience: encrypt a UTF-8 string. */
    public byte[] encrypt(String plaintext) {
        return encrypt(plaintext.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /** Decrypt [IV || ciphertext+tag]; returns original plaintext bytes. */
    public byte[] decrypt(byte[] cipherBlob) {
        try {
            byte[] iv = Arrays.copyOfRange(cipherBlob, 0, IV_LEN);
            byte[] ciphertext = Arrays.copyOfRange(cipherBlob, IV_LEN, cipherBlob.length);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, keySpec, new GCMParameterSpec(TAG_BITS, iv));
            return cipher.doFinal(ciphertext);
        } catch (Exception e) {
            throw new EncryptionException("Decryption failed", e);
        }
    }

    /** Convenience: decrypt and return as UTF-8 string. */
    public String decryptToString(byte[] cipherBlob) {
        return new String(decrypt(cipherBlob), java.nio.charset.StandardCharsets.UTF_8);
    }

    public static class EncryptionException extends RuntimeException {
        public EncryptionException(String msg, Throwable cause) { super(msg, cause); }
    }
}

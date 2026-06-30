package com.iacross.flowpilot.shared.id;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.UUID;

/**
 * Generates time-ordered UUIDv7 values per RFC 9562 §5.7.
 *
 * Layout:
 *   bits 0-47  : Unix timestamp milliseconds (big-endian)
 *   bits 48-51 : version (0b0111)
 *   bits 52-63 : 12 random bits
 *   bits 64-65 : variant (0b10)
 *   bits 66-127: 62 random bits
 *
 * Relocated from identity.domain to shared so every module can use it.
 */
public final class UuidV7 {

    private static final SecureRandom RANDOM = new SecureRandom();

    private UuidV7() {}

    public static UUID generate() {
        long epochMs = Instant.now().toEpochMilli();

        long msb = (epochMs << 16)
                | 0x7000L
                | (RANDOM.nextLong() & 0x0FFFL);

        long lsb = (RANDOM.nextLong() & 0x3FFF_FFFF_FFFF_FFFFL)
                | 0x8000_0000_0000_0000L;

        return new UUID(msb, lsb);
    }
}

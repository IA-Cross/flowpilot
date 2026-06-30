package com.iacross.flowpilot.identity.domain;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.UUID;

/**
 * Generates time-ordered UUIDv7 values.
 *
 * Layout per RFC 9562 §5.7:
 *   bits 0-47  : Unix timestamp milliseconds (big-endian)
 *   bits 48-51 : version (0b0111)
 *   bits 52-63 : 12 random bits
 *   bits 64-65 : variant (0b10)
 *   bits 66-127: 62 random bits
 */
public final class UuidV7 {

    private static final SecureRandom RANDOM = new SecureRandom();

    private UuidV7() {}

    /** Generate a new monotonic, time-ordered UUIDv7. */
    public static UUID generate() {
        long epochMs = Instant.now().toEpochMilli();

        long msb = (epochMs << 16) // top 48 bits: timestamp
                | 0x7000L          // version nibble = 7
                | (RANDOM.nextLong() & 0x0FFFL);  // 12 random bits

        long lsb = (RANDOM.nextLong() & 0x3FFF_FFFF_FFFF_FFFFL) // 62 random bits
                | 0x8000_0000_0000_0000L; // variant = 0b10

        return new UUID(msb, lsb);
    }
}

package com.iacross.flowpilot.identity.domain;

import java.util.UUID;

/** @deprecated Relocated to {@link com.iacross.flowpilot.shared.id.UuidV7}. Use that instead. */
@Deprecated
public final class UuidV7 {

    private UuidV7() {}

    public static UUID generate() {
        return com.iacross.flowpilot.shared.id.UuidV7.generate();
    }
}

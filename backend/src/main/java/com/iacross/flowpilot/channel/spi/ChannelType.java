package com.iacross.flowpilot.channel.spi;

public enum ChannelType {
    TELEGRAM,
    WEB_WIDGET;

    /** DB-stored value (lowercase, matches channel_connection.type CHECK). */
    public String toDbValue() {
        return name().toLowerCase();
    }

    public static ChannelType fromDbValue(String value) {
        return valueOf(value.toUpperCase());
    }
}

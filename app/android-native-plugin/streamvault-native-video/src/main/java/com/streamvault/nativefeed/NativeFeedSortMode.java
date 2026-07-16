package com.streamvault.nativefeed;

public enum NativeFeedSortMode {
    DESC("desc"),
    ASC("asc"),
    RANDOM("random");

    private final String value;

    NativeFeedSortMode(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public NativeFeedSortMode next() {
        if (this == DESC) return ASC;
        if (this == ASC) return RANDOM;
        return DESC;
    }

    public static NativeFeedSortMode from(String raw) {
        if (raw == null) return DESC;
        String value = raw.trim().toLowerCase();
        if ("asc".equals(value) || "sequence".equals(value)) return ASC;
        if ("random".equals(value)) return RANDOM;
        return DESC;
    }
}

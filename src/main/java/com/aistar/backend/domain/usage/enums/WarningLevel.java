package com.aistar.backend.domain.usage.enums;

public enum WarningLevel {
    NONE, WARN, CRITICAL;

    public static WarningLevel from(Double usageRatio) {
        if (usageRatio == null) return NONE;
        if (usageRatio >= 0.95) return CRITICAL;
        if (usageRatio >= 0.8) return WARN;
        return NONE;
    }
}

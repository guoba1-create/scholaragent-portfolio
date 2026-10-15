package com.team.rag.memory;

/** Lifecycle state for a semantic long-term memory. */
public enum MemoryLifecycleStatus {
    ACTIVE,
    CANDIDATE,
    DISABLED;

    public static MemoryLifecycleStatus from(Object value) {
        if (value == null || value.toString().isBlank()) {
            return ACTIVE;
        }
        try {
            return valueOf(value.toString().trim().toUpperCase());
        } catch (IllegalArgumentException ignored) {
            return ACTIVE;
        }
    }
}

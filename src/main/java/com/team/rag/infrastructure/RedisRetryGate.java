package com.team.rag.infrastructure;

import java.time.Duration;

/** Small CLOSED/OPEN/HALF_OPEN gate for optional Redis-backed stores. */
public final class RedisRetryGate {

    private final long retryNanos;
    private volatile boolean healthy = true;
    private volatile long nextRetryAt;

    public RedisRetryGate(Duration retryDelay) {
        this.retryNanos = retryDelay.toNanos();
    }

    public boolean canAttempt() {
        return healthy || System.nanoTime() >= nextRetryAt;
    }

    public void success() {
        healthy = true;
        nextRetryAt = 0L;
    }

    public void failure() {
        healthy = false;
        nextRetryAt = System.nanoTime() + retryNanos;
    }

    public boolean isHealthy() {
        return healthy;
    }
}

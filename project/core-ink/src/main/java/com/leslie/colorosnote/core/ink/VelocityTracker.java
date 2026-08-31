package com.leslie.colorosnote.core.ink;

/**
 * Estimates pen velocity from the most recent input samples using an
 * exponentially weighted average of per-segment velocities. Velocities are
 * expressed in page units per second. Pure JVM.
 */
public final class VelocityTracker {

    /** Number of recent segment velocities kept in the EMA window. */
    private static final float EMA_ALPHA = 0.5f;

    private float lastX;
    private float lastY;
    private long lastTimeMs;
    private float velocityX;
    private float velocityY;
    private boolean hasSample;

    public void reset() {
        hasSample = false;
        velocityX = 0f;
        velocityY = 0f;
    }

    public void addSample(float x, float y, long timestampMs) {
        if (!hasSample) {
            lastX = x;
            lastY = y;
            lastTimeMs = timestampMs;
            hasSample = true;
            return;
        }
        long dtMs = timestampMs - lastTimeMs;
        if (dtMs <= 0) {
            return;
        }
        float vx = (x - lastX) * 1000f / dtMs;
        float vy = (y - lastY) * 1000f / dtMs;
        velocityX += EMA_ALPHA * (vx - velocityX);
        velocityY += EMA_ALPHA * (vy - velocityY);
        lastX = x;
        lastY = y;
        lastTimeMs = timestampMs;
    }

    /** Smoothed horizontal velocity in page units/second. */
    public float getVelocityX() {
        return velocityX;
    }

    /** Smoothed vertical velocity in page units/second. */
    public float getVelocityY() {
        return velocityY;
    }

    /** Smoothed speed (magnitude) in page units/second. */
    public float getSpeed() {
        return (float) Math.hypot(velocityX, velocityY);
    }
}

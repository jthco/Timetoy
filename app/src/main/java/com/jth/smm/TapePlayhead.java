package com.jth.smm;

/** A record-relative playhead, advanced by encoder timestamps rather than UI timers. */
final class TapePlayhead {
    private long recordUs = -1L;
    private double delayUs;
    private double speed = 1.0;

    synchronized void reset() {
        recordUs = -1L;
        delayUs = 0.0;
        speed = 1.0;
    }

    synchronized void setSpeed(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) throw new IllegalArgumentException("Invalid speed");
        speed = value;
    }

    synchronized long select(long currentUs, long oldestUs) {
        if (recordUs >= 0L && currentUs >= recordUs)
            delayUs += (currentUs - recordUs) * (1.0 - speed);
        recordUs = currentUs;
        delayUs = Math.max(0.0, Math.min(Math.max(0L, currentUs - oldestUs), delayUs));
        if (delayUs == 0.0 && speed > 1.0) speed = 1.0;
        return currentUs - Math.round(delayUs);
    }

    synchronized boolean isLive() { return delayUs < 1.0 && speed == 1.0; }
    synchronized long delayMs() { return Math.round(delayUs / 1000.0); }
}

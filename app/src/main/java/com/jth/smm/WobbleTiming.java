package com.jth.smm;

/** Length is a full cycle; depth is the maximum record-relative delay. */
final class WobbleTiming {
    static final long[] PRESETS_MS = {500L, 1000L, 2000L, 4000L, 8000L};

    static boolean isPreset(long ms) {
        for (long value : PRESETS_MS) if (value == ms) return true;
        return false;
    }

    static long delayUs(long elapsedNs, long lengthMs, long depthMs) {
        if (lengthMs <= 0 || depthMs < 0) throw new IllegalArgumentException("Invalid Wobble timing");
        long cycleNs = lengthMs * 1_000_000L;
        long positionNs = elapsedNs % cycleNs;
        if (positionNs < 0) positionNs += cycleNs;
        double phase = positionNs / (double) cycleNs;
        return Math.round(depthMs * 1000.0 * (1.0 - Math.abs(2.0 * phase - 1.0)));
    }
}

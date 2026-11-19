package com.jth.smm;

/** Exposure must fit inside the selected sensor frame period. */
final class CaptureTiming {
    static long frameNs(int fps) {
        if (fps <= 0) throw new IllegalArgumentException("FPS must be positive");
        return 1_000_000_000L / fps;
    }

    static long exposureNs(long requested, int fps, long minimum, long maximum) {
        long upper = Math.min(maximum, frameNs(fps));
        if (minimum > upper) throw new IllegalArgumentException("Exposure cannot fit selected FPS");
        return Math.max(minimum, Math.min(upper, requested));
    }
}

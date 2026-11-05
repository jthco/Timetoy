package com.jth.smm;

/** Run with CaptureTiming.java; no Android runtime is needed. */
public final class CaptureTimingCheck {
    static void equal(long expected, long actual) {
        if (expected != actual) throw new AssertionError(expected + " != " + actual);
    }
    public static void main(String[] args) {
        equal(16_666_666L, CaptureTiming.frameNs(60));
        equal(8_333_333L, CaptureTiming.frameNs(120));
        // Short exposures stay short in low light; frame rate does not change.
        equal(2_000_000L, CaptureTiming.exposureNs(2_000_000L, 60, 100_000L, 100_000_000L));
        // A 1/30 shutter cannot make a 60 or 120 FPS sensor request slow down.
        equal(16_666_666L, CaptureTiming.exposureNs(33_333_333L, 60, 100_000L, 100_000_000L));
        equal(8_333_333L, CaptureTiming.exposureNs(33_333_333L, 120, 100_000L, 100_000_000L));
        equal(100_000L, CaptureTiming.exposureNs(10L, 60, 100_000L, 100_000_000L));
        equal(5_000_000L, CaptureTiming.exposureNs(10_000_000L, 60, 100_000L, 5_000_000L));
        try {
            CaptureTiming.exposureNs(20_000_000L, 120, 10_000_000L, 100_000_000L);
            throw new AssertionError("Impossible exposure range accepted");
        } catch (IllegalArgumentException expected) { }
        try {
            CaptureTiming.frameNs(0);
            throw new AssertionError("Zero FPS accepted");
        } catch (IllegalArgumentException expected) { }
        System.out.println("Capture timing regressions passed");
    }
}

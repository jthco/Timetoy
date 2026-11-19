package com.jth.smm;

public final class WobbleTimingCheck {
    private static void equal(long expected, long actual) {
        if (expected != actual) throw new AssertionError(expected + " != " + actual);
    }

    public static void main(String[] args) {
        long[] expected = {500, 1000, 2000, 4000, 8000};
        if (!java.util.Arrays.equals(expected, WobbleTiming.PRESETS_MS))
            throw new AssertionError("Preset choices changed");
        for (long length : expected) for (long depth : expected) {
            long cycle = length * 1_000_000L;
            equal(0, WobbleTiming.delayUs(0, length, depth));
            equal(depth * 500L, WobbleTiming.delayUs(cycle / 4, length, depth));
            equal(depth * 1000L, WobbleTiming.delayUs(cycle / 2, length, depth));
            equal(depth * 500L, WobbleTiming.delayUs(cycle * 3 / 4, length, depth));
            equal(0, WobbleTiming.delayUs(cycle, length, depth));
            equal(depth * 1000L, WobbleTiming.delayUs(cycle * 5 / 2, length, depth));
            for (int i = 0; i <= 100; i++) {
                long delay = WobbleTiming.delayUs(cycle * i / 100, length, depth);
                if (delay < 0 || delay > depth * 1000L)
                    throw new AssertionError("Delay exceeded Depth");
            }
        }
        if (WobbleTiming.isPreset(0) || WobbleTiming.isPreset(5000))
            throw new AssertionError("Invalid preset accepted");
        System.out.println("All 25 Wobble Length/Depth combinations passed");
    }
}

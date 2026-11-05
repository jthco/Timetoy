package com.jth.smm;

/** Standalone regressions; compile with TapePlayhead.java and run with -ea. */
public final class TapePlayheadCheck {
    private static void equal(long expected, long actual) {
        if (expected != actual) throw new AssertionError(expected + " != " + actual);
    }

    public static void main(String[] args) {
        TapePlayhead head = new TapePlayhead();
        // Entry is Live, with zero relative playhead delay.
        assert head.isLive();
        equal(10_000_000L, head.select(10_000_000L, 0));
        equal(0, head.delayMs());
        // Four seconds held at 1/4 speed produces three seconds of delay.
        head.setSpeed(0.25);
        equal(11_000_000L, head.select(14_000_000L, 0));
        equal(3000, head.delayMs());
        assert !head.isLive();
        // Release preserves delay while the record head continues advancing.
        head.setSpeed(1.0);
        equal(13_000_000L, head.select(16_000_000L, 0));
        equal(3000, head.delayMs());
        // The next tap returns immediately to Live, and another hold works.
        head.reset();
        assert head.isLive();
        equal(16_000_000L, head.select(16_000_000L, 0));
        head.setSpeed(0.5);
        equal(17_000_000L, head.select(18_000_000L, 0));
        // Catch-up returns to Live without overshooting into future frames.
        head.setSpeed(3.0);
        equal(19_000_000L, head.select(19_000_000L, 0));
        assert head.isLive();
        equal(20_000_000L, head.select(20_000_000L, 0));
        // Replay runs backward while held, bounded by available history.
        head.setSpeed(-1.0);
        equal(19_000_000L, head.select(21_000_000L, 0));
        equal(20_500_000L, head.select(22_000_000L, 20_500_000L));
        head.reset();
        equal(0, head.select(0, 0));
        head.setSpeed(0.125);
        equal(125_000L, head.select(1_000_000L, 0));
        try {
            head.setSpeed(Double.NaN);
            throw new AssertionError("NaN accepted");
        } catch (IllegalArgumentException expected) { }
        System.out.println("TapePlayhead regressions passed");
    }
}

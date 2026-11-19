package com.jth.smm;

/** Runs with compiled app classes and android.jar; does not prepare codecs. */
public final class Tape3ConfigCheck {
    public static void main(String[] args) {
        Tape3 tape = new Tape3(1920, 1080, Tape3.CAPTURE_FPS, 20_000_000, null);
        tape.setWobble(8000, 8000);
        if (tape.getLfoPeriodMs() != 8000 || tape.getLfoAmountMs() != 8000 || tape.getDelayMs() != 0)
            throw new AssertionError("Wobble 8-second Length/Depth was clamped or offset");
        try {
            new Tape3(1920, 1080, 60, 20_000_000, null);
            throw new AssertionError("Tape3 accepted 60 FPS");
        } catch (IllegalArgumentException expected) { }
        try {
            tape.setWobble(5000, 1000);
            throw new AssertionError("Invalid Wobble preset accepted");
        } catch (IllegalArgumentException expected) { }
        System.out.println("Tape3 fixed 120 FPS and 8-second Wobble configuration passed");
    }
}

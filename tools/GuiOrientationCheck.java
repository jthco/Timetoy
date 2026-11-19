package com.jth.smm;

/** Checks GUI rotation math; the video surface never uses this transform. */
public final class GuiOrientationCheck {
    static void equal(int expected, int actual) {
        if (expected != actual) throw new AssertionError(expected + " != " + actual);
    }
    public static void main(String[] args) {
        // Portrait-native phone, window fixed to landscape (display turn 90).
        equal(270, GuiOrientation.rotation(0, 90));
        equal(180, GuiOrientation.rotation(90, 90));
        equal(90, GuiOrientation.rotation(180, 90));
        equal(0, GuiOrientation.rotation(270, 90));
        // Landscape-native screen, with no display turn.
        equal(0, GuiOrientation.rotation(0, 0));
        equal(270, GuiOrientation.rotation(90, 0));
        equal(180, GuiOrientation.rotation(180, 0));
        equal(90, GuiOrientation.rotation(270, 0));
        // Quantization around upright and full-turn wrapping.
        equal(270, GuiOrientation.rotation(359, 90));
        equal(270, GuiOrientation.rotation(44, 90));
        equal(180, GuiOrientation.rotation(46, 90));
        for (int display = 0; display < 360; display += 90)
            for (int physical = 0; physical < 360; physical += 90) {
                int turn = GuiOrientation.rotation(physical, display);
                equal(0, (physical + display + turn) % 360);
                if (GuiOrientation.swapsAxes(turn) != (turn == 90 || turn == 270))
                    throw new AssertionError("Incorrect GUI bounds");
            }
        System.out.println("GUI orientation regressions passed");
    }
}

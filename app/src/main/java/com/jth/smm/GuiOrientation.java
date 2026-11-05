package com.jth.smm;

/** Convert physical orientation to a GUI-only turn in a fixed display window. */
final class GuiOrientation {
    static int rotation(int physicalDegrees, int displayDegrees) {
        int quadrant = ((physicalDegrees + 45) / 90 * 90) % 360;
        return (720 - quadrant - displayDegrees) % 360;
    }

    static boolean swapsAxes(int degrees) { return degrees % 180 != 0; }
}

// ============================================================
// SlowMo Lens
// File: LensPlayer.java
// Version: v0.5.0
// Build: Reverse Lens Default
// Date: 2026-07-18
// ============================================================

package com.jth.smm;

public interface LensPlayer {
    void prepareAsync();
    void startPrepared();
    void cancel();
    boolean isPrepared();
}

# Mode cleanup

Audited against master commit 111e7af9fa4f4dc8fd1148177bc432c949e808b8.
No AGENTS.md or other project instructions were present in the repository tree.

## Source audit and decisions

- MainActivity defines the picker, alternate HUD buttons, touch gestures, startup mode,
  mode colors/titles, control visibility, and camera-session selection. The rail now
  offers Slow, Fast, Reverse, Stutter, Wobble, Replay, Molasses, Downshift in that order.
  The old alternate mode button row is hidden so it cannot expose Strobe.
- GLView.LensMode retains internal legacy values to avoid rewriting unrelated engines.
  They are not aliases or entries in the canonical picker. Reverse uses the existing
  chunked DUBBUF_REVERSE engine; its existing duration control remains. No new speed
  setting was added for Reverse.
- Tape3 already implements variable delay plus a triangle-wave LFO. Wobble reuses
  that contiguous engine and preserves its existing delay, period, and amount controls.
  The proposed two-control redesign has not been assumed approved. Existing defaults
  (including zero initial LFO amount) remain; increasing Amount engages modulation.
- Replay and Molasses use Tape3 with fixed gesture speeds: Replay rewinds at 1x,
  Molasses advances at 1/4x while held, and both catch up at 3x after release. These
  defaults add no user-facing settings. Catch-up clamps to Live and history bounds.
- Downshift enters Live at zero relative delay. Holding advances at its selected
  factor, release advances at 1x preserving the accumulated delay, and the next
  touch resets immediately to Live. That reset gesture's release does not re-engage
  playback. Its only mode setting is speed factor (1/8, 1/4, 1/2; default 1/4).
- TapePlayhead tracks record-relative delay using encoder timestamps, bounded by
  the oldest available frame. Camera/session checks and decoder transforms include
  all new Tape3 modes. Manual modes show the direct camera output when Live.
- No mode preference serialization or restoration was found in MainActivity/GLView;
  startup now selects the contiguous Wobble engine. No persisted aliases were added.
- FX remains an existing placeholder. Adjacent FX implementation, Free entitlements,
  and other apps are outside this mode cleanup.

## Validation

The standalone Java 8 playhead regression check passes. It covers zero-delay entry,
1/4-speed hold, preserved delay after release, reset and repeated hold, catch-up,
rewind, history exhaustion, 1/8-speed playback, and invalid input. Run:

```sh
javac -source 8 -target 8 -d /tmp/timetoy-check app/src/main/java/com/jth/smm/TapePlayhead.java tools/TapePlayheadCheck.java
java -ea -cp /tmp/timetoy-check com.jth.smm.TapePlayheadCheck
```

Changed Java sources also passed a javac parser check. A full Android build and
on-device camera/rendering tests have not run: this workspace has no Android SDK
or Gradle checkout. Keep the PR as a draft until the Android build and device
verification pass.

On device, check every picker transition, Wobble delay/LFO controls, manual press,
release and catch-up, and Downshift hold/release/next tap at all factors. Repeat
after leaving/re-entering the mode, camera recovery, orientation change, app pause,
and history exhaustion. Verify legacy modes cannot be selected from visible UI.

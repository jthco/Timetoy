# Mode cleanup

Audited against master commit 111e7af9fa4f4dc8fd1148177bc432c949e808b8.
No AGENTS.md or other project instructions were present in the repository tree.

## Source audit and decisions

- MainActivity defines the picker, alternate HUD buttons, touch gestures, startup mode,
  mode colors/titles, control visibility, and camera-session selection. The rail now
  offers Slow, Fast, Reverse, Stutter, Wobble, Replay in that order.
  Molasses and Downshift are temporarily hidden, with their implementation retained.
  The old alternate mode button row is hidden so it cannot expose Strobe.
- GLView.LensMode retains internal legacy values to avoid rewriting unrelated engines.
  They are not aliases or entries in the canonical picker. Reverse uses the existing
  chunked DUBBUF_REVERSE engine; its existing duration control remains. No new speed
  setting was added for Reverse.
- Wobble uses Tape3's contiguous history and triangle-wave delay. Its two rail
  popup settings are Length (full cycle) and Depth (maximum delay behind Live).
  Both offer 0.5, 1, 2, 4, 8 seconds. Defaults are Length 2 s, Depth 1 s.
  Delay varies from zero to Depth without an additional base delay.
- Tape3 is fixed at 120 FPS, using a supported constrained high-speed configuration
  with an explicit [120,120] request and a 120 FPS encoder. Its FPS menu entry is
  locked; other engines retain their general capture setting. There is no fallback
  to 60 FPS for Tape3. The existing Slow scheduling/playback implementation is unchanged.
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
- FX now offers independent Pop, BW, and Mirror toggles in that order. They apply
  on presentation, including buffered playback, without altering stored frames.
  Free entitlements and other apps remain outside this cleanup.

## Validation

The standalone Java 8 playhead regression check passes. It covers zero-delay entry,
1/4-speed hold, preserved delay after release, reset and repeated hold, catch-up,
rewind, history exhaustion, 1/8-speed playback, and invalid input. Run:

```sh
javac -source 8 -target 8 -d /tmp/timetoy-check app/src/main/java/com/jth/smm/TapePlayhead.java tools/TapePlayheadCheck.java
java -ea -cp /tmp/timetoy-check com.jth.smm.TapePlayheadCheck
```

All 16 app Java sources compile with Java 8 source/target settings against the
Android API 29 android.jar. This is a full Java compilation, not an APK/Gradle build.
WobbleTimingCheck covers all 25 preset pairs, including full-period repetition and
maximum delay. Tape3ConfigCheck verifies the 120 FPS lock, 8-second settings and
zero base delay. Existing playhead, exposure timing and GUI checks also pass.
The GL test covers FX combinations and reflection in portrait/landscape with
identity, inverted and rotated producer transforms. On-device validation remains.

On device, check the six-entry picker, Wobble Length/Depth popups, startup and
camera recovery at requested 120 FPS, and Mirror in portrait and both landscape
orientations. Verify sensor and codec cadence in the trace; a fixed request alone
does not prove actual 120 FPS delivery or full-rate GPU preview delivery.
A depth exceeding currently retained history is limited by that history; Tape3
continues to use its existing 128 MiB compressed ring. Tape120 remains on hold.

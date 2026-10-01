Timetoy 0.6.24 source update
=============================

BASELINE
--------
Apply only to the tested Git baseline:

  7286ff1  Timetoy 0.6.23 tested baseline, yay!

FROM THE AIDE TERMINAL
----------------------
Copy Timetoy_0.6.24.patch into the SMM project directory, then:

  cd /storage/internal_new/project/SMM
  git status
  git apply --check Timetoy_0.6.24.patch
  git apply Timetoy_0.6.24.patch

Then build in AIDE.

After it compiles and you are happy with the source checkpoint:

  git add app/src/main/java/com/jth/smm/MainActivity.java \
          app/src/main/java/com/jth/smm/GLView.java \
          app/src/main/java/com/jth/smm/GLRenderer.java
  git commit -m "Timetoy 0.6.24 Fast view and HUD, yay!"
  git push

0.6.24 INTENT
-------------
- Fast View on the existing 2 s / 720p60 circular history.
- Fast speed 1x, 1.5x, 2x, 3x, 4x.
- Fast reset Time shares the existing 0.5 / 1 / 1.5 / 2 s controls.
- Fast uses the minimum jump-back: (speed - 1) * Time, bounded by 2 s history.
- Reverse defaults to 1.0 s.
- Reverse no longer duplicates 24 Hz source frames to claim 48 Hz; it samples
  preview history toward 48 unique source frames/s and displays each once.
- Legacy Reverse button is removed; that button position becomes Fast.
- Stutter first slice is shifted one slice farther back.
- Freeze captures immediately on entry instead of exposing a live interval first.
- Text-only fixed View rail: TT / View / Time / second parameter / Share.
- Slow rail shows its initial 10x factor.
- Purple Slow wait cue is lighter and begins at recording stop.
- Actual FPS falls back to rolling measured camera-frame arrivals outside clip capture.
- Background camera failures do not start a reopen loop; resume performs one reopen.
- Share is display-only in this build. No rolling output recorder yet.
- No watermark graphic asset work in this build.

NOTE
----
This package is a source patch rather than an APK. The execution environment used
to prepare it can read the exact public Git commit but cannot run AIDE/Android's
device build toolchain. `git apply --check` is intentionally the first command so
AIDE never gets a partially applied source tree if the baseline differs.

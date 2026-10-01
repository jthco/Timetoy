#!/usr/bin/env python3
from pathlib import Path
import re, shutil

ROOT = Path(".")
MAIN = ROOT / "app/src/main/java/com/jth/smm/MainActivity.java"
GLVIEW = ROOT / "app/src/main/java/com/jth/smm/GLView.java"
GLR = ROOT / "app/src/main/java/com/jth/smm/GLRenderer.java"
MANIFEST = ROOT / "app/src/main/AndroidManifest.xml"

for p in (MAIN, GLVIEW, GLR, MANIFEST):
    if not p.exists():
        raise SystemExit(f"Run this from the Timetoy repo root; missing {p}")

def backup(p):
    b = p.with_suffix(p.suffix + ".pre625")
    if not b.exists():
        shutil.copy2(p, b)

def rep(text, old, new, label):
    if old not in text:
        raise SystemExit(f"0.6.25 patch stopped: pattern not found: {label}")
    return text.replace(old, new, 1)

def sub(text, pattern, repl, label):
    out, n = re.subn(pattern, repl, text, count=1, flags=re.S)
    if n != 1:
        raise SystemExit(f"0.6.25 patch stopped: expected one match for {label}, got {n}")
    return out

for p in (MAIN, GLVIEW, GLR, MANIFEST):
    backup(p)

# MainActivity.java
s = MAIN.read_text()

s = rep(s, "// Version: v0.6.24", "// Version: v0.6.25", "Main version comment")
s = rep(s, "// Build: Fast View + Fixed View HUD + Timing Cleanup",
        "// Build: Functional Rack + Mode Colours + Tape Timing",
        "Main build comment")
s = rep(s, '"v0.6.24";', '"v0.6.25";', "VERSION")
s = rep(s, "static final int SLOW_DECODE_MARGIN_MS = 2100;",
        "static final int SLOW_DECODE_MARGIN_MS = 0;", "slow delay")
s = rep(s, "static final int SLOW_SEED_MIN_FRAMES = 110;",
        "static final int SLOW_SEED_MIN_FRAMES = 8;", "slow seed frames")
s = rep(s, "static final long SLOW_SEED_MIN_SPAN_US = 450_000L;",
        "static final long SLOW_SEED_MIN_SPAN_US = 30_000L;", "slow seed span")

s = rep(s,
'''    static final int TIMETOY_VIOLET = 0xff7f3fbf;
    static final int TIMETOY_ORANGE = 0xffff7a1a;''',
'''    static final int TIMETOY_VIOLET = 0xff7f3fbf;
    static final int TIMETOY_ORANGE = 0xffff7a1a;

    static final int MODE_REVERSE = 0xffff3030;
    static final int MODE_SLOW    = 0xff90ee90;
    static final int MODE_FAST    = 0xff168a3f;
    static final int MODE_FREEZE  = 0xff20dfe5;
    static final int MODE_STUTTER = 0xffffdf20;''', "mode colours")

s = rep(s,
"    TextView railView, railTime, railParam, railShare;",
"    TextView railTT, railView, railTime, railParam, railFX, railShare;",
"rack fields")

s = rep(s, "    int playbackFps = 48;", "    int playbackFps = 30;", "default reverse tape rate")
s = rep(s, "    int slowPlaybackMs = 4000;",
        "    int slowPlaybackMs = 2000;\n    int slowFactor = 10;\n    int stutterTimeMs = 1000;",
        "mode defaults")

rack = r'''    void buildModeRail(FrameLayout root) {
        modeRail = new LinearLayout(this);
        modeRail.setOrientation(LinearLayout.VERTICAL);
        modeRail.setPadding(dp(6), dp(6), dp(6), dp(6));
        modeRail.setBackgroundColor(0x66000000);

        railTT = makeRailText("TT");
        railTT.setTextSize(18);
        railView = makeRailText("REVERSE");
        railTime = makeRailText("1.0 S");
        railParam = makeRailText("—");
        railFX = makeRailText("FX");
        railShare = makeRailText("SHARE");

        modeRail.addView(railTT);
        modeRail.addView(railView);
        modeRail.addView(railTime);
        modeRail.addView(railParam);
        modeRail.addView(railFX);
        modeRail.addView(railShare);

        railTT.setOnClickListener(v ->
                new android.app.AlertDialog.Builder(this)
                        .setTitle("TimeToy")
                        .setMessage("TimeToy\\n" + VERSION + "\\nby yo")
                        .setPositiveButton("OK", null)
                        .show());

        railView.setOnClickListener(v -> showModeChoices(railView));
        railTime.setOnClickListener(v -> showTimeChoices(railTime));
        railParam.setOnClickListener(v -> showFactorChoices(railParam));
        railFX.setOnClickListener(v -> showFxChoices(railFX));
        railShare.setOnClickListener(v -> flashStatus("Share — coming later", 900));

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                dp(122), -2, Gravity.TOP | Gravity.LEFT);
        lp.setMargins(dp(10), dp(10), 0, 0);
        root.addView(modeRail, lp);
        updateModeRail();
    }

    void showChoicePopup(View anchor, String[] labels, Runnable[] actions) {
        final PopupWindow[] holder = new PopupWindow[1];
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(4), dp(4), dp(4), dp(4));
        box.setBackgroundColor(0xee202020);

        for (int i = 0; i < labels.length; i++) {
            final int index = i;
            Button b = new Button(this);
            b.setAllCaps(false);
            b.setText(labels[i]);
            b.setTextSize(13);
            b.setMinHeight(0);
            b.setMinimumHeight(0);
            b.setPadding(dp(8), dp(4), dp(8), dp(4));
            b.setOnClickListener(v -> {
                if (actions[index] != null) actions[index].run();
                if (holder[0] != null) holder[0].dismiss();
            });
            box.addView(b, new LinearLayout.LayoutParams(dp(170), dp(44)));
        }

        PopupWindow popup = new PopupWindow(
                box, dp(178), WindowManager.LayoutParams.WRAP_CONTENT, true);
        holder[0] = popup;
        popup.setBackgroundDrawable(
                new android.graphics.drawable.ColorDrawable(0xee202020));
        popup.setOutsideTouchable(true);
        popup.setElevation(dp(6));
        popup.showAsDropDown(anchor, dp(4), -anchor.getHeight());
    }

    void showModeChoices(View anchor) {
        showChoicePopup(anchor,
                new String[]{"REVERSE", "SLOW", "FAST", "FREEZE", "STUTTER"},
                new Runnable[]{
                        () -> setLensMode(GLView.LensMode.DUBBUF_REVERSE),
                        () -> setLensMode(GLView.LensMode.SLOW),
                        () -> setLensMode(GLView.LensMode.FAST),
                        () -> setLensMode(GLView.LensMode.FREEZE),
                        () -> setLensMode(GLView.LensMode.STUTTER)
                });
    }

    void showTimeChoices(View anchor) {
        GLView.LensMode m = glView.getLensMode();
        if (m == GLView.LensMode.SLOW) {
            int[] ms = {500, 1000, 2000, 3000, 4000};
            String[] labels = {"0.5 S", "1.0 S", "2.0 S", "3.0 S", "4.0 S"};
            Runnable[] a = new Runnable[ms.length];
            for (int i = 0; i < ms.length; i++) {
                final int v = ms[i]; a[i] = () -> setModePlaybackDuration(v);
            }
            showChoicePopup(anchor, labels, a);
        } else if (m == GLView.LensMode.FREEZE) {
            int[] ms = {125, 167, 250, 333, 500};
            String[] labels = {"1/8 S", "1/6 S", "1/4 S", "1/3 S", "1/2 S"};
            Runnable[] a = new Runnable[ms.length];
            for (int i = 0; i < ms.length; i++) {
                final int v = ms[i]; a[i] = () -> setModePlaybackDuration(v);
            }
            showChoicePopup(anchor, labels, a);
        } else if (m == GLView.LensMode.STUTTER) {
            int[] ms = {200, 500, 1000, 1500, 2000};
            String[] labels = {"0.2 S", "0.5 S", "1.0 S", "1.5 S", "2.0 S"};
            Runnable[] a = new Runnable[ms.length];
            for (int i = 0; i < ms.length; i++) {
                final int v = ms[i]; a[i] = () -> setModePlaybackDuration(v);
            }
            showChoicePopup(anchor, labels, a);
        } else {
            int[] ms = {500, 1000, 1500, 2000};
            String[] labels = {"0.5 S", "1.0 S", "1.5 S", "2.0 S"};
            Runnable[] a = new Runnable[ms.length];
            for (int i = 0; i < ms.length; i++) {
                final int v = ms[i]; a[i] = () -> setModePlaybackDuration(v);
            }
            showChoicePopup(anchor, labels, a);
        }
    }

    void showFactorChoices(View anchor) {
        GLView.LensMode m = glView.getLensMode();
        if (m == GLView.LensMode.SLOW) {
            int[] f = {4, 6, 8, 10, 12};
            String[] labels = {"4×", "6×", "8×", "10×", "12×"};
            Runnable[] a = new Runnable[f.length];
            for (int i = 0; i < f.length; i++) {
                final int v = f[i]; a[i] = () -> setSlowFactor(v);
            }
            showChoicePopup(anchor, labels, a);
        } else if (m == GLView.LensMode.FAST) {
            float[] f = {1.5f, 2.0f, 2.5f, 3.0f};
            String[] labels = {"1.5×", "2×", "2.5×", "3×"};
            Runnable[] a = new Runnable[f.length];
            for (int i = 0; i < f.length; i++) {
                final float v = f[i]; a[i] = () -> setFastSpeed(v);
            }
            showChoicePopup(anchor, labels, a);
        } else if (m == GLView.LensMode.STUTTER) {
            int[] f = {2, 3, 4, 6, 8};
            String[] labels = {"2×", "3×", "4×", "6×", "8×"};
            Runnable[] a = new Runnable[f.length];
            for (int i = 0; i < f.length; i++) {
                final int v = f[i]; a[i] = () -> setStutterSlices(v);
            }
            showChoicePopup(anchor, labels, a);
        } else {
            flashStatus("No Factor for " + currentLensTitle(), 700);
        }
    }

    void showFxChoices(View anchor) {
        Runnable stub = () -> flashStatus("FX — coming later", 900);
        showChoicePopup(anchor,
                new String[]{"B/W", "Pop", "Mirror"},
                new Runnable[]{stub, stub, stub});
    }

    int modeColor() {
        if (glView == null) return MODE_REVERSE;
        switch (glView.getLensMode()) {
            case SLOW: return MODE_SLOW;
            case FAST: return MODE_FAST;
            case FREEZE: return MODE_FREEZE;
            case STUTTER: return MODE_STUTTER;
            default: return MODE_REVERSE;
        }
    }

    void applyModeColor() {
        int c = modeColor();
        TextView[] rack = {railTT, railView, railTime, railParam, railFX, railShare};
        for (TextView v : rack) {
            if (v != null) {
                v.setBackgroundColor(c);
                v.setTextColor(0xff000000);
            }
        }

        if (recordingFrame != null) {
            android.graphics.drawable.GradientDrawable border =
                    new android.graphics.drawable.GradientDrawable();
            border.setColor(android.graphics.Color.TRANSPARENT);
            border.setStroke(2, c);
            recordingFrame.setBackground(border);
        }
        if (recordCue != null) recordCue.setBackgroundColor(c);
        if (recordProgress != null) recordProgress.setBackgroundColor(c);
    }

    TextView makeRailText(String text) {
'''
s = sub(s,
r'    void buildModeRail\(FrameLayout root\) \{.*?    TextView makeRailText\(String text\) \{\n',
rack, "functional rack")

new_update = r'''    void updateModeRail() {
        if (modeRail == null || glView == null) return;
        GLView.LensMode m = glView.getLensMode();
        String time = "";
        String factor = "—";

        if (m == GLView.LensMode.SLOW) {
            time = formatSeconds(slowPlaybackMs);
            factor = slowFactor + "×";
        } else if (m == GLView.LensMode.DUBBUF_REVERSE) {
            time = formatSeconds(reversePlaybackMs);
        } else if (m == GLView.LensMode.STUTTER) {
            time = formatSeconds(stutterTimeMs);
            factor = stutterSlices + "×";
        } else if (m == GLView.LensMode.FAST) {
            time = formatSeconds(fastTimeMs);
            factor = Math.abs(fastSpeed - Math.round(fastSpeed)) < 0.001f
                    ? String.format(Locale.US, "%.0f×", fastSpeed)
                    : String.format(Locale.US, "%.1f×", fastSpeed);
        } else if (m == GLView.LensMode.FREEZE) {
            float hz = glView.getFreezeFrequency();
            time = formatSeconds(Math.round(1000.0f / Math.max(2.0f, hz)));
        }

        railView.setText(currentLensTitle().toUpperCase(Locale.US));
        railTime.setText(time.toUpperCase(Locale.US));
        railParam.setText(factor);
        railFX.setText("FX");
        railShare.setText("SHARE");
        applyModeColor();
    }

    void buildHud(FrameLayout root) {
'''
s = sub(s,
r'    void updateModeRail\(\) \{.*?    void buildHud\(FrameLayout root\) \{\n',
new_update, "rack state")

s = rep(s,
'''            captureFps = 60; captureWidth = 1280; captureHeight = 720; playbackFps = 48;
            activeTestPreset = "DUBBUF_REVERSE_60_48";''',
'''            captureFps = 60; captureWidth = 1280; captureHeight = 720; playbackFps = 30;
            activeTestPreset = "DUBBUF_REVERSE_60_30_TAPE";''',
"reverse tape mode")

s = rep(s,
'''            activeTestPreset = "STUTTER_HISTORY_720P60_200MS_X4";
            updateControlButtons();
            updateOverlay("Stutter 0.2 s × 4; restarting");''',
'''            stutterTimeMs = 1000;
            stutterSlices = 4;
            glView.setStutterTimeMs(stutterTimeMs);
            glView.setStutterSlices(stutterSlices);
            activeTestPreset = "STUTTER_SLICE_720P60_1000MS_X4";
            updateControlButtons();
            updateOverlay("Stutter 1.0 s × 4; restarting");''',
"stutter defaults")

s = rep(s,
'''        if (mode == GLView.LensMode.SLOW) {
            playbackFps = SLOW_PLAYBACK_FPS;
            captureWidth = 1920;
            captureHeight = 1080;
            recordCueMs = Math.max(0, slowPlaybackMs - Math.max(100, slowPlaybackMs / 10) - SLOW_DECODE_MARGIN_MS);
            activeTestPreset = "SLOW_1080P240_DELAYED";
        } else {''',
'''        if (mode == GLView.LensMode.SLOW) {
            playbackFps = Math.max(1, Math.round((float) SLOW_CAPTURE_FPS / slowFactor));
            captureWidth = 1920;
            captureHeight = 1080;
            recordCueMs = 0;
            activeTestPreset = "SLOW_1080P240_TAPE";
        } else {''',
"slow startup")

s = rep(s,
'''    int effectiveRecordDurationMs() {
        return glView != null && glView.getLensMode() == GLView.LensMode.REVERSE
                ? reverseRecordMs
                : Math.max(100, slowPlaybackMs / 10);
    }''',
'''    int effectiveRecordDurationMs() {
        return glView != null && glView.getLensMode() == GLView.LensMode.REVERSE
                ? reverseRecordMs
                : Math.max(50, slowPlaybackMs / Math.max(1, slowFactor));
    }''',
"slow effective record")

s = rep(s,
'''    void setFastSpeed(float speed) {''',
'''    void setSlowFactor(int factor) {
        slowFactor = Math.max(2, Math.min(16, factor));
        playbackFps = Math.max(1, Math.round((float) SLOW_CAPTURE_FPS / slowFactor));
        captureDurationMs = Math.max(50, slowPlaybackMs / slowFactor);
        recordCueMs = 0;
        if (glView != null && glView.getLensMode() == GLView.LensMode.SLOW) {
            updateControlButtons();
            restartForCaptureOptions("Slow " + slowFactor + "×");
        } else {
            updateControlButtons();
        }
    }

    void setFastSpeed(float speed) {''',
"slow factor setter")

s = rep(s,
'''        if (mode == GLView.LensMode.SLOW) {
            slowPlaybackMs = Math.max(1000, Math.min(4000, durationMs));
            captureDurationMs = Math.max(100, slowPlaybackMs / 10);
            recordCueMs = Math.max(0, slowPlaybackMs - captureDurationMs - SLOW_DECODE_MARGIN_MS);
            activeTestPreset = "SLOW_" + slowPlaybackMs + "MS";
            updateOverlay("Slow Time " + (slowPlaybackMs / 1000.0f) + " s");''',
'''        if (mode == GLView.LensMode.SLOW) {
            slowPlaybackMs = Math.max(500, Math.min(4000, durationMs));
            captureDurationMs = Math.max(50, slowPlaybackMs / Math.max(1, slowFactor));
            recordCueMs = 0;
            activeTestPreset = "SLOW_" + slowPlaybackMs + "MS_X" + slowFactor;
            updateOverlay("Slow Time " + (slowPlaybackMs / 1000.0f) + " s");''',
"slow time choices")

s = rep(s,
'''        } else if (mode == GLView.LensMode.STUTTER) {
            int t = Math.max(500, Math.min(2000, durationMs));
            reversePlaybackMs = t;
            glView.setStutterTimeMs(t);
            updateOverlay("Stutter Time " + (t / 1000.0f) + " s");''',
'''        } else if (mode == GLView.LensMode.STUTTER) {
            int t = Math.max(200, Math.min(2000, durationMs));
            stutterTimeMs = t;
            glView.setStutterTimeMs(t);
            updateOverlay("Stutter slice " + (t / 1000.0f) + " s");''',
"stutter time semantics")

MAIN.write_text(s)

# GLView.java
s = GLVIEW.read_text()
s = rep(s, "// Version: v0.6.24", "// Version: v0.6.25", "GLView version")
s = rep(s, "// Build: Fast View + 48 Hz Reverse + Fixed View HUD",
        "// Build: 30 Hz Reverse Tape + Slice Stutter", "GLView build")
s = rep(s, "dubBufHandler.postDelayed(this, 1000L / 48L);",
        "dubBufHandler.postDelayed(this, 1000L / 30L);", "reverse tick")
s = rep(s,
'TraceLog.i("Stutter started sliceMs=200 passes=4 historyMs=2000");',
'TraceLog.i("Stutter started slice-based; historyMs=2000");',
"stutter trace")
GLVIEW.write_text(s)

# GLRenderer.java
s = GLR.read_text()
s = rep(s, "// Version: v0.6.24", "// Version: v0.6.25", "GLRenderer version")
s = rep(s, "// Build: Fast View + 48 Hz Reverse + Measured Preview FPS",
        "// Build: 30 Hz Reverse Tape + Slice Stutter", "GLRenderer build")
s = rep(s, "    int stutterTimeMs = 800;", "    int stutterTimeMs = 1000;", "stutter default")

s = rep(s,
'''        // Reverse targets 48 unique source frames/s. Sample the preview
        // timestamp stream toward 48 Hz and display each captured frame once.
        dubBufFrames = Math.max(24,
                Math.min(MAX_DUBBUF_FRAMES, playbackMs * 48 / 1000));''',
'''        // Reverse tape follows the unique preview rate observed on this device.
        // Capture and playback both use 30 samples/s.
        dubBufFrames = Math.max(15,
                Math.min(MAX_DUBBUF_FRAMES, playbackMs * 30 / 1000));''',
"reverse tape frames")
s = rep(s,
"        final long intervalNs = 1_000_000_000L / 48L;",
"        final long intervalNs = 1_000_000_000L / 30L;",
"reverse tape capture interval")

s = rep(s,
'''    public boolean advanceStutterPlayback() {
        int sliceFrames = Math.max(1,
                (stutterTimeMs * HISTORY_FPS / 1000) / Math.max(1, stutterSlices));
        if (!stutterEnabled || historyCount < sliceFrames * 2) return false;

        int pass = stutterOutputIndex / sliceFrames;
        int frameInSlice = stutterOutputIndex % sliceFrames;

        // Latch one recent slice at the start of each four-pass cycle, then
        // replay that exact logical range four times while recording continues.
        if (stutterOutputIndex == 0 || stutterAnchorNewestSequence < 0L) {
            stutterAnchorNewestSequence = historyNewestSequence;
        }
        // Start one full slice farther back. The previous implementation's
        // first pass was one slice too close to live.
        long sequence = stutterAnchorNewestSequence -
                (2L * sliceFrames) + 1L + frameInSlice;''',
'''    public boolean advanceStutterPlayback() {
        // Time is one slice. Factor is the number of repeats.
        int sliceFrames = Math.max(1,
                stutterTimeMs * HISTORY_FPS / 1000);
        if (!stutterEnabled || historyCount < sliceFrames) return false;

        int pass = stutterOutputIndex / sliceFrames;
        int frameInSlice = stutterOutputIndex % sliceFrames;

        if (stutterOutputIndex == 0 || stutterAnchorNewestSequence < 0L) {
            stutterAnchorNewestSequence = historyNewestSequence;
        }
        long sequence = stutterAnchorNewestSequence -
                sliceFrames + 1L + frameInSlice;''',
"slice-based stutter")
GLR.write_text(s)

# Orientation
s = MANIFEST.read_text()
s, n = re.subn(r'\s+android:screenOrientation="landscape"', '', s, count=1)
if n != 1:
    raise SystemExit("0.6.25 patch stopped: landscape orientation lock not found")
MANIFEST.write_text(s)

# Terminology
for p in (MAIN, GLVIEW, GLR):
    t = p.read_text()
    t = t.replace("Needle", "Tape").replace("needle", "tape")
    p.write_text(t)

print("TimeToy 0.6.25 patch applied.")
print("Backups written as *.pre625")
print("Next:")
print("  git diff --check")
print("  git diff --stat")
print("  ./gradlew assembleDebug")

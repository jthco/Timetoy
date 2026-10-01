#!/usr/bin/env python3
from pathlib import Path
import subprocess, sys

ROOT = Path(".").resolve()
FILES = {
    "MainActivity.java": ROOT / "app/src/main/java/com/jth/smm/MainActivity.java",
    "GLView.java": ROOT / "app/src/main/java/com/jth/smm/GLView.java",
    "GLRenderer.java": ROOT / "app/src/main/java/com/jth/smm/GLRenderer.java",
}

def fail(msg):
    print("ERROR:", msg, file=sys.stderr)
    sys.exit(1)

try:
    head = subprocess.check_output(["git", "rev-parse", "--short", "HEAD"], text=True).strip()
except Exception as e:
    fail(f"Not in a Git working tree: {e}")

if head != "7286ff1":
    fail(f"Expected baseline 7286ff1, found {head}")

status = subprocess.check_output(["git", "status", "--porcelain"], text=True)
tracked_changes = [line for line in status.splitlines() if not line.startswith("??")]
if tracked_changes:
    fail("Tracked files are already modified. Start from the clean 0.6.23 baseline.")

for name, path in FILES.items():
    if not path.exists():
        fail(f"Missing {path}")

def load(path):
    # Normalize line endings so AIDE/Git CRLF differences cannot break edits.
    return path.read_text(encoding="utf-8").replace("\r\n", "\n")

def replace_once(text, old, new, label):
    # Prefer an exact replacement. If Git/AIDE has harmless whitespace
    # differences, fall back to a whitespace-tolerant match.
    count = text.count(old)
    if count == 1:
        return text.replace(old, new, 1)
    if count > 1:
        fail(f"{label}: expected source block once, found {count}")

    import re
    # Escape all source characters, then let every run of whitespace match
    # any run of whitespace. This stays strict about Java tokens/punctuation
    # while tolerating CRLF, tabs and formatting differences.
    pieces = re.split(r'(\\s+)', old)
    pattern = ''.join(r'\\s+' if re.fullmatch(r'\\s+', p) else re.escape(p)
                      for p in pieces if p != '')
    matches = list(re.finditer(pattern, text, flags=re.MULTILINE))
    if len(matches) != 1:
        fail(f"{label}: exact match 0; whitespace-tolerant matches {len(matches)}")
    m = matches[0]
    return text[:m.start()] + new + text[m.end():]

# ----------------------------------------------------------------------
# GLView.java
# ----------------------------------------------------------------------
p = FILES["GLView.java"]
s = load(p)

s = replace_once(s,
"""// Version: v0.6.23
// Build: Standard Controls + Watermark + Mode Isolation
// Date: 2026-08-06
""",
"""// Version: v0.6.24
// Build: Fast View + 48 Hz Reverse + Fixed View HUD
// Date: 2026-08-09
""", "GLView header")

s = replace_once(s,
"""    public enum LensMode { REVERSE, DUBBUF_REVERSE, SLOW, FREEZE, STUTTER }
    private volatile LensMode lensMode = LensMode.DUBBUF_REVERSE;
""",
"""    public enum LensMode { REVERSE, DUBBUF_REVERSE, SLOW, FREEZE, STUTTER, FAST }
    private volatile LensMode lensMode = LensMode.DUBBUF_REVERSE;
""", "GLView enum")

s = replace_once(s,
"""    private volatile int dubBufPlaybackMs = 2000;
""",
"""    private volatile int dubBufPlaybackMs = 1000;
""", "GLView reverse default")

s = replace_once(s,
"""    private final Handler freezeHandler = new Handler(Looper.getMainLooper());
""",
"""    private final Handler fastHandler = new Handler(Looper.getMainLooper());
    private volatile boolean fastRunning = false;
    private volatile Runnable fastFirstPlayback;
    private final Runnable fastTick = new Runnable() {
        @Override public void run() {
            if (!fastRunning || lensMode != LensMode.FAST) return;
            queueEvent(() -> {
                boolean first = renderer.advanceFastPlayback();
                requestRender();
                if (first && fastFirstPlayback != null) {
                    Runnable r = fastFirstPlayback;
                    fastFirstPlayback = null;
                    post(r);
                }
            });
            fastHandler.postDelayed(this, 1000L / 60L);
        }
    };
    private final Handler freezeHandler = new Handler(Looper.getMainLooper());
""", "GLView Fast handler")

s = replace_once(s,
"""        if (mode != LensMode.FREEZE) stopFreeze();
        if (mode != LensMode.DUBBUF_REVERSE) stopDubBufReverse();
        if (mode != LensMode.STUTTER) stopStutter();
        lensMode = mode;
""",
"""        if (mode != LensMode.FREEZE) stopFreeze();
        if (mode != LensMode.DUBBUF_REVERSE) stopDubBufReverse();
        if (mode != LensMode.STUTTER) stopStutter();
        if (mode != LensMode.FAST) stopFast();
        lensMode = mode;
""", "GLView mode isolation")

s = replace_once(s,
"""    public void startDubBufReverse(int playbackMs, Runnable onFirstPlayback) {
        stopFreeze();
        stopStutter();
        releaseAllPlayers();
""",
"""    public void startDubBufReverse(int playbackMs, Runnable onFirstPlayback) {
        stopFreeze();
        stopStutter();
        stopFast();
        releaseAllPlayers();
""", "GLView reverse isolation")

s = replace_once(s,
"""    public void startStutter(Runnable onFirstPlayback) {
        stopFreeze();
        stopDubBufReverse();
        releaseAllPlayers();
""",
"""    public void startStutter(Runnable onFirstPlayback) {
        stopFreeze();
        stopDubBufReverse();
        stopFast();
        releaseAllPlayers();
""", "GLView stutter isolation")

s = replace_once(s,
"""    public void stopStutter() {
        stutterRunning = false;
        stutterHandler.removeCallbacks(stutterTick);
        stutterFirstPlayback = null;
        if (renderer != null) queueEvent(() -> renderer.releaseStutterHistory());
    }
    public void startFreeze(float frequencyHz) {
""",
"""    public void stopStutter() {
        stutterRunning = false;
        stutterHandler.removeCallbacks(stutterTick);
        stutterFirstPlayback = null;
        if (renderer != null && lensMode != LensMode.FAST)
            queueEvent(() -> renderer.releaseStutterHistory());
    }
    public void startFast(Runnable onFirstPlayback) {
        stopFreeze();
        stopDubBufReverse();
        stopStutter();
        releaseAllPlayers();
        lensMode = LensMode.FAST;
        fastFirstPlayback = onFirstPlayback;
        fastRunning = true;
        fastHandler.removeCallbacks(fastTick);
        queueEvent(() -> {
            renderer.beginFast();
            requestRender();
        });
        fastHandler.post(fastTick);
        TraceLog.i("Fast started historyMs=2000");
    }
    public void setFastTimeMs(int timeMs) {
        if (renderer != null) queueEvent(() -> renderer.setFastTimeMs(timeMs));
    }
    public void setFastSpeed(float speed) {
        if (renderer != null) queueEvent(() -> renderer.setFastSpeed(speed));
    }
    public void stopFast() {
        fastRunning = false;
        fastHandler.removeCallbacks(fastTick);
        fastFirstPlayback = null;
        if (renderer != null && lensMode != LensMode.STUTTER)
            queueEvent(() -> renderer.releaseStutterHistory());
    }
    public void startFreeze(float frequencyHz) {
""", "GLView Fast methods")

s = replace_once(s,
"""        setFreezeFrequency(frequencyHz);
        stopDubBufReverse();
        stopStutter();
        releaseAllPlayers();
        lensMode = LensMode.FREEZE;
        freezeRunning = true;
        freezeHandler.removeCallbacks(freezeTick);
        queueEvent(() -> {
            renderer.showCameraOutput();
            requestRender();
        });
""",
"""        setFreezeFrequency(frequencyHz);
        stopDubBufReverse();
        stopStutter();
        stopFast();
        releaseAllPlayers();
        lensMode = LensMode.FREEZE;
        freezeRunning = true;
        freezeHandler.removeCallbacks(freezeTick);
        // Capture immediately so Freeze never exposes a live-camera interval
        // while taking ownership of the display.
        queueEvent(() -> {
            renderer.captureLiveFrameToFreezeTexture();
            requestRender();
        });
""", "GLView Freeze first frame")

p.write_text(s, encoding="utf-8", newline="\n")

# ----------------------------------------------------------------------
# GLRenderer.java
# ----------------------------------------------------------------------
p = FILES["GLRenderer.java"]
s = load(p)

s = replace_once(s,
"""// Version: v0.6.23
// Build: Standard Controls + Watermark + Mode Isolation
// Date: 2026-08-06
""",
"""// Version: v0.6.24
// Build: Fast View + 48 Hz Reverse + Measured Preview FPS
// Date: 2026-08-09
""", "GLRenderer header")

s = replace_once(s,
"""    int dubBufPlayIndex = -1; // output tick index; each captured frame is shown twice
""",
"""    int dubBufPlayIndex = -1;
""", "GLRenderer reverse index comment")

s = replace_once(s,
"""    long dubBufLastCameraTimestampNs = -1L;
    int dubBufPlayCycle = -1;
""",
"""    long dubBufLastCameraTimestampNs = -1L;
    long dubBufNextCaptureTimestampNs = -1L;
    int dubBufPlayCycle = -1;
""", "GLRenderer reverse sampling state")

s = replace_once(s,
"""    int stutterCycle = 0;
    long stutterAnchorNewestSequence = -1L;

    int freezeTexId;
""",
"""    int stutterCycle = 0;
    long stutterAnchorNewestSequence = -1L;
    boolean fastEnabled = false;
    boolean fastFirstPlaybackReported = false;
    int fastTimeMs = 1000;
    float fastSpeed = 2.0f;
    int fastOutputIndex = 0;
    int fastCycle = 0;
    long fastAnchorNewestSequence = -1L;

    int freezeTexId;
""", "GLRenderer Fast state")

s = replace_once(s,
"""    int drawCount = 0, cameraFrameCount = 0;
    final int[] decoderFrameCounts = new int[2];
    long startNs = 0, lastDrawNs = 0;
""",
"""    int drawCount = 0, cameraFrameCount = 0;
    final int[] decoderFrameCounts = new int[2];
    long startNs = 0, lastDrawNs = 0;
    long cameraFpsWindowStartNs = 0L;
    int cameraFpsWindowFrames = 0;
    volatile double measuredCameraFps = 0.0;
""", "GLRenderer FPS state")

s = replace_once(s,
"""            cameraFrameCount++;
            if (dubBufEnabled) captureCameraFrameToDubBuf();
            if (historyEnabled) captureCameraFrameToHistory();
""",
"""            cameraFrameCount++;
            long fpsNow = System.nanoTime();
            if (cameraFpsWindowStartNs == 0L) cameraFpsWindowStartNs = fpsNow;
            cameraFpsWindowFrames++;
            long fpsSpan = fpsNow - cameraFpsWindowStartNs;
            if (fpsSpan >= 500_000_000L) {
                measuredCameraFps =
                        cameraFpsWindowFrames * 1_000_000_000.0 / fpsSpan;
                cameraFpsWindowStartNs = fpsNow;
                cameraFpsWindowFrames = 0;
            }
            if (dubBufEnabled) captureCameraFrameToDubBuf();
            if (historyEnabled) captureCameraFrameToHistory();
""", "GLRenderer measured FPS")

s = replace_once(s,
"""        // The S25 preview-only path delivers about 30 unique frames/s even when
        // requested at 60. Capture 24 source frames per playback second and
        // show each source frame twice at the 48 Hz display cadence.
        dubBufFrames = Math.max(12, Math.min(MAX_DUBBUF_FRAMES, playbackMs * 24 / 1000));
""",
"""        // Reverse targets 48 unique source frames/s. Sample the preview
        // timestamp stream toward 48 Hz and display each captured frame once.
        dubBufFrames = Math.max(24,
                Math.min(MAX_DUBBUF_FRAMES, playbackMs * 48 / 1000));
""", "GLRenderer Reverse 48 allocation")

s = replace_once(s,
"""        dubBufFirstPlaybackReported = false; dubBufLastCameraTimestampNs = -1L;
        dubBufPlayCycle = -1; dubBufRecordCycle = 1;
""",
"""        dubBufFirstPlaybackReported = false; dubBufLastCameraTimestampNs = -1L;
        dubBufNextCaptureTimestampNs = -1L;
        dubBufPlayCycle = -1; dubBufRecordCycle = 1;
""", "GLRenderer Reverse sample init")

s = replace_once(s,
"""        if (ts == dubBufLastCameraTimestampNs) return;
        dubBufLastCameraTimestampNs = ts;
        if (dubBufWriteReady || dubBufWriteIndex >= dubBufFrames) return;
""",
"""        if (ts == dubBufLastCameraTimestampNs) return;
        dubBufLastCameraTimestampNs = ts;
        final long intervalNs = 1_000_000_000L / 48L;
        if (dubBufNextCaptureTimestampNs < 0L)
            dubBufNextCaptureTimestampNs = ts;
        if (ts < dubBufNextCaptureTimestampNs) return;
        do {
            dubBufNextCaptureTimestampNs += intervalNs;
        } while (dubBufNextCaptureTimestampNs <= ts);
        if (dubBufWriteReady || dubBufWriteIndex >= dubBufFrames) return;
""", "GLRenderer Reverse sample")

s = replace_once(s,
"""                dubBufReadReady = true;
                dubBufPlayIndex = dubBufFrames * 2 - 1;
""",
"""                dubBufReadReady = true;
                dubBufPlayIndex = dubBufFrames - 1;
""", "GLRenderer Reverse first page")

s = replace_once(s,
"""        int sourceIndex = dubBufPlayIndex / 2;
        currentSource = dubBufSources[dubBufReadPage][sourceIndex];
""",
"""        int sourceIndex = dubBufPlayIndex;
        currentSource = dubBufSources[dubBufReadPage][sourceIndex];
""", "GLRenderer Reverse unique playback")

s = s.replace("dubBufPlayIndex = dubBufFrames * 2 - 1;",
              "dubBufPlayIndex = dubBufFrames - 1;")

s = replace_once(s,
"""        int sliceFrames = Math.max(1, (stutterTimeMs * HISTORY_FPS / 1000) / Math.max(1, stutterSlices));
        if (!stutterEnabled || historyCount < sliceFrames) return false;
""",
"""        int sliceFrames = Math.max(1,
                (stutterTimeMs * HISTORY_FPS / 1000) / Math.max(1, stutterSlices));
        if (!stutterEnabled || historyCount < sliceFrames * 2) return false;
""", "GLRenderer Stutter history requirement")

s = replace_once(s,
"""        long sequence = stutterAnchorNewestSequence - sliceFrames + 1L + frameInSlice;
""",
"""        // Start one full slice farther back. The previous implementation's
        // first pass was one slice too close to live.
        long sequence = stutterAnchorNewestSequence -
                (2L * sliceFrames) + 1L + frameInSlice;
""", "GLRenderer Stutter offset")

s = replace_once(s,
"""    public int getStutterCycle() { return stutterCycle; }
    public void releaseStutterHistory() {
        historyEnabled = false;
        stutterEnabled = false;
""",
"""    public int getStutterCycle() { return stutterCycle; }

    public void beginFast() {
        releaseStutterHistory();
        for (int i = 0; i < HISTORY_FRAMES; i++) {
            int id = make2dTexture();
            historyTexIds[i] = id;
            historySources[i] =
                    new SimpleTextureSource(id, GLES20.GL_TEXTURE_2D);
            android.opengl.Matrix.setIdentityM(historySources[i].matrix, 0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, id);
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA,
                    HISTORY_WIDTH, HISTORY_HEIGHT, 0, GLES20.GL_RGBA,
                    GLES20.GL_UNSIGNED_BYTE, null);
        }
        historyNewestSequence = -1L;
        historyCount = 0;
        historyLastCameraTimestampNs = -1L;
        historyEnabled = true;
        fastEnabled = true;
        fastFirstPlaybackReported = false;
        fastOutputIndex = 0;
        fastCycle = 0;
        fastAnchorNewestSequence = -1L;
        showCameraOutput();
        TraceLog.i("Fast history allocated frames=" + HISTORY_FRAMES +
                " size=" + HISTORY_WIDTH + "x" + HISTORY_HEIGHT);
    }

    public boolean advanceFastPlayback() {
        if (!fastEnabled) return false;
        if (fastSpeed <= 1.0001f) {
            showCameraOutput();
            boolean first = !fastFirstPlaybackReported;
            fastFirstPlaybackReported = true;
            return first;
        }

        int cycleFrames = Math.max(1,
                Math.round(fastTimeMs * HISTORY_FPS / 1000.0f));
        int jumpBackFrames = Math.max(0,
                Math.round((fastSpeed - 1.0f) * cycleFrames));
        if (historyCount <= jumpBackFrames + 1) return false;

        if (fastOutputIndex == 0 || fastAnchorNewestSequence < 0L)
            fastAnchorNewestSequence = historyNewestSequence;

        long sequence = fastAnchorNewestSequence - jumpBackFrames +
                Math.round(fastOutputIndex * fastSpeed);
        long oldest = historyNewestSequence - historyCount + 1L;
        if (sequence < oldest) return false;
        if (sequence > historyNewestSequence) sequence = historyNewestSequence;

        int slot = (int) (sequence % HISTORY_FRAMES);
        currentSource = historySources[slot];
        visibleDecoderSlot = -5;
        report = "Fast cycle " + (fastCycle + 1) + " " + fastSpeed + "x";

        boolean first = !fastFirstPlaybackReported;
        fastFirstPlaybackReported = true;
        fastOutputIndex++;
        if (fastOutputIndex >= cycleFrames) {
            fastOutputIndex = 0;
            fastAnchorNewestSequence = -1L;
            fastCycle++;
            TraceLog.i("Fast cycle complete=" + fastCycle);
        }
        return first;
    }

    public void setFastTimeMs(int timeMs) {
        fastTimeMs = Math.max(100, Math.min(2000, timeMs));
        if (fastSpeed > 1.0f &&
                (fastSpeed - 1.0f) * fastTimeMs > 2000.0f)
            fastSpeed = 1.0f + 2000.0f / fastTimeMs;
        fastOutputIndex = 0;
        fastAnchorNewestSequence = -1L;
        TraceLog.i("Fast Time=" + fastTimeMs + "ms speed=" + fastSpeed);
    }

    public void setFastSpeed(float speed) {
        fastSpeed = Math.max(1.0f, Math.min(4.0f, speed));
        if (fastSpeed > 1.0f &&
                (fastSpeed - 1.0f) * fastTimeMs > 2000.0f)
            fastTimeMs = Math.max(100,
                    Math.round(2000.0f / (fastSpeed - 1.0f)));
        fastOutputIndex = 0;
        fastAnchorNewestSequence = -1L;
        TraceLog.i("Fast Speed=" + fastSpeed + "x timeMs=" + fastTimeMs);
    }

    public int getFastCycle() { return fastCycle; }

    public void releaseStutterHistory() {
        historyEnabled = false;
        stutterEnabled = false;
        fastEnabled = false;
""", "GLRenderer Fast implementation")

s = replace_once(s,
"""        stutterOutputIndex = 0;
        stutterAnchorNewestSequence = -1L;
        if (visibleDecoderSlot == -4) showCameraOutput();
""",
"""        stutterOutputIndex = 0;
        stutterAnchorNewestSequence = -1L;
        fastOutputIndex = 0;
        fastAnchorNewestSequence = -1L;
        if (visibleDecoderSlot == -4 || visibleDecoderSlot == -5)
            showCameraOutput();
""", "GLRenderer history cleanup")

s = replace_once(s,
"""    public String stateText() {
        if (visibleDecoderSlot == -4) return "STUTTER";
""",
"""    public String stateText() {
        if (visibleDecoderSlot == -5) return "FAST";
        if (visibleDecoderSlot == -4) return "STUTTER";
""", "GLRenderer state text")

s = replace_once(s,
"""    public long maxDrawGapMs() { return maxDrawGapNs / 1_000_000L; }
""",
"""    public long maxDrawGapMs() { return maxDrawGapNs / 1_000_000L; }
    public double cameraFps() { return measuredCameraFps; }
""", "GLRenderer cameraFps accessor")

p.write_text(s, encoding="utf-8", newline="\n")

# ----------------------------------------------------------------------
# MainActivity.java
# ----------------------------------------------------------------------
p = FILES["MainActivity.java"]
s = load(p)

s = replace_once(s,
"""// Version: v0.6.23
// Build: Reverse Timing + Slow Cue Events
// Date: 2026-08-06
""",
"""// Version: v0.6.24
// Build: Fast View + Fixed View HUD + Timing Cleanup
// Date: 2026-08-09
""", "Main header")

s = replace_once(s, '"v0.6.23";', '"v0.6.24";', "Main version")
s = replace_once(s,
"""    static final int STANDARD_REVERSE_PLAYBACK_MS = 2000;
""",
"""    static final int STANDARD_REVERSE_PLAYBACK_MS = 1000;
""", "Main Reverse default")

s = replace_once(s,
"""    LinearLayout timeControlRow, slowControlRow, stutterSlicesRow, freezeRateRow;
    Button slices2Button, slices4Button, slices6Button, slices8Button;
""",
"""    LinearLayout timeControlRow, slowControlRow, stutterSlicesRow, freezeRateRow, fastSpeedRow;
    LinearLayout modeRail;
    TextView railView, railTime, railParam, railShare;
    Button slices2Button, slices4Button, slices6Button, slices8Button;
    Button fast1Button, fast15Button, fast2Button, fast3Button, fast4Button;
""", "Main HUD fields")

s = replace_once(s,
"""    int slowPlaybackMs = 4000;
    String activeTestPreset = "STANDARD_REVERSE";
""",
"""    int slowPlaybackMs = 4000;
    int stutterSlices = 4;
    int fastTimeMs = 1000;
    float fastSpeed = 2.0f;
    volatile boolean appPaused = false;
    String activeTestPreset = "STANDARD_REVERSE";
""", "Main state")

s = replace_once(s,
"""    final Runnable reopenCameraRunnable = () -> {
        if (cameraOpenState == CameraOpenState.RECOVERING ||
""",
"""    final Runnable reopenCameraRunnable = () -> {
        if (appPaused) {
            TraceLog.i("camera reopen suppressed while paused");
            return;
        }
        if (cameraOpenState == CameraOpenState.RECOVERING ||
""", "Main reopen guard")

s = replace_once(s,
"""        buildRecordProgress(root);
        buildHud(root);
        buildWatermark(root);
""",
"""        buildRecordProgress(root);
        buildHud(root);
        buildModeRail(root);
        buildWatermark(root);
""", "Main rail creation")

s = replace_once(s,
"""        shape.setColor(TIMETOY_VIOLET);
""",
"""        // Lighter violet timing cue.
        shape.setColor(0x889f67d5);
""", "Main cue color")

# Give the Slow cue a visual duration that spans the whole nominal non-recording
# interval. It is still hidden by recorder.start(), so it cannot outlive the gap.
s = replace_once(s,
"""    void startRecordCue(Runnable onComplete) {
        if (recordCueMs <= 0) {
""",
"""    void startRecordCue(Runnable onComplete) {
        startRecordCue(recordCueMs, onComplete);
    }
    void startRecordCue(int cueDurationMs, Runnable onComplete) {
        if (cueDurationMs <= 0) {
""", "Main cue overload")

s = replace_once(s,
"""                    .setDuration(recordCueMs)
""",
"""                    .setDuration(cueDurationMs)
""", "Main cue duration")

s = replace_once(s,
"""    void buildHud(FrameLayout root) {
""",
"""    void buildModeRail(FrameLayout root) {
        modeRail = new LinearLayout(this);
        modeRail.setOrientation(LinearLayout.VERTICAL);
        modeRail.setPadding(dp(8), dp(8), dp(8), dp(8));
        modeRail.setBackgroundColor(0x55000000);

        TextView tt = makeRailText("TT");
        tt.setTextColor(TIMETOY_VIOLET);
        tt.setTextSize(18);
        railView = makeRailText("Reverse");
        railTime = makeRailText("1.0 s");
        railParam = makeRailText("");
        railShare = makeRailText("Share");

        modeRail.addView(tt);
        modeRail.addView(railView);
        modeRail.addView(railTime);
        modeRail.addView(railParam);
        modeRail.addView(railShare);

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                dp(116), -2, Gravity.TOP | Gravity.LEFT);
        lp.setMargins(dp(10), dp(10), 0, 0);
        root.addView(modeRail, lp);
        updateModeRail();
    }

    TextView makeRailText(String text) {
        TextView v = new TextView(this);
        v.setText(text);
        v.setTextColor(0xffffffff);
        v.setTextSize(14);
        v.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
        v.setPadding(dp(6), dp(5), dp(6), dp(5));
        v.setMinHeight(dp(34));
        return v;
    }

    String formatSeconds(int ms) {
        if (ms % 1000 == 0)
            return String.format(Locale.US, "%.1f s", ms / 1000.0f);
        return String.format(Locale.US, "%.1f s", ms / 1000.0f);
    }

    void updateModeRail() {
        if (modeRail == null || glView == null) return;
        GLView.LensMode m = glView.getLensMode();
        String time = "";
        String param = "";

        if (m == GLView.LensMode.SLOW) {
            time = formatSeconds(slowPlaybackMs);
            param = slowdownText();
        } else if (m == GLView.LensMode.DUBBUF_REVERSE) {
            time = formatSeconds(reversePlaybackMs);
        } else if (m == GLView.LensMode.STUTTER) {
            time = formatSeconds(reversePlaybackMs);
            param = stutterSlices + " slices";
        } else if (m == GLView.LensMode.FAST) {
            time = formatSeconds(fastTimeMs);
            param = Math.abs(fastSpeed - Math.round(fastSpeed)) < 0.001f
                    ? String.format(Locale.US, "%.0f×", fastSpeed)
                    : String.format(Locale.US, "%.1f×", fastSpeed);
        } else if (m == GLView.LensMode.FREEZE) {
            float hz = glView.getFreezeFrequency();
            time = formatSeconds(Math.round(1000.0f / Math.max(2.0f, hz)));
        }

        railView.setText(currentLensTitle());
        railTime.setText(time);
        railParam.setText(param);
        railShare.setText("Share");
    }

    void buildHud(FrameLayout root) {
""", "Main rail methods")

s = replace_once(s,
"""        reverseLensButton = makeControlButton("Legacy Reverse");
""",
"""        // Reuse the old button variable; Legacy Reverse is removed.
        reverseLensButton = makeControlButton("Fast");
""", "Main Fast button")

s = replace_once(s,
"""        stutterSlicesRow.addView(slices6Button, tinyButtonParams());
        stutterSlicesRow.addView(slices8Button, tinyButtonParams());
        /*
""",
"""        stutterSlicesRow.addView(slices6Button, tinyButtonParams());
        stutterSlicesRow.addView(slices8Button, tinyButtonParams());

        fastSpeedRow = new LinearLayout(this);
        fastSpeedRow.setOrientation(LinearLayout.HORIZONTAL);
        fast1Button = makeSmallControlButton("1×");
        fast15Button = makeSmallControlButton("1.5×");
        fast2Button = makeSmallControlButton("2×");
        fast3Button = makeSmallControlButton("3×");
        fast4Button = makeSmallControlButton("4×");
        fastSpeedRow.addView(fast1Button, tinyButtonParams());
        fastSpeedRow.addView(fast15Button, tinyButtonParams());
        fastSpeedRow.addView(fast2Button, tinyButtonParams());
        fastSpeedRow.addView(fast3Button, tinyButtonParams());
        fastSpeedRow.addView(fast4Button, tinyButtonParams());
        /*
""", "Main Fast controls")

s = replace_once(s,
"""        hudPanel.addView(stutterSlicesRow);
        hudPanel.addView(freezeRateRow);
""",
"""        hudPanel.addView(stutterSlicesRow);
        hudPanel.addView(fastSpeedRow);
        hudPanel.addView(freezeRateRow);
""", "Main Fast row add")

s = replace_once(s,
"""        reverseLensButton.setOnClickListener(v ->
                setLensMode(GLView.LensMode.REVERSE));
""",
"""        reverseLensButton.setOnClickListener(v ->
                setLensMode(GLView.LensMode.FAST));
""", "Main Fast listener")

s = replace_once(s,
"""        slices2Button.setOnClickListener(v -> glView.setStutterSlices(2));
        slices4Button.setOnClickListener(v -> glView.setStutterSlices(4));
        slices6Button.setOnClickListener(v -> glView.setStutterSlices(6));
        slices8Button.setOnClickListener(v -> glView.setStutterSlices(8));
""",
"""        slices2Button.setOnClickListener(v -> setStutterSlices(2));
        slices4Button.setOnClickListener(v -> setStutterSlices(4));
        slices6Button.setOnClickListener(v -> setStutterSlices(6));
        slices8Button.setOnClickListener(v -> setStutterSlices(8));
        fast1Button.setOnClickListener(v -> setFastSpeed(1.0f));
        fast15Button.setOnClickListener(v -> setFastSpeed(1.5f));
        fast2Button.setOnClickListener(v -> setFastSpeed(2.0f));
        fast3Button.setOnClickListener(v -> setFastSpeed(3.0f));
        fast4Button.setOnClickListener(v -> setFastSpeed(4.0f));
""", "Main parameter listeners")

s = replace_once(s,
"""        reverseTime16Button.setOnClickListener(v -> setModePlaybackDuration(500));
        reverseTime4Button.setOnClickListener(v -> setModePlaybackDuration(1000));
        reverseTime8Button.setOnClickListener(v -> setModePlaybackDuration(2000));
        standardReverseButton.setOnClickListener(v -> setModePlaybackDuration(4000));
""",
"""        reverseTime16Button.setOnClickListener(v -> selectTimeChoice(0));
        reverseTime4Button.setOnClickListener(v -> selectTimeChoice(1));
        reverseTime8Button.setOnClickListener(v -> selectTimeChoice(2));
        standardReverseButton.setOnClickListener(v -> selectTimeChoice(3));
""", "Main Time choices")

s = replace_once(s,
"""        if (glView.getLensMode() == GLView.LensMode.STUTTER) {
            updateOverlay("Starting Stutter history");
            startPreviewOnlySession(() -> glView.startStutter(() -> {
                TraceLog.i("Stutter first playback selected; hide splash");
                hideSplashAfterFirstFrame();
            }));
            return;
        }
        cycleCount = 0;
""",
"""        if (glView.getLensMode() == GLView.LensMode.STUTTER) {
            updateOverlay("Starting Stutter history");
            startPreviewOnlySession(() -> glView.startStutter(() -> {
                TraceLog.i("Stutter first playback selected; hide splash");
                hideSplashAfterFirstFrame();
            }));
            return;
        }
        if (glView.getLensMode() == GLView.LensMode.FAST) {
            updateOverlay("Starting Fast history");
            startPreviewOnlySession(() -> {
                glView.setFastTimeMs(fastTimeMs);
                glView.setFastSpeed(fastSpeed);
                glView.startFast(() -> {
                    TraceLog.i("Fast first playback selected; hide splash");
                    hideSplashAfterFirstFrame();
                });
            });
            return;
        }
        cycleCount = 0;
""", "Main Fast startup")

s = replace_once(s,
"""        if (glView != null && glView.getLensMode() == GLView.LensMode.SLOW) {
            startRecordCue(null);
        }
""",
"""        if (glView != null && glView.getLensMode() == GLView.LensMode.SLOW) {
            TraceLog.i("SLOW LATENCY record stop t=" +
                    SystemClock.elapsedRealtime());
            // Stay visibly shrinking for the full nominal non-recording span;
            // recorder.start() remains the authoritative hide event.
            int visualWaitMs = Math.max(1,
                    slowPlaybackMs - effectiveRecordDurationMs());
            startRecordCue(visualWaitMs, null);
        }
""", "Main Slow cue span")

s = replace_once(s,
"""    void handleCameraFailure(CameraDevice device, String reason) {
        if (cameraOpenState == CameraOpenState.RECOVERING) {
""",
"""    void handleCameraFailure(CameraDevice device, String reason) {
        if (appPaused) {
            TraceLog.i("camera failure while paused; no recovery loop: " + reason);
            try { device.close(); } catch (Exception ignored) {}
            if (cameraDevice == device) cameraDevice = null;
            cameraOpenState = CameraOpenState.CLOSED;
            return;
        }
        if (cameraOpenState == CameraOpenState.RECOVERING) {
""", "Main paused camera failure")

s = replace_once(s,
"""    void scheduleCameraRecovery(String reason) {
        if (cameraOpenState == CameraOpenState.RECOVERING) {
""",
"""    void scheduleCameraRecovery(String reason) {
        if (appPaused) {
            TraceLog.i("camera recovery suppressed while paused: " + reason);
            cameraOpenState = CameraOpenState.CLOSED;
            return;
        }
        if (cameraOpenState == CameraOpenState.RECOVERING) {
""", "Main paused recovery")

s = replace_once(s,
"""        if (mode == GLView.LensMode.STUTTER) {
            running = false;
""",
"""        if (mode == GLView.LensMode.FAST) {
            running = false;
            recordingFollowing = false;
            pendingRecordingCycleId = -1;
            loadingCycleId = -1;
            glView.stopFreeze();
            glView.stopDubBufReverse();
            glView.stopStutter();
            glView.releaseAllPlayers();
            glView.setLensMode(mode);
            captureFps = 60;
            captureWidth = 1280;
            captureHeight = 720;
            playbackFps = 60;
            activeTestPreset = "FAST_HISTORY_720P60";
            updateControlButtons();
            updateOverlay("Fast " + fastSpeed + "×; restarting");
            if (splashPanel != null) splashPanel.setVisibility(View.GONE);
            scheduleCameraRecovery("Fast mode restart");
            return;
        }
        if (mode == GLView.LensMode.STUTTER) {
            running = false;
""", "Main Fast mode")

s = replace_once(s,
"""    void setModePlaybackDuration(int durationMs) {
""",
"""    void selectTimeChoice(int index) {
        boolean slow = glView != null &&
                glView.getLensMode() == GLView.LensMode.SLOW;
        int[] values = slow
                ? new int[]{1000, 2000, 3000, 4000}
                : new int[]{500, 1000, 1500, 2000};
        int i = Math.max(0, Math.min(3, index));
        setModePlaybackDuration(values[i]);
    }

    void setStutterSlices(int slices) {
        stutterSlices = slices;
        if (glView != null) glView.setStutterSlices(slices);
        updateControlButtons();
    }

    void setFastSpeed(float speed) {
        fastSpeed = Math.max(1.0f, Math.min(4.0f, speed));
        if (fastSpeed > 1.0f &&
                (fastSpeed - 1.0f) * fastTimeMs > 2000.0f)
            fastTimeMs = Math.max(100,
                    Math.round(2000.0f / (fastSpeed - 1.0f)));
        if (glView != null) {
            glView.setFastTimeMs(fastTimeMs);
            glView.setFastSpeed(fastSpeed);
        }
        updateControlButtons();
        updateOverlay("Fast " + fastSpeed + "×");
    }

    void setModePlaybackDuration(int durationMs) {
""", "Main parameter helpers")

s = replace_once(s,
"""        } else if (mode == GLView.LensMode.STUTTER) {
            int t = Math.max(400, Math.min(1600, durationMs));
            glView.setStutterTimeMs(t);
            updateOverlay("Stutter Time " + (t / 1000.0f) + " s");
        } else if (mode == GLView.LensMode.FREEZE) {
""",
"""        } else if (mode == GLView.LensMode.STUTTER) {
            int t = Math.max(500, Math.min(2000, durationMs));
            reversePlaybackMs = t;
            glView.setStutterTimeMs(t);
            updateOverlay("Stutter Time " + (t / 1000.0f) + " s");
        } else if (mode == GLView.LensMode.FAST) {
            fastTimeMs = Math.max(100, Math.min(2000, durationMs));
            if (fastSpeed > 1.0f &&
                    (fastSpeed - 1.0f) * fastTimeMs > 2000.0f)
                fastSpeed = 1.0f + 2000.0f / fastTimeMs;
            glView.setFastTimeMs(fastTimeMs);
            glView.setFastSpeed(fastSpeed);
            updateOverlay("Fast Time " + (fastTimeMs / 1000.0f) + " s");
        } else if (mode == GLView.LensMode.FREEZE) {
""", "Main Time modes")

s = replace_once(s,
"""        if (glView.getLensMode() == GLView.LensMode.STUTTER) return "Stutter";
        return "Reverse";
""",
"""        if (glView.getLensMode() == GLView.LensMode.STUTTER) return "Stutter";
        if (glView.getLensMode() == GLView.LensMode.FAST) return "Fast";
        return "Reverse";
""", "Main title")

s = replace_once(s,
"""            boolean slow = m == GLView.LensMode.SLOW;
            boolean stutter = m == GLView.LensMode.STUTTER;
            boolean strobe = m == GLView.LensMode.FREEZE;
            effectControlLabel.setText(stutter ? "Time / Slices" : slow ? "Time / Slowdown" : "Time");
            timeControlRow.setVisibility(View.VISIBLE);
            slowControlRow.setVisibility(slow ? View.VISIBLE : View.GONE);
            stutterSlicesRow.setVisibility(stutter ? View.VISIBLE : View.GONE);
            freezeRateRow.setVisibility(View.GONE);
""",
"""            boolean slow = m == GLView.LensMode.SLOW;
            boolean stutter = m == GLView.LensMode.STUTTER;
            boolean fast = m == GLView.LensMode.FAST;
            boolean strobe = m == GLView.LensMode.FREEZE;
            effectControlLabel.setText(stutter ? "Time / Slices" :
                    fast ? "Time / Speed" :
                    slow ? "Time / Slowdown" : "Time");
            timeControlRow.setVisibility(View.VISIBLE);
            slowControlRow.setVisibility(slow ? View.VISIBLE : View.GONE);
            stutterSlicesRow.setVisibility(stutter ? View.VISIBLE : View.GONE);
            fastSpeedRow.setVisibility(fast ? View.VISIBLE : View.GONE);
            freezeRateRow.setVisibility(View.GONE);
            updateModeRail();
""", "Main controls visibility")

s = replace_once(s,
"""            reverseLensButton.setEnabled(glView.getLensMode() != GLView.LensMode.REVERSE);
""",
"""            reverseLensButton.setEnabled(glView.getLensMode() != GLView.LensMode.FAST);
""", "Main Fast button enabled")

s = replace_once(s,
"""        if (freeze2Button != null && glView != null) {
""",
"""        if (fast1Button != null) {
            fast1Button.setEnabled(Math.abs(fastSpeed - 1.0f) > 0.001f);
            fast15Button.setEnabled(Math.abs(fastSpeed - 1.5f) > 0.001f);
            fast2Button.setEnabled(Math.abs(fastSpeed - 2.0f) > 0.001f);
            fast3Button.setEnabled(Math.abs(fastSpeed - 3.0f) > 0.001f);
            fast4Button.setEnabled(Math.abs(fastSpeed - 4.0f) > 0.001f);
        }
        if (freeze2Button != null && glView != null) {
""", "Main Fast button states")

s = replace_once(s,
"""            int selectedMs = slowMode ? slowPlaybackMs : reversePlaybackMs;
""",
"""            int selectedMs = slowMode ? slowPlaybackMs :
                    (glView != null && glView.getLensMode() == GLView.LensMode.FAST
                            ? fastTimeMs : reversePlaybackMs);
""", "Main Time selection state")

s = replace_once(s,
"""            else if (lensMode == GLView.LensMode.STUTTER) mode = "STUTTER";
            else mode = "REV";
""",
"""            else if (lensMode == GLView.LensMode.STUTTER) mode = "STUTTER";
            else if (lensMode == GLView.LensMode.FAST) mode = "FAST";
            else mode = "REV";
""", "Main diagnostic mode")

s = replace_once(s,
"""            } else if (lensMode == GLView.LensMode.STUTTER &&
                    glView != null && glView.renderer != null) {
                playing = glView.renderer.getStutterCycle();
                recording = -1;
            } else {
""",
"""            } else if (lensMode == GLView.LensMode.STUTTER &&
                    glView != null && glView.renderer != null) {
                playing = glView.renderer.getStutterCycle();
                recording = -1;
            } else if (lensMode == GLView.LensMode.FAST &&
                    glView != null && glView.renderer != null) {
                playing = glView.renderer.getFastCycle();
                recording = -1;
            } else {
""", "Main Fast diagnostic cycle")

s = replace_once(s,
"""            String fpsText = measuredCaptureFps > 0.0
                    ? String.format(Locale.US, "%.1f", measuredCaptureFps)
                    : "--";
""",
"""            boolean previewFpsMode =
                    lensMode == GLView.LensMode.DUBBUF_REVERSE ||
                    lensMode == GLView.LensMode.STUTTER ||
                    lensMode == GLView.LensMode.FAST ||
                    lensMode == GLView.LensMode.FREEZE;
            double actualFps = previewFpsMode &&
                    glView != null && glView.renderer != null
                    ? glView.renderer.cameraFps()
                    : measuredCaptureFps;
            String fpsText = actualFps > 0.0
                    ? String.format(Locale.US, "%.1f", actualFps)
                    : "--";
""", "Main actual FPS")

s = replace_once(s,
"""    @Override
    protected void onDestroy() {
""",
"""    @Override
    protected void onPause() {
        super.onPause();
        appPaused = true;
        running = false;
        mainHandler.removeCallbacks(reopenCameraRunnable);
        cameraOpenGeneration++;
        TraceLog.i("MainActivity onPause; camera recovery disabled");

        releasePersistentRecordingPipeline();
        CameraDevice device = cameraDevice;
        cameraDevice = null;
        cameraOpenState = CameraOpenState.CLOSED;
        if (device != null) {
            try { device.close(); } catch (Exception ignored) {}
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        enterFullscreen();
        if (!appPaused) return;
        appPaused = false;
        TraceLog.i("MainActivity onResume; reopening camera once");
        if (cameraTexture != null &&
                checkSelfPermission(Manifest.permission.CAMERA) ==
                        PackageManager.PERMISSION_GRANTED) {
            mainHandler.postDelayed(() -> {
                if (!appPaused && cameraDevice == null &&
                        cameraOpenState == CameraOpenState.CLOSED)
                    startCamera();
            }, 250L);
        }
    }

    @Override
    protected void onDestroy() {
""", "Main lifecycle")

s = replace_once(s,
"""            glView.stopFreeze();
            glView.releaseAllPlayers();
""",
"""            glView.stopFreeze();
            glView.stopFast();
            glView.releaseAllPlayers();
""", "Main destroy Fast")

p.write_text(s, encoding="utf-8", newline="\n")

print("0.6.24 complete source files generated in place.")
print("Modified:")
for path in FILES.values():
    print(" ", path.relative_to(ROOT))
print()
print("Next:")
print("  git diff --stat")
print("  git diff --check")
print("Then build in AIDE before committing.")

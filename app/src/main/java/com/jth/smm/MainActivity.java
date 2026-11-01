// ============================================================
// Timetoy
// File: MainActivity.java
// Version: v0.6.33
// Build: Functional Rack + Mode Colours + Tape Timing
// Date: 2026-08-09
// ============================================================

package com.jth.smm;

import android.Manifest;
import android.app.Activity;
import android.os.*;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.SurfaceTexture;
import android.graphics.BitmapFactory;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;

import android.hardware.camera2.*;
import android.hardware.camera2.CameraConstrainedHighSpeedCaptureSession;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.Image;
import android.media.ImageReader;
import android.graphics.ImageFormat;
import android.util.Range;
import android.util.Size;
import android.view.*;
import android.widget.*;

import java.io.File;
import java.util.*;

public class MainActivity extends Activity {
    static final long REVERSE_PREPARE_TIMEOUT_MS = 4500L;

    static final int DEFAULT_WIDTH = 1280;
    static final int DEFAULT_HEIGHT = 720;
    static final int DEFAULT_FPS = 60;
    static final int SLOW_CAPTURE_FPS = 240;
    static final int REVERSE_CAPTURE_FPS = 120;
    static final String VERSION =
            "v0.6.33";

    static final int SLOW_PLAYBACK_FPS = 24;
    static final int SLOW_DECODE_MARGIN_MS = 0;
    static final int SLOW_SEED_MIN_FRAMES = 8;
    static final long SLOW_SEED_MIN_SPAN_US = 30_000L;
    static final int TIMETOY_VIOLET = 0xff7f3fbf;
    static final int TIMETOY_ORANGE = 0xffff7a1a;

    static final int MODE_REVERSE = 0xffff3030;
    static final int MODE_SLOW    = 0xff90ee90;
    static final int MODE_FAST    = 0xff168a3f;
    static final int MODE_FREEZE  = 0xff20dfe5;
    static final int MODE_STUTTER = 0xffffdf20;
    static final int MODE_SCRUB   = TIMETOY_VIOLET;
    static final int MODE_REWIND  = TIMETOY_ORANGE;
    static final int MODE_RAMREV  = MODE_REVERSE;
    static final int MODE_CREV    = TIMETOY_ORANGE;
    static final int STANDARD_REVERSE_RECORD_MS = 1600;
    static final int STANDARD_REVERSE_PLAYBACK_MS = 1000;
    static final int TEST_REVERSE_MS = 4000;
    static final long SEED_RETRY_MS = 500;
    static final long CAMERA_REOPEN_MS = 1000;
    static final int REVERSE_SEED_MIN_FRAMES = 40;
    static final long REVERSE_SEED_MIN_SPAN_US = 1_300_000L;
    static final int WARMUP_RECORD_MS = 250;
    static final int DEFAULT_RECORD_MS = 500;

    GLView glView;
    FrameLayout guiRoot, choiceOverlay;
    OrientationEventListener guiOrientationListener;
    int guiRotation;

    View recordingFrame;
    View recordCue;
    View recordProgress;
    TextView flashLabel;
    LinearLayout splashPanel;
    TextView splashTitle;
    TextView splashStatus;
    TextView splashTracePath;

    LinearLayout hudPanel;
    LinearLayout diagnosticPanel;
    TextView overlay;
    ImageView watermarkView;
    TextView effectControlLabel;
    LinearLayout timeControlRow, slowControlRow, stutterSlicesRow, freezeRateRow, fastSpeedRow;
    LinearLayout modeRail;
    TextView railTT, railView, railTime, railParam, railFX, railShare, railCamera;
    LinearLayout scrubPanel;
    float downshiftFactor = 0.25f;
    boolean downshiftDelayed, downshiftResetGesture;

    boolean isManualTapeMode() {
        if (glView == null) return false;
        GLView.LensMode mode = glView.getLensMode();
        return mode == GLView.LensMode.REPLAY || mode == GLView.LensMode.MOLASSES ||
                mode == GLView.LensMode.DOWNSHIFT;
    }

    void configureTapeMode(Tape3 tape) {
        tape.setReverse3(glView.getLensMode() == GLView.LensMode.REVERSE3);
        tape.setManualPlayback(isManualTapeMode());
        tape.setLfoPeriodMs(lfoPeriodMs);
        if (glView.getLensMode() == GLView.LensMode.TAPE3) tape.setDelayMs(wobbleDelayMs);
        tape.setLfoAmountMs(glView.getLensMode() == GLView.LensMode.TAPE3 ? lfoAmountMs : 0L);
        downshiftDelayed = downshiftResetGesture = false;
    }

    void showTapeOutput() {
        if (tape3 != null && isManualTapeMode() && tape3.isPlaybackLive())
            glView.showRamLive();
        else glView.showTape3Delay();
    }

    long lfoPeriodMs = 2000L, lfoAmountMs = 250L, wobbleDelayMs = 1000L;

    Button slices2Button, slices4Button, slices6Button, slices8Button;
    Button fast1Button, fast15Button, fast2Button, fast3Button, fast4Button;

    Button reverseLensButton;
    Button dubBufReverseButton;
    Button slowLensButton;
    Button freezeLensButton;
    Button stutterLensButton;

    Button speed16Button;
    Button speed8Button;
    Button speed4Button;

    Button gain1Button;
    Button gain2Button;
    Button gain4Button;

    Button zoom1Button;
    Button zoom15Button;
    Button zoom2Button;

    Button resolution1080Button;
    Button resolution720Button;

    Button capture240Button;
    Button capture120Button;
    Button capture60Button;
    Button capture30Button;

    Button length05Button;
    Button length10Button;

    Button bitrate20Button;
    Button bitrate40Button;
    Button bitrate80Button;

    Button cueOffButton;
    Button cue05Button;
    Button cue10Button;

    Button reverseRes1080Button;
    Button reverseRes720Button;
    Button reverseRes540Button;
    Button reverseResVgaButton;
    Button reverseTime16Button;
    Button reverseTime4Button;
    Button reverseTime8Button;
    Button standardReverseButton;
    Button freeze2Button;
    Button freeze4Button;
    Button freeze5Button;
    Button freeze6Button;
    Button freeze8Button;
    Button freeze10Button;

    int playbackFps = 30;
    float playbackGain = 1.0f;
    float cameraZoom = 1.0f;

    int captureWidth = DEFAULT_WIDTH;
    int captureHeight = DEFAULT_HEIGHT;
    int captureFps = DEFAULT_FPS;
    int selectedCaptureFps = DEFAULT_FPS;
    boolean captureHighSpeed, manualSensorAvailable;
    boolean manualExposure = true;
    long shutterNs = 8_333_333L;
    int sensorIso = 400;
    int captureDurationMs = DEFAULT_RECORD_MS;
    int captureBitrate = 20_000_000;
    int recordCueMs = 0;
    int reverseRecordMs = STANDARD_REVERSE_RECORD_MS;
    int reversePlaybackMs = STANDARD_REVERSE_PLAYBACK_MS;
    int slowPlaybackMs = 2000;
    int slowFactor = 10;
    int stutterTimeMs = 1000;
    int stutterSlices = 4;
    int fastTimeMs = 1000;
    float fastSpeed = 2.0f;
    volatile boolean appPaused = false;
    String activeTestPreset = "STANDARD_REVERSE";

    CameraManager cameraManager;
    CameraDevice cameraDevice;
    CameraCaptureSession session;

    HandlerThread cameraThread;
    Handler cameraHandler;

    final Handler mainHandler =
            new Handler(
                    Looper.getMainLooper()
            );

    SurfaceTexture cameraTexture;
    Surface previewSurface;

    RamFrameBuffer ramBuffer;
    Tape3 tape3;
    ImageReader ramReader;
    boolean ramScrubbing = false;
    long ramSelectedOffsetMs = 0L;

    boolean rewindHolding = false;
    boolean rewindCatchup = false;
    boolean rewindAtLimit = false;
    double rewindDelayMs = 0.0;
    long rewindLastTickMs = 0L;

    static final long RAMREV_BLOCK_MS = 1000L;
    long ramRevCycleStartMs = 0L;

    MediaSurfaceRecorder recorder;

    SensorManager imuSensorManager;
    Sensor imuAccelerometer;
    Sensor imuGyroscope;

    volatile float imuAccelX = 0.0f;
    volatile float imuAccelY = 0.0f;
    volatile float imuAccelZ = 0.0f;
    volatile float imuGyroX = 0.0f;
    volatile float imuGyroY = 0.0f;
    volatile float imuGyroZ = 0.0f;

    final SensorEventListener imuListener = new SensorEventListener() {
        @Override
        public void onSensorChanged(SensorEvent event) {
            if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
                imuAccelX = event.values[0];
                imuAccelY = event.values[1];
                imuAccelZ = event.values[2];
            } else if (event.sensor.getType() == Sensor.TYPE_GYROSCOPE) {
                imuGyroX = event.values[0];
                imuGyroY = event.values[1];
                imuGyroZ = event.values[2];
            }
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {
        }
    };

    void startImu() {
        if (imuSensorManager == null) {
            imuSensorManager =
                    (SensorManager) getSystemService(Context.SENSOR_SERVICE);
            if (imuSensorManager != null) {
                imuAccelerometer = imuSensorManager.getDefaultSensor(
                        Sensor.TYPE_ACCELEROMETER);
                imuGyroscope = imuSensorManager.getDefaultSensor(
                        Sensor.TYPE_GYROSCOPE);
            }
        }

        if (imuSensorManager == null) return;
        imuSensorManager.unregisterListener(imuListener);

        if (imuAccelerometer != null)
            imuSensorManager.registerListener(
                    imuListener, imuAccelerometer,
                    SensorManager.SENSOR_DELAY_GAME);

        if (imuGyroscope != null)
            imuSensorManager.registerListener(
                    imuListener, imuGyroscope,
                    SensorManager.SENSOR_DELAY_GAME);
    }

    void stopImu() {
        if (imuSensorManager != null)
            imuSensorManager.unregisterListener(imuListener);
    }

    volatile double tape3SensorFrameMs = 0.0;
    volatile double tape3ExposureMs = 0.0;
    volatile int tape3Iso = 0;
    volatile int tape3AeState = -1;
    volatile double tape3CallbackFps = 0.0;
    volatile String tape3RequestedAe = "--";

    long tape3AuditFirstNs = 0L;
    long tape3AuditLastLogNs = 0L;
    long tape3AuditFrames = 0L;
    long tape3AuditLastFrames = 0L;

    final CameraCaptureSession.CaptureCallback frameAuditCaptureCallback =
            new CameraCaptureSession.CaptureCallback() {
                @Override
                public void onCaptureCompleted(
                        CameraCaptureSession captureSession,
                        CaptureRequest request,
                        TotalCaptureResult result
                ) {
                    Long timestamp = result.get(CaptureResult.SENSOR_TIMESTAMP);
                    FrameAudit.cameraCapture(timestamp == null ? -1L : timestamp);

                    if (glView != null && GLView.isTapeMode(glView.getLensMode())) {
                        tape3AuditFrames++;
                        Long tape3FrameDuration =
                                result.get(CaptureResult.SENSOR_FRAME_DURATION);
                        Long tape3Exposure =
                                result.get(CaptureResult.SENSOR_EXPOSURE_TIME);
                        Integer tape3Sensitivity =
                                result.get(CaptureResult.SENSOR_SENSITIVITY);
                        Integer tape3Ae =
                                result.get(CaptureResult.CONTROL_AE_STATE);
                        Range<Integer> tape3AeRequest =
                                request.get(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE);

                        tape3SensorFrameMs = tape3FrameDuration == null
                                ? 0.0 : tape3FrameDuration / 1000000.0;
                        tape3ExposureMs = tape3Exposure == null
                                ? 0.0 : tape3Exposure / 1000000.0;
                        tape3Iso = tape3Sensitivity == null ? 0 : tape3Sensitivity;
                        tape3AeState = tape3Ae == null ? -1 : tape3Ae;
                        tape3RequestedAe = tape3AeRequest == null
                                ? "--" : tape3AeRequest.toString();

                        long nowNs = System.nanoTime();
                        if (tape3AuditFirstNs == 0L) {
                            tape3AuditFirstNs = nowNs;
                            tape3AuditLastLogNs = nowNs;
                            tape3AuditLastFrames = tape3AuditFrames;
                        } else if (nowNs - tape3AuditLastLogNs >= 5000000000L) {
                            long deltaFrames = tape3AuditFrames - tape3AuditLastFrames;
                            double seconds = (nowNs - tape3AuditLastLogNs) / 1000000000.0;
                            tape3CallbackFps = seconds > 0.0
                                    ? deltaFrames / seconds : 0.0;

                            Long frameDuration = result.get(CaptureResult.SENSOR_FRAME_DURATION);
                            Range<Integer> aeRange = request.get(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE);
                            TraceLog.i("TAPE3 CAMERA frames=" + tape3AuditFrames +
                                    " fps=" + (deltaFrames / seconds) +
                                    " frameDurationMs=" + (frameDuration == null ? -1.0 : frameDuration / 1000000.0) +
                                    " requestedAE=" + aeRange);
                            if (frameDuration != null && frameDuration > CaptureTiming.frameNs(captureFps) * 1.1)
                                TraceLog.i("FPS MISMATCH requested=" + captureFps + " sensor=" +
                                        (1_000_000_000.0 / frameDuration) + " requestAE=" + aeRange);
                            tape3AuditLastLogNs = nowNs;
                            tape3AuditLastFrames = tape3AuditFrames;
                        }
                    }
                }
            };

    /*
     * Stable encoder target owned for the lifetime of the open camera.
     * Each clip gets a fresh codec/muxer attached to this Surface, while the
     * CameraCaptureSession remains unchanged.
     */
    Surface encoderSurface;
    boolean recordingSessionConfigured = false;
    boolean recordingSessionCreating = false;
    int recordingSessionDiagnosticId = 0;

    File waitingRecordingFile;
    int waitingRecordingDurationMs;
    boolean waitingRecordingShowCue;
    Runnable waitingRecordingDone;

    volatile boolean running = false;
    volatile boolean busyRecording = false;
    volatile boolean sessionClosing = false;

    enum CameraOpenState { CLOSED, OPENING, OPEN, RECOVERING }
    volatile CameraOpenState cameraOpenState = CameraOpenState.CLOSED;
    int cameraOpenGeneration = 0;
    final Runnable reopenCameraRunnable = () -> {
        if (appPaused) {
            TraceLog.i("camera reopen suppressed while paused");
            return;
        }
        if (cameraOpenState == CameraOpenState.RECOVERING ||
                cameraOpenState == CameraOpenState.CLOSED) {
            cameraOpenState = CameraOpenState.CLOSED;
            startCamera();
        }
    };

    Runnable pendingRecordingDone;
    File pendingRecordingFile;
    int closeGeneration = 0;

    int cycleCount = 0;
    int seedAttempt = 0;
    int nextCaptureCycle = 2;
    volatile int recordingCycleId = -1;
    volatile int pendingRecordingCycleId = -1;
    volatile int loadingCycleId = -1;
    volatile double measuredCaptureFps = 0.0;
    volatile int measuredCaptureFrames = 0;
    volatile long measuredCaptureSpanUs = -1L;

    boolean hudVisible = false;
    boolean diagnosticVisible = true;


    PlaybackItem currentItem;
    PlaybackItem nextItem;
    PlaybackItem queuedItem;

    File recordingCandidate;
    boolean currentPlaybackDone = false;
    boolean recordingFollowing = false;

    String lastEvent = "Booting";

    final Handler hudHandler =
            new Handler(
                    Looper.getMainLooper()
            );

    long hudTicks = 0;
    long appStartMs = 0;
    CameraDiagnostics diagnostics;

    final Runnable hideFlashLabel = () -> {
        if (flashLabel != null) {
            flashLabel.setVisibility(View.GONE);
        }
    };

    final Runnable rewindTick = new Runnable() {
        @Override public void run() {
            if (glView == null ||
                    glView.getLensMode() != GLView.LensMode.REWIND ||
                    (!rewindHolding && !rewindCatchup)) {
                rewindLastTickMs = 0L;
                return;
            }

            long now = SystemClock.elapsedRealtime();
            if (rewindLastTickMs == 0L) rewindLastTickMs = now;
            long dt = Math.max(0L, Math.min(50L, now - rewindLastTickMs));
            rewindLastTickMs = now;

            double maxDelayMs = ramBuffer == null
                    ? 0.0
                    : ramBuffer.getHistorySeconds() * 1000.0;

            if (rewindHolding) {
                if (!rewindAtLimit) {
                    rewindDelayMs += 2.0 * dt;

                    if (rewindDelayMs >= maxDelayMs) {
                        rewindDelayMs = maxDelayMs;
                        rewindAtLimit = true;
                    }
                }
            } else if (rewindCatchup) {
                rewindDelayMs -= 2.0 * dt;

                if (rewindDelayMs <= 0.0) {
                    rewindDelayMs = 0.0;
                    rewindCatchup = false;
                    rewindAtLimit = false;
                    glView.showRamLive();
                    updateOverlay("Rewind LIVE");
                    return;
                }
            }

            if (rewindDelayMs > 0.0) {
                glView.showRamOffsetMs(-Math.round(rewindDelayMs));
            } else {
                glView.showRamLive();
            }

            mainHandler.postDelayed(this, 1000L / 60L);
        }
    };

    void startRewindHold() {
        if (glView == null ||
                glView.getLensMode() != GLView.LensMode.REWIND ||
                ramBuffer == null ||
                ramBuffer.getCount() < 2) {
            return;
        }

        rewindHolding = true;
        rewindCatchup = false;
        rewindAtLimit = false;
        rewindLastTickMs = SystemClock.elapsedRealtime();

        mainHandler.removeCallbacks(rewindTick);
        mainHandler.post(rewindTick);

        updateOverlay("Rewind 1x");
    }

    void releaseRewindHold() {
        if (glView == null ||
                glView.getLensMode() != GLView.LensMode.REWIND) {
            return;
        }

        rewindHolding = false;

        if (rewindDelayMs <= 0.0) {
            rewindCatchup = false;
            glView.showRamLive();
            return;
        }

        rewindCatchup = true;
        rewindAtLimit = false;
        rewindLastTickMs = SystemClock.elapsedRealtime();

        mainHandler.removeCallbacks(rewindTick);
        mainHandler.post(rewindTick);

        updateOverlay("Rewind forward 3x");
    }

    final Runnable ramRevTick = new Runnable() {
        @Override public void run() {
            if (glView == null ||
                    (glView.getLensMode() != GLView.LensMode.RAMREV &&
                     glView.getLensMode() != GLView.LensMode.CREV)) {
                ramRevCycleStartMs = 0L;
                return;
            }

            if (ramBuffer == null ||
                    ramBuffer.getHistorySeconds() < 2.05) {
                glView.showRamLive();
                mainHandler.postDelayed(this, 1000L / 60L);
                return;
            }

            long now = SystemClock.elapsedRealtime();

            if (ramRevCycleStartMs == 0L) {
                ramRevCycleStartMs = now;
            }

            long elapsed = now - ramRevCycleStartMs;

            if (elapsed >= RAMREV_BLOCK_MS) {
                ramRevCycleStartMs +=
                        (elapsed / RAMREV_BLOCK_MS) * RAMREV_BLOCK_MS;
                elapsed = now - ramRevCycleStartMs;
            }

            long delayMs = Math.round(2.0 * elapsed);
            glView.showRamOffsetMs(-delayMs);

            mainHandler.postDelayed(this, 1000L / 60L);
        }
    };

    void startRamRev() {
        ramRevCycleStartMs = 0L;
        mainHandler.removeCallbacks(ramRevTick);
        mainHandler.post(ramRevTick);
        updateOverlay("RAMRev 1080p60");
    }

    final Runnable hudHeartbeat =
            new Runnable() {
                @Override
                public void run() {
                    hudTicks++;
                    refreshOverlay();
                    updateScrubUi();

                    hudHandler.postDelayed(
                            this,
                            100
                    );
                }
            };

    static class PlaybackItem {
        final int cycleId;
        final int slot;
        final File file;
        final int outputFps;

        boolean ready = false;
        boolean started = false;
        boolean done = false;
        boolean failed = false;

        PlaybackItem(
                int cycleId,
                int slot,
                File file,
                int outputFps
        ) {
            this.cycleId = cycleId;
            this.slot = slot;
            this.file = file;
            this.outputFps = outputFps;
        }
    }

    @Override
    public void onCreate(Bundle b) {
        super.onCreate(b);

        enterFullscreen();

        appStartMs =
                SystemClock.elapsedRealtime();

        TraceLog.init(
                Environment
                        .getExternalStoragePublicDirectory(
                                Environment
                                        .DIRECTORY_DOCUMENTS
                        )
        );

        TraceLog.i(
                "MainActivity onCreate"
        );

        diagnostics = new CameraDiagnostics(appStartMs);

        getWindow().addFlags(
                WindowManager
                        .LayoutParams
                        .FLAG_KEEP_SCREEN_ON
        );

        FrameLayout root =
                new FrameLayout(this);

        glView =
                new GLView(this);
        captureFps = selectedCaptureFps;
        captureWidth = 1920;
        captureHeight = 1080;
        playbackFps = 60;
        activeTestPreset = "TAPE3_1080P60";
        glView.setLensMode(GLView.LensMode.TAPE3);

        root.addView(
                glView,
                new FrameLayout.LayoutParams(
                        -1,
                        -1
                )
        );

        // The video surface is a sibling, never a child of the rotating GUI.
        guiRoot = new FrameLayout(this);
        root.addView(guiRoot, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        buildRecordingFrame(guiRoot);
        buildRecordCue(guiRoot);
        buildRecordProgress(guiRoot);
        buildHud(guiRoot);
        buildModeRail(guiRoot);
        buildWatermark(guiRoot);
        buildFlashLabel(guiRoot);
        buildSplash(guiRoot);
        installGuiOrientation(root);

        setContentView(root);

        startImu();


        glView.setOnTouchListener((v, event) -> {
            if (isManualTapeMode()) {
                if (tape3 == null) return true;
                GLView.LensMode mode = glView.getLensMode();
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        if (mode == GLView.LensMode.DOWNSHIFT && downshiftDelayed) {
                            tape3.jumpToLive();
                            downshiftDelayed = false;
                            downshiftResetGesture = true;
                            showTapeOutput();
                        } else {
                            downshiftResetGesture = false;
                            tape3.setPlaybackSpeed(mode == GLView.LensMode.REPLAY ? -1.0 :
                                    mode == GLView.LensMode.MOLASSES ? 0.25 : downshiftFactor);
                            glView.showTape3Delay();
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        if (!downshiftResetGesture) {
                            tape3.setPlaybackSpeed(mode == GLView.LensMode.DOWNSHIFT ? 1.0 : 3.0);
                            downshiftDelayed = mode == GLView.LensMode.DOWNSHIFT;
                        }
                        downshiftResetGesture = false;
                        v.performClick();
                        return true;
                }
                return true;
            }
            if (glView.getLensMode() != GLView.LensMode.REWIND) {
                if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                    diagnosticVisible = !diagnosticVisible;
                    if (diagnosticPanel != null)
                        diagnosticPanel.setVisibility(
                            diagnosticVisible ? View.VISIBLE : View.GONE);
                    v.performClick();
                }
                return true;
            }

            if (glView.getLensMode() != GLView.LensMode.REWIND) {
                return false;
            }

            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    startRewindHold();
                    return true;

                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    releaseRewindHold();
                    return true;
            }

            return true;
        });

        glView.listener = st -> {
            TraceLog.i(
                    "GL camera SurfaceTexture ready"
            );

            cameraTexture = st;

            if (diagnostics != null) {
                diagnostics.previewSurfaceCreated();
            }

            cameraTexture
                    .setDefaultBufferSize(
                            captureWidth,
                            captureHeight
                    );

            previewSurface =
                    new Surface(
                            cameraTexture
                    );

            updateOverlay(
                    "GL preview surface ready"
            );

            checkPermissionAndStart();
        };

        hudHandler.post(
                hudHeartbeat
        );
    }

    void installGuiOrientation(FrameLayout videoRoot) {
        videoRoot.addOnLayoutChangeListener((v, left, top, right, bottom,
                oldLeft, oldTop, oldRight, oldBottom) -> layoutGui(videoRoot));
        guiOrientationListener = new OrientationEventListener(this) {
            private int lastQuadrant = -1;
            @Override public void onOrientationChanged(int orientation) {
                if (orientation == ORIENTATION_UNKNOWN) return;
                int quadrant = ((orientation + 45) / 90 * 90) % 360;
                // Ignore small movement near quadrant boundaries.
                if (lastQuadrant >= 0) {
                    int difference = Math.abs(orientation - lastQuadrant);
                    difference = Math.min(difference, 360 - difference);
                    if (difference < 60) return;
                }
                lastQuadrant = quadrant;
                int displayDegrees = getWindowManager().getDefaultDisplay().getRotation() * 90;
                guiRotation = GuiOrientation.rotation(quadrant, displayDegrees);
                layoutGui(videoRoot);
                TraceLog.i("GUI orientation physical=" + quadrant + " display=" + displayDegrees +
                        " guiTurn=" + guiRotation + " video=FIXED_LANDSCAPE");
            }
        };
        TraceLog.i("View orientation fixed landscape; only GUI follows physical orientation");
    }

    void layoutGui(FrameLayout videoRoot) {
        int width = videoRoot.getWidth(), height = videoRoot.getHeight();
        if (width == 0 || height == 0 || guiRoot == null) return;
        boolean swap = GuiOrientation.swapsAxes(guiRotation);
        int guiWidth = swap ? height : width;
        int guiHeight = swap ? width : height;
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) guiRoot.getLayoutParams();
        if (params.width != guiWidth || params.height != guiHeight) {
            params.width = guiWidth;
            params.height = guiHeight;
            params.gravity = Gravity.CENTER;
            guiRoot.setLayoutParams(params);
        }
        guiRoot.setRotation(guiRotation);
    }

    @Override public void onBackPressed() {
        if (choiceOverlay != null) dismissChoices();
        else super.onBackPressed();
    }

    void dismissChoices() {
        if (choiceOverlay != null && guiRoot != null) guiRoot.removeView(choiceOverlay);
        choiceOverlay = null;
    }

    void buildSplash(FrameLayout root) {
        splashPanel = new LinearLayout(this);
        splashPanel.setOrientation(
                LinearLayout.VERTICAL
        );
        splashPanel.setGravity(
                Gravity.CENTER
        );
        splashPanel.setBackgroundColor(0xcc000000);
        splashPanel.setPadding(dp(28), dp(22), dp(28), dp(22));

        splashTitle = new TextView(this);
        splashTitle.setText("Timetoy");
        splashTitle.setTextColor(
                android.graphics.Color.WHITE
        );
        splashTitle.setTextSize(34);
        splashTitle.setGravity(
                Gravity.CENTER
        );

        TextView byline = new TextView(this);
        byline.setText(
                "by yo"
        );
        byline.setTextColor(
                0xffcccccc
        );
        byline.setTextSize(18);
        byline.setGravity(
                Gravity.CENTER
        );

        TextView version = new TextView(this);
        version.setText(
                VERSION
        );
        version.setTextColor(
                0xff999999
        );
        version.setTextSize(12);
        version.setGravity(
                Gravity.CENTER
        );
        version.setPadding(
                0,
                dp(10),
                0,
                0
        );

        splashStatus = new TextView(this);
        splashStatus.setText("Starting");
        splashStatus.setTextColor(0xffcccccc);
        splashStatus.setTextSize(15);
        splashStatus.setGravity(Gravity.CENTER);
        splashStatus.setPadding(dp(16), dp(22), dp(16), 0);

        splashTracePath = new TextView(this);
        splashTracePath.setText(
                "Trace: " +
                        TraceLog.path()
        );
        splashTracePath.setTextColor(0xff777777);
        splashTracePath.setTextSize(10);
        splashTracePath.setGravity(Gravity.CENTER);
        splashTracePath.setPadding(dp(16), dp(12), dp(16), 0);

        splashPanel.addView(splashTitle);
        splashPanel.addView(byline);
        splashPanel.addView(version);
        splashPanel.addView(splashStatus);
        splashPanel.addView(splashTracePath);

        root.addView(
                splashPanel,
                new FrameLayout.LayoutParams(
                        dp(260),
                        -2,
                        Gravity.CENTER
                )
        );
    }

    void buildWatermark(FrameLayout root) {
        watermarkView = new ImageView(this);
        //watermarkView.setImageResource(R.drawable.timetoy_watermark);
        watermarkView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        watermarkView.setAlpha(0.38f);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(112), dp(132), Gravity.LEFT | Gravity.BOTTOM);
        lp.setMargins(dp(10), 0, 0, dp(10));
        root.addView(watermarkView, lp);
    }

    void buildRecordingFrame(FrameLayout root) {
        recordingFrame = new View(this);

        android.graphics.drawable.GradientDrawable border =
                new android.graphics.drawable.GradientDrawable();

        border.setColor(
                android.graphics.Color.TRANSPARENT
        );

        border.setStroke(
                2,
                TIMETOY_ORANGE
        );

        recordingFrame.setBackground(border);
        recordingFrame.setVisibility(View.GONE);

        FrameLayout.LayoutParams params =
                new FrameLayout.LayoutParams(
                        -1,
                        -1
                );

        int inset = dp(5);
        params.setMargins(
                inset,
                inset,
                inset,
                inset
        );

        root.addView(
                recordingFrame,
                params
        );
    }

    void buildFlashLabel(FrameLayout root) {
        flashLabel = new TextView(this);

        flashLabel.setTextColor(
                android.graphics.Color.WHITE
        );

        flashLabel.setBackgroundColor(
                0x99000000
        );

        flashLabel.setTextSize(28);
        flashLabel.setGravity(Gravity.CENTER);
        flashLabel.setPadding(
                dp(24),
                dp(12),
                dp(24),
                dp(12)
        );

        flashLabel.setVisibility(View.GONE);

        root.addView(
                flashLabel,
                new FrameLayout.LayoutParams(
                        -2,
                        -2,
                        Gravity.CENTER
                )
        );
    }

    int dp(int value) {
        return Math.round(
                value *
                        getResources()
                                .getDisplayMetrics()
                                .density
        );
    }

    void setRecordingIndicator(boolean visible) {
        final boolean show = visible &&
                glView != null &&
                glView.getLensMode() == GLView.LensMode.SLOW;

        runOnUiThread(() -> {
            if (recordingFrame != null) {
                recordingFrame.setVisibility(show ? View.VISIBLE : View.GONE);
            }
        });
    }

    void flashStatus(
            String text,
            long durationMs
    ) {
        runOnUiThread(() -> {
            if (flashLabel == null) {
                return;
            }

            flashLabel.setText(text);
            flashLabel.setVisibility(View.VISIBLE);

            mainHandler.removeCallbacks(
                    hideFlashLabel
            );

            mainHandler.postDelayed(
                    hideFlashLabel,
                    durationMs
            );
        });
    }


    void buildRecordCue(FrameLayout root) {
        recordCue = new View(this);

        android.graphics.drawable.GradientDrawable shape =
                new android.graphics.drawable.GradientDrawable();

        // Lighter violet timing cue.
        shape.setColor(0x889f67d5);
        shape.setCornerRadius(0f);
        recordCue.setBackground(shape);
        recordCue.setVisibility(View.GONE);

        FrameLayout.LayoutParams params =
                new FrameLayout.LayoutParams(
                        dp(24),
                        dp(24),
                        Gravity.TOP | Gravity.RIGHT
                );

        params.setMargins(0, dp(12), dp(12), 0);
        root.addView(recordCue, params);
    }

    void startRecordCue(Runnable onComplete) {
        startRecordCue(recordCueMs, onComplete);
    }
    void startRecordCue(int cueDurationMs, Runnable onComplete) {
        if (cueDurationMs <= 0) {
            if (onComplete != null) {
                onComplete.run();
            }
            return;
        }

        runOnUiThread(() -> {
            if (recordCue == null) {
                if (onComplete != null) onComplete.run();
                return;
            }

            recordCue.animate().cancel();
            recordCue.setScaleX(1.0f);
            recordCue.setScaleY(1.0f);
            recordCue.setAlpha(1.0f);
            recordCue.setPivotX(dp(12));
            recordCue.setPivotY(dp(24));
            recordCue.setVisibility(View.VISIBLE);

            recordCue.animate()
                    .scaleX(1.0f)
                    .scaleY(0.0f)
                    .setDuration(cueDurationMs)
                    .withEndAction(() -> {
                        // Remain as a flat violet line until actual recording
                        // begins. recorder.start() is the only hide event.
                        recordCue.setScaleY(0.02f);
                        if (onComplete != null) onComplete.run();
                    })
                    .start();
        });
    }

    void hideRecordCue() {
        runOnUiThread(() -> {
            if (recordCue != null) {
                recordCue.animate().cancel();
                recordCue.setVisibility(View.GONE);
            }
        });
    }

    void buildRecordProgress(FrameLayout root) {
        recordProgress = new View(this);

        android.graphics.drawable.GradientDrawable shape =
                new android.graphics.drawable.GradientDrawable();

        shape.setColor(TIMETOY_ORANGE);
        shape.setCornerRadius(0f);

        recordProgress.setBackground(shape);
        recordProgress.setVisibility(View.GONE);

        FrameLayout.LayoutParams params =
                new FrameLayout.LayoutParams(
                        dp(24),
                        dp(24),
                        Gravity.TOP | Gravity.RIGHT
                );

        params.setMargins(0, dp(12), dp(12), 0);
        root.addView(recordProgress, params);
    }

    void startRecordProgress(int durationMs) {
        final boolean show = glView != null &&
                glView.getLensMode() == GLView.LensMode.SLOW;

        runOnUiThread(() -> {
            if (recordProgress == null || !show) {
                if (recordProgress != null) recordProgress.setVisibility(View.GONE);
                return;
            }

            recordProgress.animate().cancel();
            recordProgress.setScaleX(1.0f);
            recordProgress.setScaleY(0.0f);
            recordProgress.setPivotY(dp(24));
            recordProgress.setVisibility(View.VISIBLE);

            recordProgress.animate()
                    .scaleY(1.0f)
                    .setDuration(durationMs)
                    .start();
        });
    }

    void hideRecordProgress() {
        runOnUiThread(() -> {
            if (recordProgress != null) {
                recordProgress.animate().cancel();
                recordProgress.setVisibility(View.GONE);
            }
        });
    }

    void updateScrubUi() {
        // Legacy scrub modes are not exposed. Wobble uses the rail popups.
        if (scrubPanel != null) scrubPanel.setVisibility(View.GONE);
    }

    void buildModeRail(FrameLayout root) {
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
        railCamera = makeRailText("CAM 60");

        modeRail.addView(railTT);
        modeRail.addView(railView);
        modeRail.addView(railTime);
        modeRail.addView(railParam);
        modeRail.addView(railFX);
        modeRail.addView(railShare);
        modeRail.addView(railCamera);

        railTT.setOnClickListener(v ->
                new android.app.AlertDialog.Builder(this)
                        .setTitle("TimeToy")
                        .setMessage("TimeToy\n" + VERSION + "\nby yo")
                        .setPositiveButton("OK", null)
                        .show());

        railCamera.setOnClickListener(v -> showCameraChoices(railCamera));
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
        showChoicePopup(anchor, labels, actions, null);
    }

    void showChoicePopup(View anchor, String[] labels, Runnable[] actions, int[] colors) {
        dismissChoices();
        FrameLayout overlay = new FrameLayout(this);
        choiceOverlay = overlay;
        overlay.setOnClickListener(v -> dismissChoices());
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(4), dp(4), dp(4), dp(4));
        box.setBackgroundColor(0xee202020);
        box.setClickable(true);
        for (int i = 0; i < labels.length; i++) {
            final int index = i;
            Button button = new Button(this);
            button.setAllCaps(false);
            button.setText(labels[i]);
            button.setTextSize(13);
            button.setMinHeight(0);
            button.setMinimumHeight(0);
            button.setPadding(dp(8), dp(4), dp(8), dp(4));
            if (colors != null && i < colors.length) {
                button.setBackgroundColor(colors[i]);
                button.setTextColor(0xff000000);
            }
            button.setOnClickListener(v -> {
                dismissChoices();
                if (actions[index] != null) actions[index].run();
            });
            box.addView(button, new LinearLayout.LayoutParams(-1, dp(44)));
        }
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(false);
        scroll.addView(box);
        int availableWidth = Math.max(dp(178), guiRoot.getWidth());
        int availableHeight = Math.max(dp(44), guiRoot.getHeight() - dp(20));
        FrameLayout.LayoutParams palette = new FrameLayout.LayoutParams(
                dp(178), Math.min(dp(labels.length * 44 + 8), availableHeight), Gravity.TOP | Gravity.LEFT);
        palette.leftMargin = Math.min(dp(140), Math.max(dp(4), availableWidth - dp(182)));
        palette.topMargin = dp(10);
        overlay.addView(scroll, palette);
        guiRoot.addView(overlay, new FrameLayout.LayoutParams(-1, -1));
    }

    void showModeChoices(View anchor) {
        // Chunked, contiguous, manual; Downshift intentionally closes the list.
        showChoicePopup(anchor,
                new String[]{"SLOW", "FAST", "REVERSE", "STUTTER", "WOBBLE", "REPLAY", "MOLASSES", "DOWNSHIFT"},
                new Runnable[]{
                        () -> setLensMode(GLView.LensMode.SLOW),
                        () -> setLensMode(GLView.LensMode.FAST),
                        () -> setLensMode(GLView.LensMode.DUBBUF_REVERSE),
                        () -> setLensMode(GLView.LensMode.STUTTER),
                        () -> setLensMode(GLView.LensMode.TAPE3),
                        () -> setLensMode(GLView.LensMode.REPLAY),
                        () -> setLensMode(GLView.LensMode.MOLASSES),
                        () -> setLensMode(GLView.LensMode.DOWNSHIFT)
                },
                new int[]{MODE_SLOW, MODE_FAST, MODE_REVERSE, MODE_STUTTER,
                        MODE_CREV, MODE_REWIND, MODE_SLOW, MODE_REWIND});
    }

    void showTimeChoices(View anchor) {
        GLView.LensMode m = glView.getLensMode();
        if (m == GLView.LensMode.TAPE3) {
            long[] values = {0, 250, 500, 1000, 2000, 4000};
            Runnable[] actions = new Runnable[values.length];
            String[] labels = new String[values.length];
            for (int i = 0; i < values.length; i++) {
                final long value = values[i];
                labels[i] = String.format(Locale.US, "Delay %.3f s", value / 1000.0);
                actions[i] = () -> {
                    wobbleDelayMs = value;
                    if (tape3 != null) tape3.setDelayMs(value);
                    updateModeRail();
                };
            }
            showChoicePopup(anchor, labels, actions);
        } else if (m == GLView.LensMode.SLOW) {
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
        if (m == GLView.LensMode.TAPE3) {
            long[] values = {0, 125, 250, 500, 1000, 2000};
            Runnable[] actions = new Runnable[values.length];
            String[] labels = new String[values.length];
            for (int i = 0; i < values.length; i++) {
                final long value = values[i];
                labels[i] = "Amount " + String.format(Locale.US, "%.3f s", value / 1000.0);
                actions[i] = () -> {
                    lfoAmountMs = value;
                    if (tape3 != null) tape3.setLfoAmountMs(value);
                    updateModeRail();
                };
            }
            showChoicePopup(anchor, labels, actions);
        } else if (m == GLView.LensMode.DOWNSHIFT) {
            float[] factors = {0.125f, 0.25f, 0.5f};
            Runnable[] actions = new Runnable[factors.length];
            for (int i = 0; i < factors.length; i++) {
                final float factor = factors[i];
                actions[i] = () -> { downshiftFactor = factor; updateModeRail(); };
            }
            showChoicePopup(anchor, new String[]{"1/8", "1/4", "1/2"}, actions);
        } else if (m == GLView.LensMode.SLOW) {
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

    void applyCaptureControls(CaptureRequest.Builder request, boolean highSpeed) throws Exception {
        CameraCharacteristics c = cameraManager.getCameraCharacteristics(cameraDevice.getId());
        boolean manual = manualExposure && !highSpeed && hasManualSensor(c);
        List<CaptureRequest.Key<?>> keys = c.getAvailableCaptureRequestKeys();
        boolean rotateAndCropDisabled = false;
        if (keys != null) for (CaptureRequest.Key<?> key : keys) {
            if ("android.scaler.rotateAndCrop".equals(key.getName())) {
                request.set(new CaptureRequest.Key<Integer>("android.scaler.rotateAndCrop", Integer.class), 0);
                rotateAndCropDisabled = true;
                break;
            }
        }
        TraceLog.i("Camera rotate-and-crop=" + (rotateAndCropDisabled ? "NONE" : "not exposed"));
        request.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
        request.set(CaptureRequest.CONTROL_AE_MODE, manual
                ? CaptureRequest.CONTROL_AE_MODE_OFF : CaptureRequest.CONTROL_AE_MODE_ON);
        Range<Integer> fixedFps = new Range<Integer>(captureFps, captureFps);
        Range<Integer>[] advertised = c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
        boolean fixedFpsAdvertised = false;
        if (advertised != null) for (Range<Integer> range : advertised)
            if (fixedFps.equals(range)) fixedFpsAdvertised = true;
        // Some HALs retain TEMPLATE_RECORD's 30 FPS range even with AE off.
        // Replace it in manual mode too, whenever the exact range is advertised.
        if (!manual || fixedFpsAdvertised)
            request.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fixedFps);
        TraceLog.i("FPS control requested=" + captureFps + " fixedRangeAdvertised=" + fixedFpsAdvertised +
                " availableAE=" + Arrays.toString(advertised) +
                " requestAE=" + request.get(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE));
        if (manual) {
            Range<Long> exposureRange = c.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE);
            Range<Integer> isoRange = c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);
            if (exposureRange == null || isoRange == null)
                throw new IllegalStateException("Manual sensor limits unavailable");
            long exposure = CaptureTiming.exposureNs(shutterNs, captureFps,
                    exposureRange.getLower(), exposureRange.getUpper());
            int iso = Math.max(isoRange.getLower(), Math.min(isoRange.getUpper(), sensorIso));
            request.set(CaptureRequest.SENSOR_FRAME_DURATION, CaptureTiming.frameNs(captureFps));
            request.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposure);
            request.set(CaptureRequest.SENSOR_SENSITIVITY, iso);
            shutterNs = exposure;
            sensorIso = iso;
            TraceLog.i("Manual sensor FPS=" + captureFps + " exposureNs=" + exposure + " ISO=" + iso);
        }
    }

    void showCameraChoices(View anchor) {
        String exposure = captureHighSpeed ? "Exposure: Auto (HS)" :
                !manualSensorAvailable ? "Exposure: Auto (camera limit)" :
                manualExposure ? "Exposure: Manual" : "Exposure: Auto";
        showChoicePopup(anchor,
                new String[]{"FPS: " + captureFps, exposure, "Shutter", "ISO: " + sensorIso, "Resolution"},
                new Runnable[]{
                        () -> showChoicePopup(anchor, new String[]{"60 FPS", "120 FPS (HS: Auto)"},
                                new Runnable[]{() -> setCaptureFps(60), () -> setCaptureFps(120)}),
                        () -> showChoicePopup(anchor, new String[]{"Manual exposure", "Auto exposure"},
                                new Runnable[]{() -> setExposureMode(true), () -> setExposureMode(false)}),
                        () -> showShutterChoices(anchor),
                        () -> showIsoChoices(anchor),
                        () -> showChoicePopup(anchor, new String[]{"1080p", "720p"},
                                new Runnable[]{() -> setCaptureResolution(1920, 1080), () -> setCaptureResolution(1280, 720)})
                });
    }

    boolean canSetManualExposure() {
        if (cameraDevice == null) { flashStatus("Camera is not ready; choose FPS or resolution first.", 2500); return false; }
        if (captureHighSpeed || !manualSensorAvailable) {
            flashStatus(captureHighSpeed ? "High-speed capture forces Auto exposure. Try 60 FPS for manual control." :
                    "This camera does not advertise manual sensor control.", 3500);
            return false;
        }
        return true;
    }

    void setExposureMode(boolean manual) {
        if (manual && !canSetManualExposure()) return;
        boolean previous = manualExposure;
        manualExposure = manual;
        try {
            if (cameraManager != null) selectCaptureCamera(captureWidth, captureHeight, captureFps, false);
        } catch (Exception e) { manualExposure = previous; flashStatus(e.getMessage(), 3500); return; }
        restartForCaptureOptions(manual ? "Manual exposure" : "Auto exposure");
    }

    void showShutterChoices(View anchor) {
        if (!canSetManualExposure()) return;
        int[] denominators = {60, 120, 240, 500, 1000, 2000};
        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        try {
            CameraCharacteristics c = cameraManager.getCameraCharacteristics(cameraDevice.getId());
            Range<Long> range = c.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE);
            for (int denominator : denominators) {
                final long ns = 1_000_000_000L / denominator;
                if (denominator < captureFps || range == null || !range.contains(ns)) continue;
                labels.add("1/" + denominator + " s");
                actions.add(() -> { shutterNs = ns; setExposureMode(true); });
            }
        } catch (Exception e) { flashStatus("Camera is restarting; try again.", 1800); return; }
        if (labels.isEmpty()) { flashStatus("No listed shutters fit this camera and FPS.", 2500); return; }
        showChoicePopup(anchor, labels.toArray(new String[0]), actions.toArray(new Runnable[0]));
    }

    void showIsoChoices(View anchor) {
        if (!canSetManualExposure()) return;
        int[] values = {100, 200, 400, 800, 1600, 3200};
        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        try {
            CameraCharacteristics c = cameraManager.getCameraCharacteristics(cameraDevice.getId());
            Range<Integer> range = c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);
            for (int value : values) {
                if (range == null || !range.contains(value)) continue;
                labels.add("ISO " + value);
                actions.add(() -> { sensorIso = value; setExposureMode(true); });
            }
        } catch (Exception e) { flashStatus("Camera is restarting; try again.", 1800); return; }
        if (labels.isEmpty()) { flashStatus("No listed ISO values fit this camera.", 2500); return; }
        showChoicePopup(anchor, labels.toArray(new String[0]), actions.toArray(new Runnable[0]));
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
            case SCRUB: return MODE_SCRUB;
            case REWIND: return MODE_REWIND;
            case RAMREV: return MODE_RAMREV;
            case CREV: return MODE_CREV;
            case TAPE3: return MODE_CREV;
            case REPLAY: return MODE_REWIND;
            case MOLASSES: return MODE_SLOW;
            case DOWNSHIFT: return MODE_REWIND;
            default: return MODE_REVERSE;
        }
    }

    void applyModeColor() {
        int c = modeColor();
        TextView[] rack = {railTT, railView, railTime, railParam, railFX, railShare, railCamera};
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
        String factor = "—";

        if (m == GLView.LensMode.CREV) {
            time = ramBuffer == null ? "0.0 s" :
                    String.format(Locale.US, "%.1f s", ramBuffer.getHistorySeconds());
            factor = "1x";
        } else if (m == GLView.LensMode.RAMREV) {
            time = "1.0 s";
            factor = "1x";
        } else if (m == GLView.LensMode.REWIND) {
            time = ramBuffer == null
                    ? "0.0 s"
                    : String.format(Locale.US, "%.1f s", ramBuffer.getHistorySeconds());
            factor = "1x / 3x";
        } else if (m == GLView.LensMode.SCRUB) {
            time = ramBuffer == null
                    ? "0.0 s"
                    : String.format(Locale.US, "%.1f s", ramBuffer.getHistorySeconds());
        } else if (m == GLView.LensMode.SLOW) {
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

        boolean manual = isManualTapeMode();
        boolean wobble = m == GLView.LensMode.TAPE3;
        railTime.setVisibility(manual ? View.GONE : View.VISIBLE);
        railParam.setVisibility((manual && m != GLView.LensMode.DOWNSHIFT) ||
                m == GLView.LensMode.DUBBUF_REVERSE ? View.GONE : View.VISIBLE);
        if (m == GLView.LensMode.DOWNSHIFT)
            factor = downshiftFactor == 0.125f ? "1/8" : downshiftFactor == 0.5f ? "1/2" : "1/4";
        railView.setText(currentLensTitle().toUpperCase(Locale.US));
        railTime.setText(wobble ? String.format(Locale.US, "DELAY %.3f s", wobbleDelayMs / 1000.0) : time.toUpperCase(Locale.US));
        if (wobble) factor = "AMOUNT " + String.format(Locale.US, "%.3f s", lfoAmountMs / 1000.0);
        railParam.setText(factor);
        railFX.setText("FX");
        railShare.setText("SHARE");
        railCamera.setText("CAM " + captureFps + (captureHighSpeed || !manualSensorAvailable || !manualExposure ? " A" : " M"));
        applyModeColor();
    }

    void buildHud(FrameLayout root) {
        hudPanel = new LinearLayout(this);
        hudPanel.setOrientation(LinearLayout.VERTICAL);
        hudPanel.setBackgroundColor(0x66000000);
        hudPanel.setPadding(18, 18, 18, 18);

        LinearLayout lensRow = new LinearLayout(this);
        lensRow.setOrientation(LinearLayout.HORIZONTAL);

        // Reuse the old button variable; Legacy Reverse is removed.
        reverseLensButton = makeControlButton("Fast");
        dubBufReverseButton = makeControlButton("Reverse");
        slowLensButton = makeControlButton("Slow");
        freezeLensButton = makeControlButton("Strobe");
        stutterLensButton = makeControlButton("Stutter");

        lensRow.addView(reverseLensButton, weightedButtonParams());
        lensRow.addView(dubBufReverseButton, weightedButtonParams());
        lensRow.addView(slowLensButton, weightedButtonParams());
        lensRow.addView(freezeLensButton, weightedButtonParams());
        lensRow.addView(stutterLensButton, weightedButtonParams());

        slowControlRow = new LinearLayout(this);
        LinearLayout speedRow = slowControlRow;
        speedRow.setOrientation(LinearLayout.HORIZONTAL);

        speed16Button = makeControlButton("Slow 16×");
        speed8Button = makeControlButton("Slow 8×");
        speed4Button = makeControlButton("Slow 4×");

        speedRow.addView(speed16Button, weightedButtonParams());
        speedRow.addView(speed8Button, weightedButtonParams());
        speedRow.addView(speed4Button, weightedButtonParams());

        LinearLayout gainRow = new LinearLayout(this);
        gainRow.setOrientation(LinearLayout.HORIZONTAL);

        gain1Button = makeControlButton("Bright 1×");
        gain2Button = makeControlButton("Bright 2×");
        gain4Button = makeControlButton("Bright 4×");

        gainRow.addView(gain1Button, weightedButtonParams());
        gainRow.addView(gain2Button, weightedButtonParams());
        gainRow.addView(gain4Button, weightedButtonParams());

        LinearLayout zoomRow = new LinearLayout(this);
        zoomRow.setOrientation(LinearLayout.HORIZONTAL);

        zoom1Button = makeControlButton("Zoom 1×");
        zoom15Button = makeControlButton("Zoom 1.5×");
        zoom2Button = makeControlButton("Zoom 2×");

        zoomRow.addView(zoom1Button, weightedButtonParams());
        zoomRow.addView(zoom15Button, weightedButtonParams());
        zoomRow.addView(zoom2Button, weightedButtonParams());

        LinearLayout resolutionRow = new LinearLayout(this);
        resolutionRow.setOrientation(LinearLayout.HORIZONTAL);

        resolution1080Button = makeControlButton("1080p");
        resolution720Button = makeControlButton("720p");

        resolutionRow.addView(
                resolution1080Button,
                weightedButtonParams()
        );
        resolutionRow.addView(
                resolution720Button,
                weightedButtonParams()
        );

        LinearLayout captureRateRow = new LinearLayout(this);
        captureRateRow.setOrientation(LinearLayout.HORIZONTAL);

        capture240Button = makeControlButton("Capture 240");
        capture120Button = makeControlButton("Capture 120");
        capture60Button = makeControlButton("Capture 60");
        capture30Button = makeControlButton("Capture 30");

        captureRateRow.addView(capture240Button, scrollingButtonParams());
        captureRateRow.addView(capture120Button, scrollingButtonParams());
        captureRateRow.addView(capture60Button, scrollingButtonParams());
        captureRateRow.addView(capture30Button, scrollingButtonParams());

        LinearLayout lengthRow = new LinearLayout(this);
        lengthRow.setOrientation(LinearLayout.HORIZONTAL);

        length05Button = makeControlButton("Length 0.5s");
        length10Button = makeControlButton("Length 1.0s");

        lengthRow.addView(
                length05Button,
                weightedButtonParams()
        );
        lengthRow.addView(
                length10Button,
                weightedButtonParams()
        );

        LinearLayout bitrateRow = new LinearLayout(this);
        bitrateRow.setOrientation(LinearLayout.HORIZONTAL);

        bitrate20Button = makeControlButton("20 Mb");
        bitrate40Button = makeControlButton("40 Mb");
        bitrate80Button = makeControlButton("80 Mb");

        bitrateRow.addView(
                bitrate20Button,
                weightedButtonParams()
        );
        bitrateRow.addView(
                bitrate40Button,
                weightedButtonParams()
        );
        bitrateRow.addView(
                bitrate80Button,
                weightedButtonParams()
        );

        LinearLayout cueRow = new LinearLayout(this);
        cueRow.setOrientation(LinearLayout.HORIZONTAL);

        cueOffButton = makeControlButton("Cue Off");
        cue05Button = makeControlButton("Cue 0.5s");
        cue10Button = makeControlButton("Cue 1.0s");

        cueRow.addView(cueOffButton, weightedButtonParams());
        cueRow.addView(cue05Button, weightedButtonParams());
        cueRow.addView(cue10Button, weightedButtonParams());

        TextView testLabel = new TextView(this);
        testLabel.setText("Reverse test matrix");
        testLabel.setTextColor(0xffffffff);
        testLabel.setTextSize(11);
        testLabel.setPadding(dp(3), dp(3), dp(3), dp(1));

        LinearLayout reverseResolutionRow = new LinearLayout(this);
        reverseResolutionRow.setOrientation(LinearLayout.HORIZONTAL);
        reverseRes1080Button = makeSmallControlButton("1080");
        reverseRes720Button = makeSmallControlButton("720");
        reverseRes540Button = makeSmallControlButton("540");
        reverseResVgaButton = makeSmallControlButton("VGA");
        reverseResolutionRow.addView(reverseRes1080Button, tinyButtonParams());
        reverseResolutionRow.addView(reverseRes720Button, tinyButtonParams());
        reverseResolutionRow.addView(reverseRes540Button, tinyButtonParams());
        reverseResolutionRow.addView(reverseResVgaButton, tinyButtonParams());

        timeControlRow = new LinearLayout(this);
        LinearLayout reverseDurationRow = timeControlRow;
        reverseDurationRow.setOrientation(LinearLayout.HORIZONTAL);
        reverseTime16Button = makeSmallControlButton("0.5 s");
        reverseTime4Button = makeSmallControlButton("1 s");
        reverseTime8Button = makeSmallControlButton("2 s");
        standardReverseButton = makeSmallControlButton("4 s");
        reverseDurationRow.addView(reverseTime16Button, tinyButtonParams());
        reverseDurationRow.addView(reverseTime4Button, tinyButtonParams());
        reverseDurationRow.addView(reverseTime8Button, tinyButtonParams());
        reverseDurationRow.addView(standardReverseButton, tinyButtonParams());

        freezeRateRow = new LinearLayout(this);
        freezeRateRow.setOrientation(LinearLayout.HORIZONTAL);
        freeze2Button = makeSmallControlButton("2 Hz");
        freeze4Button = makeSmallControlButton("4 Hz");
        freeze5Button = makeSmallControlButton("5 Hz");
        freeze6Button = makeSmallControlButton("6 Hz");
        freeze8Button = makeSmallControlButton("8 Hz");
        freeze10Button = makeSmallControlButton("10 Hz");
        freezeRateRow.addView(freeze2Button, tinyButtonParams());
        freezeRateRow.addView(freeze4Button, tinyButtonParams());
        freezeRateRow.addView(freeze5Button, tinyButtonParams());
        freezeRateRow.addView(freeze6Button, tinyButtonParams());
        freezeRateRow.addView(freeze8Button, tinyButtonParams());
        freezeRateRow.addView(freeze10Button, tinyButtonParams());

        stutterSlicesRow = new LinearLayout(this);
        stutterSlicesRow.setOrientation(LinearLayout.HORIZONTAL);
        slices2Button = makeSmallControlButton("2");
        slices4Button = makeSmallControlButton("4");
        slices6Button = makeSmallControlButton("6");
        slices8Button = makeSmallControlButton("8");
        stutterSlicesRow.addView(slices2Button, tinyButtonParams());
        stutterSlicesRow.addView(slices4Button, tinyButtonParams());
        stutterSlicesRow.addView(slices6Button, tinyButtonParams());
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
         * Controls first: diagnostics can no longer push them off-screen.
         */
        // The mode rail is the single canonical mode picker.
        lensRow.setVisibility(View.GONE);
        hudPanel.addView(lensRow);
        effectControlLabel = new TextView(this);
        effectControlLabel.setTextColor(0xffffffff);
        effectControlLabel.setTextSize(12);
        effectControlLabel.setPadding(dp(3), dp(5), dp(3), dp(1));
        hudPanel.addView(effectControlLabel);
        hudPanel.addView(timeControlRow);
        hudPanel.addView(slowControlRow);
        hudPanel.addView(stutterSlicesRow);
        hudPanel.addView(fastSpeedRow);
        hudPanel.addView(freezeRateRow);

        FrameLayout.LayoutParams hudParams =
                new FrameLayout.LayoutParams(
                        0,
                        -2,
                        Gravity.TOP | Gravity.LEFT
                );

        hudParams.width =
                getResources()
                        .getDisplayMetrics()
                        .widthPixels / 2;

        root.addView(hudPanel, hudParams);
        hudPanel.setVisibility(View.GONE);

        // Compact, always-visible instrument panel. Controls still toggle on tap.
        diagnosticPanel = new LinearLayout(this);
        diagnosticPanel.setOrientation(LinearLayout.VERTICAL);
        diagnosticPanel.setBackgroundColor(0x88000000);
        diagnosticPanel.setPadding(dp(10), dp(8), dp(10), dp(8));

        overlay = new TextView(this);
        overlay.setTextColor(0xffffffff);
        overlay.setTextSize(12);
        overlay.setGravity(Gravity.RIGHT);
        overlay.setTypeface(android.graphics.Typeface.MONOSPACE);
        overlay.setSingleLine(false);
        diagnosticPanel.addView(overlay, new LinearLayout.LayoutParams(-2, -2));

        FrameLayout.LayoutParams diagnosticParams =
                new FrameLayout.LayoutParams(
                        -2,
                        -2,
                        Gravity.TOP | Gravity.RIGHT
                );
        diagnosticParams.setMargins(0, dp(44), dp(10), 0);
        root.addView(diagnosticPanel, diagnosticParams);

        speed16Button.setOnClickListener(v -> {
            playbackFps = 15;
            updateControlButtons();
            updateOverlay("Playback speed: 16×");
        });

        speed8Button.setOnClickListener(v -> {
            playbackFps = 30;
            updateControlButtons();
            updateOverlay("Playback speed: 8×");
        });

        speed4Button.setOnClickListener(v -> {
            playbackFps = 60;
            updateControlButtons();
            updateOverlay("Playback speed: 4×");
        });


        reverseLensButton.setOnClickListener(v ->
                setLensMode(GLView.LensMode.FAST));
        dubBufReverseButton.setOnClickListener(v ->
                setLensMode(GLView.LensMode.DUBBUF_REVERSE));

        slowLensButton.setOnClickListener(v ->
                setLensMode(GLView.LensMode.SLOW));

        freezeLensButton.setOnClickListener(v ->
                setLensMode(GLView.LensMode.FREEZE));
        freeze2Button.setOnClickListener(v -> setFreezeFrequency(2.0f));
        freeze4Button.setOnClickListener(v -> setFreezeFrequency(4.0f));
        freeze5Button.setOnClickListener(v -> setFreezeFrequency(5.0f));
        freeze6Button.setOnClickListener(v -> setFreezeFrequency(6.0f));
        freeze8Button.setOnClickListener(v -> setFreezeFrequency(8.0f));
        freeze10Button.setOnClickListener(v -> setFreezeFrequency(10.0f));
        stutterLensButton.setOnClickListener(v ->
                setLensMode(GLView.LensMode.STUTTER));
        slices2Button.setOnClickListener(v -> setStutterSlices(2));
        slices4Button.setOnClickListener(v -> setStutterSlices(4));
        slices6Button.setOnClickListener(v -> setStutterSlices(6));
        slices8Button.setOnClickListener(v -> setStutterSlices(8));
        fast1Button.setOnClickListener(v -> setFastSpeed(1.0f));
        fast15Button.setOnClickListener(v -> setFastSpeed(1.5f));
        fast2Button.setOnClickListener(v -> setFastSpeed(2.0f));
        fast3Button.setOnClickListener(v -> setFastSpeed(3.0f));
        fast4Button.setOnClickListener(v -> setFastSpeed(4.0f));

        resolution1080Button.setOnClickListener(
                v -> setCaptureResolution(1920, 1080)
        );

        resolution720Button.setOnClickListener(
                v -> setCaptureResolution(1280, 720)
        );

        capture240Button.setOnClickListener(
                v -> setCaptureFps(240)
        );

        capture120Button.setOnClickListener(
                v -> setCaptureFps(120)
        );

        capture60Button.setOnClickListener(
                v -> setCaptureFps(60)
        );

        capture30Button.setOnClickListener(
                v -> setCaptureFps(30)
        );

        length05Button.setOnClickListener(
                v -> setCaptureDuration(500)
        );

        length10Button.setOnClickListener(
                v -> setCaptureDuration(1000)
        );

        bitrate20Button.setOnClickListener(
                v -> setCaptureBitrate(20_000_000)
        );

        bitrate40Button.setOnClickListener(
                v -> setCaptureBitrate(40_000_000)
        );

        bitrate80Button.setOnClickListener(
                v -> setCaptureBitrate(80_000_000)
        );

        cueOffButton.setOnClickListener(
                v -> setRecordCueMs(0)
        );

        cue05Button.setOnClickListener(
                v -> setRecordCueMs(500)
        );

        cue10Button.setOnClickListener(
                v -> setRecordCueMs(1000)
        );

        reverseRes1080Button.setOnClickListener(v -> setReverseTestResolution(1920, 1080, "1080"));
        reverseRes720Button.setOnClickListener(v -> setReverseTestResolution(1280, 720, "720"));
        reverseRes540Button.setOnClickListener(v -> setReverseTestResolution(960, 540, "540"));
        reverseResVgaButton.setOnClickListener(v -> setReverseTestResolution(640, 480, "VGA"));
        reverseTime16Button.setOnClickListener(v -> selectTimeChoice(0));
        reverseTime4Button.setOnClickListener(v -> selectTimeChoice(1));
        reverseTime8Button.setOnClickListener(v -> selectTimeChoice(2));
        standardReverseButton.setOnClickListener(v -> selectTimeChoice(3));

        refreshTestPresetSupport();
        updateControlButtons();
    }

    void hideSplashAfterFirstFrame() {
        if (splashPanel == null) return;
        splashPanel.animate().alpha(0f).setDuration(250)
                .withEndAction(() -> {
                    splashPanel.setVisibility(View.GONE);
                    splashPanel.setAlpha(1f);
                }).start();
    }

    void startPhase2() {
        TraceLog.i(
                "startPhase2 requested"
        );

        if (running) {
            return;
        }

        if (cameraDevice == null ||
                cameraHandler == null) {

            updateOverlay(
                    "Camera not ready"
            );

            return;
        }

        running = true;

        if (GLView.isTapeMode(glView.getLensMode())) {
            updateOverlay("Starting Tape3 " + captureFps + " FPS All-I");
            startTape3Session(() -> {
                TraceLog.i("TAPE3 active requested FPS=" + captureFps);
                mainHandler.postDelayed(() -> {
                    if (tape3 != null &&
                            GLView.isTapeMode(glView.getLensMode())) {
                        showTapeOutput();
                    }
                }, 1500L);
                hideSplashAfterFirstFrame();
            });
            return;
        }
        if (glView.getLensMode() == GLView.LensMode.RAMREV ||
                glView.getLensMode() == GLView.LensMode.CREV) {
            updateOverlay(glView.getLensMode() == GLView.LensMode.CREV
                    ? "Starting CRev 1080p60" : "Starting RAMRev 1080p60");
            TraceLog.i("DBG CRev 01 before startRamPreviewSession");
            startRamPreviewSession(() -> {
                TraceLog.i("DBG CRev 20 onReady entered");
                TraceLog.i("DBG CRev 21 before setRamBuffer");
                glView.setRamBuffer(ramBuffer);
                TraceLog.i("DBG CRev 22 before showRamLive");
                glView.showRamLive();
                TraceLog.i("DBG CRev 23 before startRamRev");
                startRamRev();
                TraceLog.i("DBG CRev 24 onReady complete");

                TraceLog.i("RAMRev active 1080p60 reverse 1x");
                hideSplashAfterFirstFrame();
            });
            TraceLog.i("DBG CRev 02 startRamPreviewSession returned");
            return;
        }

        if (glView.getLensMode() == GLView.LensMode.REWIND) {
            updateOverlay("Starting RAM Rewind 1080p60");
            startRamPreviewSession(() -> {
                glView.setRamBuffer(ramBuffer);
                glView.showRamLive();

                rewindHolding = false;
                rewindCatchup = false;
                rewindAtLimit = false;
                rewindDelayMs = 0.0;

                TraceLog.i("RAM Rewind active 1x back / 3x forward");
                hideSplashAfterFirstFrame();
            });
            return;
        }

        if (glView.getLensMode() == GLView.LensMode.SCRUB) {
            updateOverlay("Starting RAM Scrub 1080p60");
            startRamPreviewSession(() -> {
                glView.setRamBuffer(ramBuffer);
                glView.showRamLive();
                updateScrubUi();
                TraceLog.i("RAM Scrub active; hide splash");
                hideSplashAfterFirstFrame();
            });
            return;
        }

        if (glView.getLensMode() == GLView.LensMode.DUBBUF_REVERSE) {
            updateOverlay("Starting DubBuf preview");
            startPreviewOnlySession(() -> glView.startDubBufReverse(reversePlaybackMs, () -> {
                TraceLog.i("DubBuf first playback selected; hide splash");
                hideSplashAfterFirstFrame();
            }));
            return;
        }

        if (glView.getLensMode() == GLView.LensMode.STUTTER) {
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
        seedAttempt = 0;
        nextCaptureCycle = 2;

        currentItem = null;
        nextItem = null;
        queuedItem = null;
        recordingCandidate = null;
        currentPlaybackDone = false;
        recordingFollowing = false;

        TraceLog.i(
                "S20 startup: skipping short warmup"
        );

        TraceLog.i("timing mode=" + glView.getLensMode() +
                " preset=" + activeTestPreset +
                " requested=" + captureWidth + "x" + captureHeight + "@" + captureFps +
                " recordMs=" + effectiveRecordDurationMs() +
                " playbackMs=" + effectivePlaybackDurationMs());

        updateOverlay(
                "Recording startup seed"
        );

        recordSeedClip();
    }

    void runWarmupCapture() {
        if (!running) {
            return;
        }

        final File warmup =
                makeClipFile(
                        "warmup"
                );

        TraceLog.i(
                "startup warmup record begin durationMs=" +
                        WARMUP_RECORD_MS
        );

        updateOverlay(
                "Warming camera pipeline"
        );

        flashStatus(
                "WARMING UP",
                700
        );

        /*
         * The first high-speed recording is frequently empty. Treat it as
         * deliberate camera/encoder warm-up, make it short, and skip MP4
         * validation and decoder preparation entirely.
         */
        recordClip(
                warmup,
                WARMUP_RECORD_MS,
                false,
                () -> {
                    TraceLog.i(
                            "startup warmup record complete size=" +
                                    warmup.length()
                    );

                    deleteFileQuietly(
                            warmup
                    );

                    recordSeedClip();
                }
        );
    }

    void recordSeedClip() {
        if (!running) {
            return;
        }

        seedAttempt++;

        updateOverlay(
                "Recording seed attempt " +
                        seedAttempt
        );

        final File candidate =
                makeClipFile(
                        "seed_" +
                                seedAttempt
                );
        pendingRecordingCycleId = 1;

        recordClip(
                candidate,
                effectiveRecordDurationMs(),
                false,
                () -> {
            pendingRecordingCycleId = -1;
            if (!running) {
                return;
            }

            if (!isValidSeedMp4(candidate)) {
                TraceLog.i(
                        "Seed attempt " +
                                seedAttempt +
                                " failed validation"
                );

                deleteFileQuietly(
                        candidate
                );

                if (cameraDevice == null) {
                    TraceLog.i(
                            "Seed failed with no camera; reopening"
                    );

                    updateOverlay(
                            "Reopening camera"
                    );

                    running = false;
                    scheduleCameraRecovery("seed failed with no camera");

                    return;
                }

                if (seedAttempt >= 5) {
                    TraceLog.i(
                            "Seed retry limit reached; reopening camera"
                    );

                    updateOverlay(
                            "Restarting camera"
                    );

                    running = false;
                    scheduleCameraRecovery("seed retry limit");

                    return;
                }

                updateOverlay(
                        "Seed failed; retrying"
                );

                mainHandler.postDelayed(
                        this::recordSeedClip,
                        SEED_RETRY_MS
                );

                return;
            }

            PlaybackItem seed =
                    new PlaybackItem(
                            1,
                            0,
                            candidate,
                            playbackFps
                    );

            currentItem = seed;

            prepareItem(seed);
        });
    }

    void prepareItem(
            PlaybackItem item
    ) {
        if (currentItem != null && currentItem.started &&
                item.slot == currentItem.slot) {
            TraceLog.i("RECOVERY refused prepare into playing slot cycle=" +
                    item.cycleId + " slot=" + item.slot +
                    " currentCycle=" + currentItem.cycleId);
            item.failed = true;
            if (item == nextItem) nextItem = null;
            deleteFileQuietly(item.file);
            promoteQueuedItemIfPossible();
            startRecordingForNextItem();
            return;
        }
        loadingCycleId = item.cycleId;
        TraceLog.i(
                "prepare item cycle=" +
                        item.cycleId +
                        " slot=" +
                        item.slot +
                        " file=" +
                        item.file.getName()
        );

        final Runnable prepareWatchdog = () -> {
            if (!running || item.ready || item.failed || item.started) {
                return;
            }
            if (loadingCycleId != item.cycleId) {
                return;
            }

            TraceLog.i(
                    "RECOVERY prepare timeout cycle=" + item.cycleId +
                            " slot=" + item.slot +
                            " timeoutMs=" + REVERSE_PREPARE_TIMEOUT_MS
            );

            item.failed = true;
            loadingCycleId = -1;
            glView.releaseSlot(item.slot);
            handlePrepareFailure(item, "prepare timeout");
        };

        mainHandler.postDelayed(
                prepareWatchdog,
                REVERSE_PREPARE_TIMEOUT_MS
        );

        glView.prepareFile(
                item.slot,
                item.file,
                item.outputFps,
                effectivePlaybackDurationMs(),
                () -> mainHandler.post(() -> {
                    mainHandler.removeCallbacks(prepareWatchdog);

                    if (!running || item.failed) {
                        return;
                    }

                    item.ready = true;
                    if (loadingCycleId == item.cycleId) loadingCycleId = -1;

                    TraceLog.i(
                            "item ready cycle=" +
                                    item.cycleId +
                                    " slot=" +
                                    item.slot
                    );

                    updateOverlay(
                            "Prepared cycle " +
                                    item.cycleId +
                                    " slot " +
                                    item.slot
                    );

                    if (item == currentItem &&
                            !item.started) {

                        if (item.cycleId == 1) {
                            flashStatus(
                                    "READY",
                                    600
                            );
                        }

                        startCurrentItem();
                    } else {
                        maybeHandoff();
                    }
                }),
                () -> mainHandler.post(() -> {
                    mainHandler.removeCallbacks(prepareWatchdog);

                    if (!running || item.failed) {
                        return;
                    }

                    item.done = true;

                    TraceLog.i(
                            "item done cycle=" +
                                    item.cycleId +
                                    " slot=" +
                                    item.slot
                    );

                    if (item == currentItem) {
                        currentPlaybackDone = true;

                        updateOverlay(
                                "Playback cycle " +
                                        item.cycleId +
                                        " done"
                        );

                        maybeHandoff();
                    }
                }),
                reason -> mainHandler.post(() -> {
                    mainHandler.removeCallbacks(prepareWatchdog);
                    if (!item.failed) {
                        handlePrepareFailure(item, reason);
                    }
                })
        );
    }

    void handlePrepareFailure(PlaybackItem item, String reason) {
        if (!running || item == null) return;
        item.failed = true;
        if (loadingCycleId == item.cycleId) loadingCycleId = -1;
        TraceLog.i("RECOVERY prepare failed cycle=" + item.cycleId +
                " slot=" + item.slot + " reason=" + reason);
        updateOverlay("Skipped cycle " + item.cycleId);
        glView.releaseSlot(item.slot);
        deleteFileQuietly(item.file);

        if (item == nextItem) nextItem = null;
        if (item == currentItem) {
            currentItem = null;
            currentPlaybackDone = true;
            TraceLog.i("RECOVERY seed/current prepare failed; recording a fresh seed");
            mainHandler.postDelayed(this::recordSeedClip, SEED_RETRY_MS);
            return;
        }

        // Freshness wins: promote the newest completed recording and keep going.
        promoteQueuedItemIfPossible();
        startRecordingForNextItem();
    }

    void startCurrentItem() {
        if (!running ||
                currentItem == null ||
                !currentItem.ready ||
                currentItem.started) {

            return;
        }

        currentItem.started = true;
        currentPlaybackDone = false;

        cycleCount =
                currentItem.cycleId;

        TraceLog.i(
                "start current cycle=" +
                        currentItem.cycleId +
                        " slot=" +
                        currentItem.slot
        );

        updateOverlay(
                "Playing cycle " +
                        currentItem.cycleId +
                        " slot " +
                        currentItem.slot
        );

        glView.startPrepared(
                currentItem.slot
        );

        if (currentItem.cycleId == 1 &&
                splashPanel != null) {

            mainHandler.postDelayed(
                    () -> {
                        TraceLog.i(
                                "first playback selected; hide splash"
                        );

                        splashPanel.setVisibility(
                                View.GONE
                        );
                    },
                    50
            );
        }

        startRecordingForNextItem();
    }

    void startRecordingForNextItem() {
        if (!running || currentItem == null || recordingFollowing) {
            return;
        }

        /*
         * Do not record more than one file ahead of the prepared clip.
         * queuedItem is the third pipeline stage.
         */
        if (nextItem != null && queuedItem != null) {
            return;
        }

        /*
         * SlowMo must not build a third, queued capture.  Its long playback
         * interval otherwise leaves the displayed clip one full capture cycle
         * behind.  Reverse keeps the extra queue for recovery/freshness.
         */
        if (glView != null &&
                glView.getLensMode() == GLView.LensMode.SLOW &&
                nextItem != null) {
            TraceLog.i("slow pipeline waiting: next clip already prepared/preparing");
            return;
        }

        recordingFollowing = true;

        final int nextCycle;
        final int intendedSlot;

        nextCycle = nextCaptureCycle++;

        if (nextItem == null) {
            intendedSlot = 1 - currentItem.slot;
        } else {
            intendedSlot = currentItem.slot;
        }

        recordingCandidate = makeClipFile("cycle_" + nextCycle);
        pendingRecordingCycleId = nextCycle;
        final File candidate = recordingCandidate;

        TraceLog.i("record pipeline cycle=" + nextCycle +
                " intendedSlot=" + intendedSlot +
                " file=" + candidate.getName());

        final int recordDurationMs = effectiveRecordDurationMs();
        final boolean slowMode = glView != null &&
                glView.getLensMode() == GLView.LensMode.SLOW;
        final int nonRecordingMs = slowMode
                ? Math.max(0, slowPlaybackMs - recordDurationMs - SLOW_DECODE_MARGIN_MS)
                : 0;

        Runnable beginCapture = () -> recordClip(
                candidate,
                recordDurationMs,
                false,
                () -> {
            recordingFollowing = false;
            pendingRecordingCycleId = -1;

            if (!running) return;

            if (!isPlayableMp4(candidate)) {
                TraceLog.i("pipeline recording invalid cycle=" + nextCycle);
                updateOverlay("Invalid recording; retrying");
                deleteFileQuietly(candidate);
                recordingCandidate = null;
                startRecordingForNextItem();
                return;
            }

            PlaybackItem captured = new PlaybackItem(
                    nextCycle, intendedSlot, candidate, playbackFps
            );

            recordingCandidate = null;

            if (nextItem == null) {
                nextItem = captured;
                prepareItem(captured);
            } else {
                if (queuedItem != null) {
                    TraceLog.i("RECOVERY resync drop stale queued cycle=" + queuedItem.cycleId +
                            " for newer cycle=" + captured.cycleId);
                    deleteFileQuietly(queuedItem.file);
                }
                queuedItem = captured;
                TraceLog.i("queued recorded clip cycle=" +
                        captured.cycleId + " intendedSlot=" +
                        captured.slot);
                updateOverlay("Queued cycle " + captured.cycleId);
            }

            startRecordingForNextItem();
        });

        if (nonRecordingMs > 0) {
            mainHandler.postDelayed(() -> cameraHandler.post(beginCapture), nonRecordingMs);
        } else cameraHandler.post(beginCapture);
    }

    void promoteQueuedItemIfPossible() {
        if (!running || queuedItem == null || nextItem != null ||
                currentItem == null) {
            return;
        }

        PlaybackItem queued = queuedItem;
        queuedItem = null;

        PlaybackItem promoted = new PlaybackItem(
                queued.cycleId,
                1 - currentItem.slot,
                queued.file,
                queued.outputFps
        );

        nextItem = promoted;

        TraceLog.i("promote queued clip cycle=" +
                promoted.cycleId + " slot=" + promoted.slot);

        prepareItem(promoted);
    }

    synchronized void maybeHandoff() {
        if (!running ||
                currentItem == null ||
                nextItem == null) {

            return;
        }

        if (!currentPlaybackDone ||
                !nextItem.ready) {

            return;
        }

        PlaybackItem oldItem =
                currentItem;

        PlaybackItem newItem =
                nextItem;

        nextItem = null;
        currentItem = newItem;

        currentPlaybackDone = false;

        TraceLog.i(
                "handoff oldCycle=" +
                        oldItem.cycleId +
                        " oldSlot=" +
                        oldItem.slot +
                        " newCycle=" +
                        newItem.cycleId +
                        " newSlot=" +
                        newItem.slot
        );
        TraceLog.i(
                "handoff ownership old player completed; " +
                        "new player is sole owner of slot " +
                        newItem.slot
        );

        updateOverlay(
                "Handoff " +
                        oldItem.slot +
                        " → " +
                        newItem.slot
        );

        /*
         * New slot already contains its first decoded frame.
         * startCurrentItem() performs one GL-thread texture switch and
         * releases the prepared player's playback latch.
         */
        startCurrentItem();

        /*
         * The old player has completed and released its codec. Keep the
         * old texture untouched until after the GL switch request has
         * been queued, then free the old file and slot bookkeeping.
         */
        mainHandler.postDelayed(
                () -> {
                    glView.releaseSlot(oldItem.slot);
                    deleteFileQuietly(oldItem.file);

                    promoteQueuedItemIfPossible();
                    startRecordingForNextItem();
                },
                100
        );
    }

    File makeClipFile(
            String label
    ) {
        File outDir =
                Environment
                        .getExternalStoragePublicDirectory(
                                Environment
                                        .DIRECTORY_MOVIES
                        );

        if (!outDir.exists()) {
            outDir.mkdirs();
        }

        return new File(
                outDir,
                "smm_" +
                        label +
                        "_" +
                        System.currentTimeMillis() +
                        ".mp4"
        );
    }



    void startTape3Session(Runnable onReady) {
        if (cameraDevice == null || previewSurface == null) {
            if (onReady != null) onReady.run();
            return;
        }

        try {
            final int tape3CameraFps = captureFps;
            final boolean highSpeed = captureHighSpeed;
            final Tape3 newTape3 =
                    new Tape3(captureWidth, captureHeight, tape3CameraFps, captureBitrate, glView.getDecoderSurface(0));
            configureTapeMode(newTape3);
            newTape3.setReverseDurationMs(reversePlaybackMs);

            newTape3.prepare();
            tape3 = newTape3;

            tape3AuditFirstNs = 0L;
            tape3AuditLastLogNs = 0L;
            tape3AuditFrames = 0L;
            tape3AuditLastFrames = 0L;

            if (session != null) {
                try { session.close(); } catch (Exception ignored) {}
                session = null;
            }

            ArrayList<Surface> surfaces = new ArrayList<>();
            surfaces.add(previewSurface);
            surfaces.add(newTape3.getInputSurface());

            CameraCaptureSession.StateCallback callback = new CameraCaptureSession.StateCallback() {
                        @Override public void onConfigured(CameraCaptureSession s) {
                            try {
                                if (tape3 != newTape3 ||
                                        !GLView.isTapeMode(glView.getLensMode())) {
                                    try { s.close(); } catch (Exception ignored) {}
                                    newTape3.stopAndRelease();
                                    return;
                                }

                                session = s;
                                recordingSessionConfigured = false;

                                CaptureRequest.Builder b =
                                        cameraDevice.createCaptureRequest(
                                                CameraDevice.TEMPLATE_RECORD);
                                b.addTarget(previewSurface);
                                b.addTarget(newTape3.getInputSurface());
                                applyCaptureControls(b, highSpeed);
                                applyCameraZoom(b, cameraZoom);
                                newTape3.start();
                                if (highSpeed) {
                                    CameraConstrainedHighSpeedCaptureSession hs = (CameraConstrainedHighSpeedCaptureSession) s;
                                    hs.setRepeatingBurst(hs.createHighSpeedRequestList(b.build()), frameAuditCaptureCallback, cameraHandler);
                                } else s.setRepeatingRequest(b.build(), frameAuditCaptureCallback, cameraHandler);
                                TraceLog.i("TAPE3 requested fixed FPS=" + captureFps + " highSpeed=" + highSpeed);
                                if (onReady != null) mainHandler.post(onReady);
                            } catch (Exception e) {
                                updateOverlay("Tape3 settings failed: " + e.getMessage());
                                if (tape3 == newTape3) tape3 = null;
                                newTape3.stopAndRelease();
                                TraceLog.e("TAPE3 session configure error", e);
                            }
                        }

                        @Override public void onConfigureFailed(CameraCaptureSession s) {
                            TraceLog.i("TAPE3 camera session failed");
                            updateOverlay(
                                    "Selected Tape3 camera stream failed. Try 60 fps or 720p.");
                            if (tape3 == newTape3) tape3 = null;
                            newTape3.stopAndRelease();
                        }
                    };
            if (highSpeed) cameraDevice.createConstrainedHighSpeedCaptureSession(surfaces, callback, cameraHandler);
            else cameraDevice.createCaptureSession(surfaces, callback, cameraHandler);
        } catch (Exception e) {
            TraceLog.e("startTape3Session failed", e);
            updateOverlay("Tape3 start failed: " + e);
            if (tape3 != null) {
                tape3.stopAndRelease();
                tape3 = null;
            }
        }
    }

    void closeTape3() {
        Tape3 oldTape3 = tape3;
        tape3 = null;
        if (oldTape3 != null) {
            try { oldTape3.stopAndRelease(); } catch (Exception ignored) {}
        }
    }

    void startRamPreviewSession(Runnable onReady) {
        TraceLog.i("DBG RAM 01 enter startRamPreviewSession");
        if (cameraDevice == null || previewSurface == null) {
            if (onReady != null) onReady.run();
            return;
        }

        TraceLog.i("DBG RAM 03 before closeRamCapture");
        closeRamCapture();
        TraceLog.i("DBG RAM 04 after closeRamCapture");

        try {
            TraceLog.i("DBG RAM 05 before new RamFrameBuffer");
            ramBuffer = new RamFrameBuffer();
            TraceLog.i("DBG RAM 06 after new RamFrameBuffer");
            TraceLog.i("DBG RAM 07 before ImageReader.newInstance");
            ramReader = ImageReader.newInstance(1920, 1080, ImageFormat.YUV_420_888, 4);
            TraceLog.i("DBG RAM 08 after ImageReader.newInstance");

            ramReader.setOnImageAvailableListener(reader -> {
                Image image = null;
                long beginNs = System.nanoTime();
                try {
                    image = reader.acquireNextImage();
                    if (image != null && ramBuffer != null) ramBuffer.addImage(image);
                } catch (Throwable t) {
                    TraceLog.e("RAM frame copy failed",
                            t instanceof Exception ? (Exception)t : new RuntimeException(t));
                } finally {
                    if (image != null) try { image.close(); } catch (Exception ignored) {}
                }
                long copyNs = System.nanoTime() - beginNs;
                if (copyNs >= 10_000_000L)
                    TraceLog.i("RAM copy slow ms=" + (copyNs / 1_000_000.0));
            }, cameraHandler);
            TraceLog.i("DBG RAM 09 listener installed");

            if (session != null) {
                TraceLog.i("DBG RAM 10 closing old session");
                try { session.close(); } catch (Exception ignored) {}
                session = null;
            }

            TraceLog.i("DBG RAM 11 building surface list");
            ArrayList<Surface> surfaces = new ArrayList<>();
            surfaces.add(previewSurface);
            surfaces.add(ramReader.getSurface());

            TraceLog.i("DBG RAM 12 before createCaptureSession");
            cameraDevice.createCaptureSession(
                    surfaces,
                    new CameraCaptureSession.StateCallback() {
                        @Override public void onConfigured(CameraCaptureSession s) {
                            TraceLog.i("DBG RAM 13 onConfigured entered");
                            try {
                                session = s;
                                recordingSessionConfigured = false;

                                TraceLog.i("DBG RAM 14 before createCaptureRequest");
                                CaptureRequest.Builder b =
                                        cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                                TraceLog.i("DBG RAM 15 request builder created");
                                b.addTarget(previewSurface);
                                b.addTarget(ramReader.getSurface());
                                b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                                        new Range<Integer>(60, 60));
                                b.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
                                b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
                                applyCameraZoom(b, cameraZoom);

                                TraceLog.i("DBG RAM 16 before setRepeatingRequest");
                                s.setRepeatingRequest(
                                        b.build(), frameAuditCaptureCallback, cameraHandler);
                                TraceLog.i("DBG RAM 17 repeating request active");
                                TraceLog.i("RAM preview session active 1920x1080@60 targets=preview+YUV");
                                if (onReady != null) mainHandler.post(onReady);
                            } catch (Exception e) {
                                TraceLog.e("RAM session configure error", e);
                            }
                        }

                        @Override public void onConfigureFailed(CameraCaptureSession s) {
                            TraceLog.i("RAM preview session failed");
                            updateOverlay("RAM 1080p60 stream combination failed");
                        }
                    },
                    cameraHandler
            );
            TraceLog.i("DBG RAM 18 createCaptureSession returned");
        } catch (Exception e) {
            TraceLog.e("startRamPreviewSession failed", e);
            updateOverlay("RAM Scrub start failed: " + e);
        }
    }

    void closeRamCapture() {
        if (ramReader != null) {
            try { ramReader.close(); } catch (Exception ignored) {}
            ramReader = null;
        }
        if (ramBuffer != null) {
            ramBuffer.clear();
            ramBuffer.release();
            ramBuffer = null;
        }
        if (glView != null) glView.setRamBuffer(null);
    }

    void startPreviewOnlySession(Runnable onReady) {
        if (cameraDevice == null || previewSurface == null) return;
        try {
            if (session != null) { session.close(); session = null; }
            final boolean highSpeed = captureHighSpeed;
            CameraCaptureSession.StateCallback callback = new CameraCaptureSession.StateCallback() {
                @Override public void onConfigured(CameraCaptureSession s) {
                    try {
                        if (cameraDevice == null || appPaused) { s.close(); return; }
                        session = s;
                        recordingSessionConfigured = false;
                        CaptureRequest.Builder b = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
                        b.addTarget(previewSurface);
                        applyCaptureControls(b, highSpeed);
                        applyCameraZoom(b, cameraZoom);
                        if (highSpeed) {
                            CameraConstrainedHighSpeedCaptureSession hs = (CameraConstrainedHighSpeedCaptureSession) s;
                            hs.setRepeatingBurst(hs.createHighSpeedRequestList(b.build()), frameAuditCaptureCallback, cameraHandler);
                        } else s.setRepeatingRequest(b.build(), frameAuditCaptureCallback, cameraHandler);
                        if (onReady != null) mainHandler.post(onReady);
                    } catch (Exception e) { updateOverlay("Camera settings failed: " + e.getMessage()); TraceLog.e("Preview settings", e); }
                }
                @Override public void onConfigureFailed(CameraCaptureSession s) {
                    updateOverlay("Selected camera stream is unsupported. Try 60 fps or 720p.");
                }
            };
            List<Surface> surfaces = Collections.singletonList(previewSurface);
            if (highSpeed) cameraDevice.createConstrainedHighSpeedCaptureSession(surfaces, callback, cameraHandler);
            else cameraDevice.createCaptureSession(surfaces, callback, cameraHandler);
        } catch (Exception e) { updateOverlay("Preview failed: " + e.getMessage()); TraceLog.e("Preview session", e); }
    }

    void recordClip(
            File outFile,
            Runnable onDone
    ) {
        recordClip(
                outFile,
                captureDurationMs,
                true,
                onDone
        );
    }

    void recordClip(
            File outFile,
            int durationMs,
            Runnable onDone
    ) {
        recordClip(
                outFile,
                durationMs,
                false,
                onDone
        );
    }

    void recordClip(
            File outFile,
            int durationMs,
            boolean showCue,
            Runnable onDone
    ) {
        TraceLog.i(
                "recordClip request file=" +
                        outFile.getName() +
                        " durationMs=" +
                        durationMs +
                        " cueMs=" +
                        (showCue ? recordCueMs : 0) +
                        " mode=" +
                        captureWidth +
                        "x" +
                        captureHeight +
                        "@" +
                        captureFps +
                        " bitrate=" +
                        captureBitrate
        );

        if (cameraHandler == null) {
            updateOverlay("Camera thread not ready");
            completeRecordingCallback(onDone);
            return;
        }

        cameraHandler.post(
                () -> recordClipOnCameraThread(
                        outFile,
                        durationMs,
                        showCue,
                        onDone
                )
        );
    }

    void recordClipOnCameraThread(
            File outFile,
            int durationMs,
            boolean showCue,
            Runnable onDone
    ) {
        try {
            if (cameraDevice == null) {
                updateOverlay("Camera not ready");
                completeRecordingCallback(onDone);
                return;
            }

            if (busyRecording || recordingSessionCreating) {
                TraceLog.i(
                        "record deferred busy=" + busyRecording +
                                " sessionCreating=" + recordingSessionCreating
                );

                cameraHandler.postDelayed(
                        () -> recordClipOnCameraThread(
                                outFile, durationMs, showCue, onDone
                        ),
                        50
                );
                return;
            }

            busyRecording = true;

            ensurePersistentEncoderSurface();

            recorder = new MediaSurfaceRecorder(
                    captureWidth,
                    captureHeight,
                    captureFps,
                    captureBitrate,
                    outFile,
                    encoderSurface
            );

            TraceLog.i("recorder prepare begin");
            recorder.prepare();
            TraceLog.i("recorder prepare done");

            if (diagnostics != null) {
                diagnostics.recorderPrepared();
            }

            if (recordingSessionConfigured && session != null) {
                TraceLog.i("SESSION reuse #" + recordingSessionDiagnosticId);
                beginActualRecordingOnCameraThread(
                        outFile, durationMs, showCue, onDone
                );
            } else {
                createPersistentRecordingSession(
                        outFile, durationMs, showCue, onDone
                );
            }

        } catch (Exception e) {
            failRecordingStart("recordClip error", e, onDone);
        }
    }

    void ensurePersistentEncoderSurface() {
        closeTape3();

        if (encoderSurface != null) {
            return;
        }

        encoderSurface = MediaCodec.createPersistentInputSurface();

        TraceLog.i("persistent encoder Surface created");
        if (diagnostics != null) {
            diagnostics.encoderSurfaceCreated();
        }
    }

    void createPersistentRecordingSession(
            File outFile,
            int durationMs,
            boolean showCue,
            Runnable onDone
    ) throws Exception {
        recordingSessionCreating = true;

        ArrayList<Surface> surfaces = new ArrayList<>();
        surfaces.add(previewSurface);
        surfaces.add(encoderSurface);

        final boolean highSpeed = captureHighSpeed;

        updateOverlay(highSpeed
                ? "Creating persistent high-speed session"
                : "Creating persistent recording session");

        TraceLog.i(highSpeed
                ? "createConstrainedHighSpeedCaptureSession begin"
                : "createCaptureSession begin");

        recordingSessionDiagnosticId = diagnostics == null
                ? 0
                : diagnostics.sessionCreating(highSpeed, "persistent-recording");

        CameraCaptureSession.StateCallback stateCallback =
                new CameraCaptureSession.StateCallback() {

            @Override
            public void onConfigured(CameraCaptureSession raw) {
                recordingSessionCreating = false;

                if (cameraDevice == null) {
                    raw.close();
                    failRecordingStart(
                            "session configured after camera close",
                            null,
                            onDone
                    );
                    return;
                }

                try {
                    session = raw;
                    recordingSessionConfigured = true;

                    if (diagnostics != null) {
                        diagnostics.sessionConfigured(
                                recordingSessionDiagnosticId
                        );
                    }

                    CaptureRequest.Builder builder =
                            cameraDevice.createCaptureRequest(
                                    CameraDevice.TEMPLATE_RECORD
                            );

                    builder.addTarget(previewSurface);
                    builder.addTarget(encoderSurface);
                    applyCaptureControls(builder, highSpeed);
                    applyCameraZoom(builder, cameraZoom);

                    CaptureRequest request = builder.build();

                    /*
                     * The repeating request remains active for the lifetime
                     * of this session. Each clip independently starts a fresh
                     * encoder attached to the same persistent Surface.
                     */
                    if (highSpeed) {
                        CameraConstrainedHighSpeedCaptureSession hs =
                                (CameraConstrainedHighSpeedCaptureSession) raw;
                        List<CaptureRequest> burst =
                                hs.createHighSpeedRequestList(request);
                        hs.setRepeatingBurst(burst, frameAuditCaptureCallback, cameraHandler);
                    } else {
                        raw.setRepeatingRequest(
                                request, frameAuditCaptureCallback, cameraHandler
                        );
                    }

                    TraceLog.i(
                            "persistent session active #" +
                                    recordingSessionDiagnosticId
                    );

                    beginActualRecordingOnCameraThread(
                            outFile, durationMs, showCue, onDone
                    );

                } catch (Exception e) {
                    failRecordingStart("onConfigured error", e, onDone);
                }
            }

            @Override
            public void onClosed(CameraCaptureSession closedSession) {
                super.onClosed(closedSession);
                TraceLog.i("persistent camera session onClosed");

                if (diagnostics != null) {
                    diagnostics.sessionClosed(
                            recordingSessionDiagnosticId
                    );
                }

                if (session == closedSession) {
                    session = null;
                }
                recordingSessionConfigured = false;
                recordingSessionCreating = false;
            }

            @Override
            public void onConfigureFailed(
                    CameraCaptureSession failedSession
            ) {
                recordingSessionCreating = false;
                recordingSessionConfigured = false;

                if (diagnostics != null) {
                    diagnostics.sessionConfigureFailed(
                            recordingSessionDiagnosticId
                    );
                }

                failRecordingStart(
                        highSpeed
                                ? "Persistent high-speed session failed"
                                : "Persistent recording session failed",
                        null,
                        onDone
                );
            }
        };

        if (highSpeed) {
            cameraDevice.createConstrainedHighSpeedCaptureSession(
                    surfaces, stateCallback, cameraHandler
            );
        } else {
            cameraDevice.createCaptureSession(
                    surfaces, stateCallback, cameraHandler
            );
        }

        TraceLog.i(highSpeed
                ? "createConstrainedHighSpeedCaptureSession returned"
                : "createCaptureSession returned");
    }

    void beginActualRecordingOnCameraThread(
            File outFile,
            int durationMs,
            boolean showCue,
            Runnable onDone
    ) {
        Runnable begin = () -> cameraHandler.post(() -> {
            try {
                recorder.start();
                recordingCycleId = pendingRecordingCycleId;
                hideRecordCue();
                refreshOverlay();

                if (diagnostics != null) {
                    diagnostics.recorderStarted();
                }

                beginRecordingUiAndTimer(
                        outFile, durationMs, onDone
                );
            } catch (Exception e) {
                failRecordingStart(
                        "beginActualRecording error", e, onDone
                );
            }
        });

        updateOverlay(showCue ? "Ready to record" : "Starting recording");

        if (showCue) {
            startRecordCue(begin);
        } else {
            begin.run();
        }
    }

    void beginRecordingUiAndTimer(
            File outFile,
            int durationMs,
            Runnable onDone
    ) {
        FrameAudit.beginCapture(outFile.getName());

        TraceLog.i(
                "recording active file=" + outFile.getName() +
                        " durationMs=" + durationMs +
                        " persistentSession=true"
        );

        TraceLog.i("record border ON");
        setRecordingIndicator(true);
        startRecordProgress(durationMs);
        updateOverlay("Recording " + durationMs + " ms");

        cameraHandler.postDelayed(
                () -> stopRecordingOnCameraThread(outFile, onDone),
                durationMs
        );
    }

    void failRecordingStart(
            String message,
            Exception error,
            Runnable onDone
    ) {
        busyRecording = false;
        recordingSessionCreating = false;
        hideRecordCue();
        hideRecordProgress();
        setRecordingIndicator(false);

        if (error != null) {
            TraceLog.e(message, error);
            updateOverlay(message + ": " + error);
        } else {
            TraceLog.i(message);
            updateOverlay(message);
        }

        if (recorder != null) {
            try {
                recorder.stopAndRelease();
            } catch (Exception ignored) {
            }
            recorder = null;
        }

        completeRecordingCallback(onDone);
    }

    void stopRecordingOnCameraThread(
            File outFile,
            Runnable onDone
    ) {
        hideRecordProgress();

        TraceLog.i("record border OFF");
        setRecordingIndicator(false);
        recordingCycleId = -1;
        refreshOverlay();
        if (glView != null && glView.getLensMode() == GLView.LensMode.SLOW) {
            TraceLog.i("SLOW LATENCY record stop t=" +
                    SystemClock.elapsedRealtime());
            // Stay visibly shrinking for the full nominal non-recording span;
            // recorder.start() remains the authoritative hide event.
            int visualWaitMs = Math.max(1,
                    slowPlaybackMs - effectiveRecordDurationMs());
            startRecordCue(visualWaitMs, null);
        }

        try {
            updateOverlay("Stopping recording");

            FrameAudit.endCapture(outFile == null ? "null" : outFile.getName());
            measuredCaptureFps = FrameAudit.lastCaptureFps();
            measuredCaptureFrames = FrameAudit.lastCaptureFrames();
            measuredCaptureSpanUs = FrameAudit.lastCaptureSpanUs();

            if (recorder != null) {
                recorder.stopAndRelease();
                if (diagnostics != null) {
                    diagnostics.recorderStopped();
                }
                recorder = null;
            }

            /*
             * Deliberately retain session and encoderSurface.  The next clip
             * creates only a codec and muxer, then reuses this camera session.
             */
            busyRecording = false;

            long size = outFile == null ? 0 : outFile.length();
            if (outFile != null && size > 0) {
                FrameAudit.logMp4(outFile);
            }
            updateOverlay(
                    size > 0
                            ? "Saved " + size + " bytes; session retained"
                            : "Recording failed; session retained"
            );

            completeRecordingCallback(onDone);

        } catch (Exception e) {
            busyRecording = false;
            TraceLog.e("stopRecording error", e);
            completeRecordingCallback(onDone);
        }
    }

    void releasePersistentRecordingPipeline() {
        closeRamCapture();
        recordingSessionConfigured = false;
        recordingSessionCreating = false;
        busyRecording = false;

        if (recorder != null) {
            try {
                recorder.stopAndRelease();
            } catch (Exception ignored) {
            }
            recorder = null;
        }

        if (session != null) {
            try {
                session.stopRepeating();
            } catch (Exception ignored) {
            }
            try {
                session.close();
            } catch (Exception ignored) {
            }
            session = null;
        }

        closeTape3();

        if (encoderSurface != null) {
            try {
                encoderSurface.release();
            } catch (Exception ignored) {
            }
            encoderSurface = null;
        }

        TraceLog.i("persistent recording pipeline released");
    }

    void completeRecordingCallback(
            Runnable onDone
    ) {
        if (onDone != null) {
            mainHandler.post(onDone);
        }
    }

    boolean isValidSeedMp4(File file) {
        if (!isPlayableMp4(file)) return false;
        if (glView != null && glView.getLensMode() == GLView.LensMode.SLOW) {
            MediaExtractor slowExtractor = new MediaExtractor();
            try {
                slowExtractor.setDataSource(file.getAbsolutePath());
                int track = -1;
                for (int i = 0; i < slowExtractor.getTrackCount(); i++) {
                    MediaFormat f = slowExtractor.getTrackFormat(i);
                    String mime = f.getString(MediaFormat.KEY_MIME);
                    if (mime != null && mime.startsWith("video/")) { track = i; break; }
                }
                if (track < 0) return false;
                slowExtractor.selectTrack(track);
                int frames = 0; long firstUs = -1L; long lastUs = -1L;
                while (true) {
                    long pts = slowExtractor.getSampleTime();
                    if (pts < 0) break;
                    if (firstUs < 0) firstUs = pts;
                    lastUs = pts; frames++;
                    if (!slowExtractor.advance()) break;
                }
                long spanUs = firstUs >= 0 && lastUs >= firstUs ? lastUs - firstUs : -1L;
                int expectedFrames = Math.max(1, effectiveRecordDurationMs() * captureFps / 1000);
                int minFrames = Math.max(1, expectedFrames * 85 / 100);
                long minSpanUs = Math.max(1L, effectiveRecordDurationMs() * 1000L * 85L / 100L);
                boolean valid = frames >= minFrames && spanUs >= minSpanUs;
                TraceLog.i("slow seed validation file=" + file.getName() +
                        " frames=" + frames + " minFrames=" + minFrames +
                        " spanUs=" + spanUs + " minSpanUs=" + minSpanUs +
                        " valid=" + valid);
                return valid;
            } catch (Exception e) {
                TraceLog.e("slow seed validation failed", e);
                return false;
            } finally {
                try { slowExtractor.release(); } catch (Exception ignored) {}
            }
        }
        if (glView == null || glView.getLensMode() != GLView.LensMode.REVERSE) {
            return true;
        }

        MediaExtractor extractor = new MediaExtractor();
        try {
            extractor.setDataSource(file.getAbsolutePath());
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat format = extractor.getTrackFormat(i);
                String mime = format.getString(MediaFormat.KEY_MIME);
                if (mime == null || !mime.startsWith("video/")) continue;
                extractor.selectTrack(i);
                int frames = 0;
                long firstUs = -1L;
                long lastUs = -1L;
                while (true) {
                    long pts = extractor.getSampleTime();
                    if (pts < 0) break;
                    if (firstUs < 0) firstUs = pts;
                    lastUs = pts;
                    frames++;
                    if (!extractor.advance()) break;
                }
                long spanUs = firstUs >= 0 && lastUs >= firstUs ? lastUs - firstUs : -1L;
                int expectedFrames = Math.max(1,
                        reversePlaybackMs * Math.max(1, playbackFps) / 1000);
                int minFrames = Math.max(1,
                        (expectedFrames * 90) / 100);
                long minSpanUs = Math.max(1L,
                        (effectiveRecordDurationMs() * 1000L * 85L) / 100L);
                boolean valid = frames >= minFrames && spanUs >= minSpanUs;
                TraceLog.i("reverse seed validation file=" + file.getName() +
                        " frames=" + frames + "/" + expectedFrames +
                        " minFrames=" + minFrames +
                        " spanUs=" + spanUs +
                        " minSpanUs=" + minSpanUs +
                        " valid=" + valid);
                return valid;
            }
            return false;
        } catch (Exception e) {
            TraceLog.e("reverse seed validation failed", e);
            return false;
        } finally {
            try { extractor.release(); } catch (Exception ignored) {}
        }
    }

    boolean isPlayableMp4(
            File file
    ) {
        if (file == null ||
                !file.exists() ||
                file.length() <= 0) {

            return false;
        }

        MediaExtractor extractor =
                new MediaExtractor();

        try {
            extractor.setDataSource(
                    file.getAbsolutePath()
            );

            for (int i = 0;
                 i < extractor.getTrackCount();
                 i++) {

                MediaFormat format =
                        extractor.getTrackFormat(i);

                String mime =
                        format.getString(
                                MediaFormat.KEY_MIME
                        );

                if (mime != null &&
                        mime.startsWith("video/")) {

                    return true;
                }
            }

            return false;

        } catch (Exception e) {
            TraceLog.e(
                    "isPlayableMp4 failed: " +
                            file.getName(),
                    e
            );

            return false;

        } finally {
            try {
                extractor.release();
            } catch (Exception ignored) {
            }
        }
    }

    void deleteFileQuietly(
            File file
    ) {
        if (file == null) {
            return;
        }

        try {
            if (file.exists()) {
                boolean deleted =
                        file.delete();

                TraceLog.i(
                        "delete " +
                                file.getName() +
                                "=" +
                                deleted
                );
            }
        } catch (Exception e) {
            TraceLog.e(
                    "delete file failed",
                    e
            );
        }
    }

    void checkPermissionAndStart() {
        if (checkSelfPermission(
                Manifest.permission.CAMERA
        ) != PackageManager
                .PERMISSION_GRANTED) {

            requestPermissions(
                    new String[]{
                            Manifest.permission.CAMERA
                    },
                    1
            );
        } else {
            startCamera();
        }
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode,
            String[] permissions,
            int[] grantResults
    ) {
        super.onRequestPermissionsResult(
                requestCode,
                permissions,
                grantResults
        );

        if (grantResults.length > 0 &&
                grantResults[0] ==
                        PackageManager
                                .PERMISSION_GRANTED) {

            startCamera();

        } else {
            updateOverlay(
                    "Camera permission denied"
            );
        }
    }

    void startCamera() {
        if (cameraOpenState == CameraOpenState.OPENING ||
                cameraOpenState == CameraOpenState.OPEN) {
            TraceLog.i("startCamera ignored state=" + cameraOpenState);
            return;
        }

        mainHandler.removeCallbacks(reopenCameraRunnable);
        cameraOpenState = CameraOpenState.OPENING;
        final int openGeneration = ++cameraOpenGeneration;
        releasePersistentRecordingPipeline();

        if (cameraDevice != null) {
            try {
                cameraDevice.close();
            } catch (Exception ignored) {
            }

            cameraDevice = null;
        }

        if (cameraThread != null) {
            try {
                cameraThread.quitSafely();
            } catch (Exception ignored) {
            }

            cameraThread = null;
            cameraHandler = null;
        }

        cameraThread =
                new HandlerThread(
                        "CameraThread"
                );

        cameraThread.start();

        cameraHandler =
                new Handler(
                        cameraThread
                                .getLooper()
                );

        cameraManager =
                (CameraManager)
                        getSystemService(
                                Context
                                        .CAMERA_SERVICE
                        );

        try {
            logHighSpeedCapabilities();

            if (cameraTexture != null) cameraTexture.setDefaultBufferSize(captureWidth, captureHeight);
            String cameraId = findCaptureCamera();

            updateOverlay(
                    "Opening camera " +
                            cameraId
            );

            if (diagnostics != null) {
                diagnostics.cameraOpenRequested(cameraId);
            }

            final String openedCameraId = cameraId;

            cameraManager.openCamera(
                    cameraId,
                    new CameraDevice
                            .StateCallback() {

                        @Override
                        public void onOpened(
                                CameraDevice device
                        ) {
                            if (openGeneration != cameraOpenGeneration ||
                                    cameraOpenState != CameraOpenState.OPENING) {
                                TraceLog.i("stale camera open ignored generation=" + openGeneration);
                                device.close();
                                return;
                            }
                            cameraDevice = device;
                            cameraOpenState = CameraOpenState.OPEN;
                            mainHandler.post(() -> {
                                updateControlButtons();
                                if (captureHighSpeed && manualExposure)
                                    flashStatus("High-speed capture uses Auto exposure; 60 FPS supports manual settings when available.", 3500);
                            });

                            if (diagnostics != null) {
                                diagnostics.cameraOpened(openedCameraId);
                            }

                            updateOverlay(
                                    "Camera opened; starting"
                            );

                            mainHandler.post(
                                    () -> startPhase2()
                            );
                        }

                        @Override
                        public void onDisconnected(
                                CameraDevice device
                        ) {
                            if (diagnostics != null) diagnostics.cameraDisconnected();
                            handleCameraFailure(device, "Camera disconnected");
                        }

                        @Override
                        public void onError(
                                CameraDevice device,
                                int error
                        ) {
                            if (diagnostics != null) diagnostics.cameraError(error);
                            handleCameraFailure(device, "Camera error " + error);
                        }

                        @Override
                        public void onClosed(CameraDevice device) {
                            if (diagnostics != null) {
                                diagnostics.cameraClosed();
                            }
                        }
                    },
                    cameraHandler
            );

        } catch (Exception e) {
            TraceLog.e(
                    "startCamera error",
                    e
            );

            updateOverlay(
                    "startCamera error: " + e
            );
            scheduleCameraRecovery("startCamera exception");
        }
    }

    void handleCameraFailure(CameraDevice device, String reason) {
        if (appPaused) {
            TraceLog.i("camera failure while paused; no recovery loop: " + reason);
            try { device.close(); } catch (Exception ignored) {}
            if (cameraDevice == device) cameraDevice = null;
            cameraOpenState = CameraOpenState.CLOSED;
            return;
        }
        if (cameraOpenState == CameraOpenState.RECOVERING) {
            TraceLog.i("duplicate camera failure ignored: " + reason);
            try { device.close(); } catch (Exception ignored) {}
            return;
        }
        TraceLog.i("camera recovery requested: " + reason);
        cameraOpenState = CameraOpenState.RECOVERING;
        running = false;
        releasePersistentRecordingPipeline();
        try { device.close(); } catch (Exception ignored) {}
        if (cameraDevice == device) cameraDevice = null;
        updateOverlay(reason + "; reopening");
        mainHandler.removeCallbacks(reopenCameraRunnable);
        mainHandler.postDelayed(reopenCameraRunnable, CAMERA_REOPEN_MS);
    }

    void scheduleCameraRecovery(String reason) {
        if (appPaused) {
            TraceLog.i("camera recovery suppressed while paused: " + reason);
            cameraOpenState = CameraOpenState.CLOSED;
            return;
        }
        if (cameraOpenState == CameraOpenState.RECOVERING) {
            TraceLog.i("camera recovery already pending: " + reason);
            return;
        }
        TraceLog.i("schedule camera recovery: " + reason);
        cameraOpenState = CameraOpenState.RECOVERING;
        running = false;
        releasePersistentRecordingPipeline();
        CameraDevice device = cameraDevice;
        cameraDevice = null;
        if (device != null) {
            try { device.close(); } catch (Exception ignored) {}
        }
        mainHandler.removeCallbacks(reopenCameraRunnable);
        mainHandler.postDelayed(reopenCameraRunnable, CAMERA_REOPEN_MS);
    }

    void logHighSpeedCapabilities() {
        try {
            for (String id :
                    cameraManager.getCameraIdList()) {

                CameraCharacteristics characteristics =
                        cameraManager.getCameraCharacteristics(id);

                StreamConfigurationMap map =
                        characteristics.get(
                                CameraCharacteristics
                                        .SCALER_STREAM_CONFIGURATION_MAP
                        );

                if (map == null) {
                    continue;
                }

                Size[] sizes =
                        map.getHighSpeedVideoSizes();

                if (sizes == null) {
                    continue;
                }

                for (Size size : sizes) {
                    Range<Integer>[] ranges =
                            map.getHighSpeedVideoFpsRangesFor(
                                    size
                            );

                    if (ranges == null) {
                        continue;
                    }

                    for (Range<Integer> range : ranges) {
                        TraceLog.i(
                                "HS capability camera=" +
                                        id +
                                        " size=" +
                                        size.getWidth() +
                                        "x" +
                                        size.getHeight() +
                                        " fps=" +
                                        range.getLower() +
                                        "-" +
                                        range.getUpper()
                        );
                    }
                }
            }
        } catch (Exception e) {
            TraceLog.e(
                    "capability logging failed",
                    e
            );
        }
    }

    boolean hasManualSensor(CameraCharacteristics c) {
        int[] capabilities = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
        if (capabilities != null) for (int value : capabilities)
            if (value == CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR) return true;
        return false;
    }

    boolean supportsNormalFps(CameraCharacteristics c, StreamConfigurationMap map,
                              Size size, int fps) {
        if (!containsSize(map.getOutputSizes(SurfaceTexture.class), size) ||
                !containsSize(map.getOutputSizes(MediaCodec.class), size)) return false;
        long previewNs = map.getOutputMinFrameDuration(SurfaceTexture.class, size);
        long encoderNs = map.getOutputMinFrameDuration(MediaCodec.class, size);
        long frameNs = CaptureTiming.frameNs(fps);
        if (previewNs > frameNs + 1000L || encoderNs > frameNs + 1000L) return false;
        Range<Integer>[] ranges = c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
        if (ranges != null) for (Range<Integer> range : ranges)
            if (range.getLower() == fps && range.getUpper() == fps) return true;
        // AE-off capture can use a frame duration absent from the AE ranges, only
        // when both output streams explicitly advertise the required cadence.
        return manualExposure && hasManualSensor(c) && previewNs > 0 && encoderNs > 0;
    }

    String selectCaptureCamera(int width, int height, int fps, boolean apply) throws Exception {
        Size size = new Size(width, height);
        for (String id : cameraManager.getCameraIdList()) {
            CameraCharacteristics c = cameraManager.getCameraCharacteristics(id);
            StreamConfigurationMap map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (map == null) continue;
            boolean normal = supportsNormalFps(c, map, size, fps);
            boolean highSpeed = false;
            if (!normal && containsSize(map.getHighSpeedVideoSizes(), size)) {
                Range<Integer>[] ranges = map.getHighSpeedVideoFpsRangesFor(size);
                if (ranges != null) for (Range<Integer> range : ranges)
                    if (range.getLower() == fps && range.getUpper() == fps) highSpeed = true;
            }
            if (!normal && !highSpeed) continue;
            if (apply) {
                captureHighSpeed = highSpeed;
                manualSensorAvailable = hasManualSensor(c);
            }
            return id;
        }
        throw new IllegalArgumentException("No fixed " + fps + " fps at " + width + "x" + height + ". Try 720p in Camera settings.");
    }

    String findCaptureCamera() throws Exception {
        return selectCaptureCamera(captureWidth, captureHeight, captureFps, true);
    }

    boolean containsSize(
            Size[] sizes,
            Size wantedSize
    ) {
        if (sizes == null) {
            return false;
        }

        for (Size size : sizes) {
            if (size.equals(wantedSize)) {
                return true;
            }
        }

        return false;
    }

    boolean isCaptureModeSupported(int width, int height, int fps) {
        if (cameraManager == null) return false;
        try {
            Size wanted = new Size(width, height);
            for (String id : cameraManager.getCameraIdList()) {
                CameraCharacteristics c = cameraManager.getCameraCharacteristics(id);
                StreamConfigurationMap map = c.get(
                        CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                if (map == null) continue;
                if (fps <= 60) {
                    if (containsSize(map.getOutputSizes(SurfaceTexture.class), wanted) &&
                            containsSize(map.getOutputSizes(MediaCodec.class), wanted)) {
                        return true;
                    }
                } else if (containsSize(map.getHighSpeedVideoSizes(), wanted)) {
                    Range<Integer>[] ranges = map.getHighSpeedVideoFpsRangesFor(wanted);
                    if (ranges != null) {
                        for (Range<Integer> range : ranges) {
                            if (range.getLower() == fps && range.getUpper() == fps) return true;
                        }
                    }
                }
            }
        } catch (Exception e) {
            TraceLog.e("Mode support query failed", e);
        }
        return false;
    }

    void refreshTestPresetSupport() {
        if (reverseRes1080Button == null) return;
        setPresetSupport(reverseRes1080Button, 1920, 1080, captureFps);
        setPresetSupport(reverseRes720Button, 1280, 720, captureFps);
        setPresetSupport(reverseRes540Button, 960, 540, captureFps);
        setPresetSupport(reverseResVgaButton, 640, 480, captureFps);
    }

    void setPresetSupport(Button button, int width, int height, int fps) {
        boolean supported = isCaptureModeSupported(width, height, fps);
        button.setEnabled(supported);
        button.setAlpha(supported ? 1.0f : 0.35f);
        TraceLog.i("TEST capability " + width + "x" + height + "@" + fps +
                " supported=" + supported);
    }

    void setReverseTestResolution(int width, int height, String label) {
        captureFps = REVERSE_CAPTURE_FPS;
        playbackFps = 24;
        if (!isCaptureModeSupported(width, height, captureFps)) {
            updateOverlay("Unsupported: " + width + "x" + height + "@" + captureFps);
            return;
        }
        captureWidth = width;
        captureHeight = height;
        activeTestPreset = "REVERSE_" + label + "_" + reversePlaybackMs + "MS_PLAYBACK";
        TraceLog.i("TEST resolution=" + width + "x" + height +
                " fps=" + captureFps + " recordMs=" + reverseRecordMs +
                " playbackMs=" + reversePlaybackMs + " playbackFps=" + playbackFps);
        updateControlButtons();
        restartForCaptureOptions("Reverse " + label);
    }

    void setReverseTestDuration(int playbackDurationMs) {
        if (glView != null) glView.setLensMode(GLView.LensMode.REVERSE);

        captureFps = REVERSE_CAPTURE_FPS;
        playbackFps = 24;
        reversePlaybackMs = playbackDurationMs;
        reverseRecordMs = playbackDurationMs * playbackFps / captureFps;

        activeTestPreset = "REVERSE_" + captureWidth + "x" + captureHeight +
                "_" + playbackDurationMs + "MS_PLAYBACK";
        TraceLog.i("TEST reverse cycle playbackMs=" + reversePlaybackMs +
                " recordMs=" + reverseRecordMs +
                " requested=" + captureWidth + "x" + captureHeight +
                "@" + captureFps + " playbackFps=" + playbackFps);
        refreshTestPresetSupport();
        updateControlButtons();
        restartForCaptureOptions("Reverse " + (playbackDurationMs / 1000.0) + "s cycle");
    }

    void applyStandardReversePreset() {
        if (glView != null) glView.setLensMode(GLView.LensMode.REVERSE);
        captureWidth = 1280;
        captureHeight = 720;
        captureFps = REVERSE_CAPTURE_FPS;
        playbackFps = 24;
        reverseRecordMs = STANDARD_REVERSE_RECORD_MS;
        reversePlaybackMs = STANDARD_REVERSE_PLAYBACK_MS;
        activeTestPreset = "STANDARD_REVERSE";
        TraceLog.i("TEST preset=STANDARD_REVERSE requested=1280x720@" +
                captureFps + " recordMs=" + reverseRecordMs +
                " playbackMs=" + reversePlaybackMs +
                " playbackFps=" + playbackFps);
        refreshTestPresetSupport();
        updateSplashTitle();
        updateControlButtons();
        restartForCaptureOptions("Standard Reverse");
    }

    void setCameraZoom(float zoom) {
        cameraZoom = zoom;
        updateControlButtons();
        updateOverlay("Camera zoom: " + zoomText());
    }

    void applyCameraZoom(
            CaptureRequest.Builder builder,
            float requestedZoom
    ) {
        try {
            CameraCharacteristics characteristics =
                    cameraManager.getCameraCharacteristics(
                            cameraDevice.getId()
                    );

            android.graphics.Rect active =
                    characteristics.get(
                            CameraCharacteristics
                                    .SENSOR_INFO_ACTIVE_ARRAY_SIZE
                    );

            if (active == null) {
                TraceLog.i(
                        "No active sensor array for zoom"
                );
                return;
            }

            float zoom =
                    Math.max(
                            1.0f,
                            requestedZoom
                    );

            int cropWidth =
                    (int) (
                            active.width() /
                                    zoom
                    );

            int cropHeight =
                    (int) (
                            active.height() /
                                    zoom
                    );

            int left =
                    active.left +
                            (active.width() -
                                    cropWidth) / 2;

            int top =
                    active.top +
                            (active.height() -
                                    cropHeight) / 2;

            android.graphics.Rect crop =
                    new android.graphics.Rect(
                            left,
                            top,
                            left + cropWidth,
                            top + cropHeight
                    );

            builder.set(
                    CaptureRequest
                            .SCALER_CROP_REGION,
                    crop
            );

            TraceLog.i(
                    "Zoom " +
                            zoom +
                            " crop=" +
                            crop
            );

        } catch (Exception e) {
            TraceLog.e(
                    "applyCameraZoom failed",
                    e
            );
        }
    }

    int effectiveRecordDurationMs() {
        return glView != null && glView.getLensMode() == GLView.LensMode.REVERSE
                ? reverseRecordMs
                : Math.max(50, slowPlaybackMs / Math.max(1, slowFactor));
    }

    int effectivePlaybackDurationMs() {
        return glView != null && glView.getLensMode() == GLView.LensMode.REVERSE
                ? reversePlaybackMs
                : slowPlaybackMs;
    }

    void setLensMode(GLView.LensMode mode) {
        if (glView == null || glView.getLensMode() == mode) {
            return;
        }

        if (scrubPanel != null) scrubPanel.setVisibility(View.GONE);

        if (GLView.isTapeMode(mode)) {
            if (tape3 != null &&
                    (GLView.isTapeMode(glView.getLensMode()))) {
                glView.setLensMode(mode);
                configureTapeMode(tape3);
                showTapeOutput();
                updateControlButtons();
                updateScrubUi();
                updateOverlay(currentLensTitle());
                return;
            }
            running = false;
            glView.setLensMode(mode);
            if (isManualTapeMode()) glView.showRamLive();
            captureFps = selectedCaptureFps;
            playbackFps = 60;
            activeTestPreset = "TAPE3_" + captureWidth + "X" + captureHeight + "_" + captureFps;
            updateControlButtons();
            updateScrubUi();
            scheduleCameraRecovery("Tape3 mode restart");
            return;
        }

        if (mode == GLView.LensMode.DUBBUF_REVERSE) {
            running = false; recordingFollowing = false; recordingCycleId = -1; loadingCycleId = -1;
            glView.stopFreeze(); glView.releaseAllPlayers(); glView.setLensMode(mode);
            captureFps = selectedCaptureFps; captureWidth = 1280; captureHeight = 720; playbackFps = 30;
            activeTestPreset = "DUBBUF_REVERSE_60_30_TAPE";
            updateControlButtons(); updateOverlay("Reverse; restarting");
            if (splashPanel != null) splashPanel.setVisibility(View.GONE);
            scheduleCameraRecovery("DubBuf mode restart");
            return;
        }

        if (mode == GLView.LensMode.RAMREV ||
                mode == GLView.LensMode.CREV) {
            running = false;
            recordingFollowing = false;
            pendingRecordingCycleId = -1;
            loadingCycleId = -1;

            mainHandler.removeCallbacks(ramRevTick);
            ramRevCycleStartMs = 0L;

            glView.stopFreeze();
            glView.stopDubBufReverse();
            glView.stopStutter();
            glView.stopFast();
            glView.releaseAllPlayers();
            glView.setLensMode(mode);

            captureFps = selectedCaptureFps;
            captureWidth = 1920;
            captureHeight = 1080;
            playbackFps = 60;
            activeTestPreset = mode == GLView.LensMode.CREV
                    ? "CREV_1080P60" : "RAMREV_1080P60";

            updateControlButtons();
            updateScrubUi();
            updateOverlay("RAMRev 1080p60; restarting");

            if (splashPanel != null) splashPanel.setVisibility(View.GONE);
            scheduleCameraRecovery("RAMRev mode restart");
            return;
        }

        if (mode == GLView.LensMode.REWIND) {
            running = false;
            recordingFollowing = false;
            pendingRecordingCycleId = -1;
            loadingCycleId = -1;

            rewindHolding = false;
            rewindCatchup = false;
            rewindAtLimit = false;
            rewindDelayMs = 0.0;
            rewindLastTickMs = 0L;
            mainHandler.removeCallbacks(rewindTick);

            glView.stopFreeze();
            glView.stopDubBufReverse();
            glView.stopStutter();
            glView.stopFast();
            glView.releaseAllPlayers();
            glView.setLensMode(mode);

            captureFps = selectedCaptureFps;
            captureWidth = 1920;
            captureHeight = 1080;
            playbackFps = 60;
            activeTestPreset = "RAM_REWIND_1080P60_1X_3X";

            updateControlButtons();
            updateScrubUi();
            updateOverlay("Rewind RAM 1080p60; restarting");

            if (splashPanel != null) splashPanel.setVisibility(View.GONE);
            scheduleCameraRecovery("Rewind RAM mode restart");
            return;
        }

        if (mode == GLView.LensMode.SCRUB) {
            running = false;
            recordingFollowing = false;
            pendingRecordingCycleId = -1;
            loadingCycleId = -1;
            glView.stopFreeze();
            glView.stopDubBufReverse();
            glView.stopStutter();
            glView.stopFast();
            glView.releaseAllPlayers();
            glView.setLensMode(mode);
            captureFps = selectedCaptureFps;
            captureWidth = 1920;
            captureHeight = 1080;
            playbackFps = 60;
            activeTestPreset = "RAM_SCRUB_1080P60";
            ramSelectedOffsetMs = 0L;
            updateControlButtons();
            updateScrubUi();
            updateOverlay("Scrub RAM 1080p60; restarting");
            if (splashPanel != null) splashPanel.setVisibility(View.GONE);
            scheduleCameraRecovery("Scrub RAM mode restart");
            return;
        }

        if (mode == GLView.LensMode.FAST) {
            running = false;
            recordingFollowing = false;
            pendingRecordingCycleId = -1;
            loadingCycleId = -1;
            glView.stopFreeze();
            glView.stopDubBufReverse();
            glView.stopStutter();
            glView.releaseAllPlayers();
            glView.setLensMode(mode);
            captureFps = selectedCaptureFps;
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
            recordingFollowing = false;
            pendingRecordingCycleId = -1;
            loadingCycleId = -1;
            glView.stopFreeze();
            glView.stopDubBufReverse();
            glView.releaseAllPlayers();
            glView.setLensMode(mode);
            captureFps = selectedCaptureFps;
            captureWidth = 1280;
            captureHeight = 720;
            playbackFps = 60;
            stutterTimeMs = 1000;
            stutterSlices = 4;
            glView.setStutterTimeMs(stutterTimeMs);
            glView.setStutterSlices(stutterSlices);
            activeTestPreset = "STUTTER_SLICE_720P60_1000MS_X4";
            updateControlButtons();
            updateOverlay("Stutter 1.0 s × 4; restarting");
            if (splashPanel != null) splashPanel.setVisibility(View.GONE);
            scheduleCameraRecovery("Stutter mode restart");
            return;
        }

        if (mode == GLView.LensMode.FREEZE) {
            running = false;
            recordingFollowing = false;
            pendingRecordingCycleId = -1;
            loadingCycleId = -1;
            hideRecordCue();
            hideRecordProgress();
            setRecordingIndicator(false);
            glView.releaseAllPlayers();
            boolean needsNormalPreview = captureFps > 60 || captureWidth != 1280 || captureHeight != 720;
            captureFps = selectedCaptureFps; captureWidth = 1280; captureHeight = 720; playbackFps = 60;
            activeTestPreset = "STROBE_720P60";
            glView.setLensMode(GLView.LensMode.FREEZE);
            glView.startFreeze(5.0f);
            updateSplashTitle(); updateControlButtons(); updateOverlay("Strobe 5 Hz");
            if (splashPanel != null) splashPanel.setVisibility(View.GONE);
            if (needsNormalPreview) scheduleCameraRecovery("Strobe normal preview restart");
            return;
        }

        glView.stopFreeze();
        glView.setLensMode(mode);

        captureFps = selectedCaptureFps;

        if (mode == GLView.LensMode.SLOW) {
            playbackFps = Math.max(1, Math.round((float) captureFps / slowFactor));
            captureWidth = 1920;
            captureHeight = 1080;
            recordCueMs = 0;
            activeTestPreset = "SLOW_1080P" + captureFps + "_TAPE";
        } else {
            playbackFps = 48;
            captureWidth = 1280;
            captureHeight = 720;
            recordCueMs = 0;
            reverseRecordMs = STANDARD_REVERSE_RECORD_MS;
            reversePlaybackMs = STANDARD_REVERSE_PLAYBACK_MS;
            activeTestPreset = "STANDARD_REVERSE_120_48";
        }

        updateSplashTitle();
        updateControlButtons();
        restartForCaptureOptions(
                (mode == GLView.LensMode.REVERSE
                        ? "Reverse Lens"
                        : "Slow Lens") +
                        " @ " + captureFps + " fps"
        );
    }


    void setFreezeFrequency(float hz) {
        if (glView == null) return;
        if (glView.getLensMode() != GLView.LensMode.FREEZE) {
            setLensMode(GLView.LensMode.FREEZE);
        }
        glView.setFreezeFrequency(hz);
        updateControlButtons();
        updateOverlay("Freeze " + hz + " Hz");
    }

    void selectTimeChoice(int index) {
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

    void setSlowFactor(int factor) {
        slowFactor = Math.max(2, Math.min(16, factor));
        playbackFps = Math.max(1, Math.round((float) captureFps / slowFactor));
        captureDurationMs = Math.max(50, slowPlaybackMs / slowFactor);
        recordCueMs = 0;
        if (glView != null && glView.getLensMode() == GLView.LensMode.SLOW) {
            updateControlButtons();
            restartForCaptureOptions("Slow " + slowFactor + "×");
        } else {
            updateControlButtons();
        }
    }

    void setFastSpeed(float speed) {
        fastSpeed = Math.max(1.0f, Math.min(4.0f, speed));
        if (fastSpeed > 1.0f &&
                fastSpeed * fastTimeMs > 2000.0f)
            fastTimeMs = Math.max(100,
                    Math.round(2000.0f / fastSpeed));
        if (glView != null) {
            glView.setFastTimeMs(fastTimeMs);
            glView.setFastSpeed(fastSpeed);
        }
        updateControlButtons();
        updateOverlay("Fast " + fastSpeed + "×");
    }

    void setModePlaybackDuration(int durationMs) {
        if (glView == null) return;
        GLView.LensMode mode = glView.getLensMode();
        if (mode == GLView.LensMode.SLOW) {
            slowPlaybackMs = Math.max(500, Math.min(4000, durationMs));
            captureDurationMs = Math.max(50, slowPlaybackMs / Math.max(1, slowFactor));
            recordCueMs = 0;
            activeTestPreset = "SLOW_" + slowPlaybackMs + "MS_X" + slowFactor;
            updateOverlay("Slow Time " + (slowPlaybackMs / 1000.0f) + " s");
        } else if (mode == GLView.LensMode.REVERSE3) {
            reversePlaybackMs = Math.max(500, Math.min(4000, durationMs));
            if (tape3 != null) tape3.setReverseDurationMs(reversePlaybackMs);
            updateOverlay("Reverse3 Time " + (reversePlaybackMs / 1000.0f) + " s");
        } else if (mode == GLView.LensMode.DUBBUF_REVERSE) {
            reversePlaybackMs = Math.max(500, Math.min(4000, durationMs));
            glView.startDubBufReverse(reversePlaybackMs, null);
            updateOverlay("Reverse Time " + (reversePlaybackMs / 1000.0f) + " s");
        } else if (mode == GLView.LensMode.STUTTER) {
            int t = Math.max(200, Math.min(2000, durationMs));
            stutterTimeMs = t;
            glView.setStutterTimeMs(t);
            updateOverlay("Stutter slice " + (t / 1000.0f) + " s");
        } else if (mode == GLView.LensMode.FAST) {
            fastTimeMs = Math.max(100, Math.min(2000, durationMs));
            if (fastSpeed > 1.0f &&
                    fastSpeed * fastTimeMs > 2000.0f)
                fastSpeed = 2000.0f / fastTimeMs;
            glView.setFastTimeMs(fastTimeMs);
            glView.setFastSpeed(fastSpeed);
            updateOverlay("Fast Time " + (fastTimeMs / 1000.0f) + " s");
        } else if (mode == GLView.LensMode.FREEZE) {
            int t = Math.max(100, Math.min(4000, durationMs));
            glView.setFreezeFrequency(1000.0f / t);
            updateOverlay("Strobe Time " + (t / 1000.0f) + " s");
        } else {
            reversePlaybackMs = durationMs;
            updateOverlay("Time " + (durationMs / 1000.0f) + " s");
        }
        updateControlButtons();
    }

    void setCaptureResolution(int width, int height) {
        if (captureWidth == width && captureHeight == height) return;
        try {
            if (cameraManager != null) selectCaptureCamera(width, height, captureFps, false);
        } catch (Exception e) { flashStatus(e.getMessage(), 3500); return; }
        captureWidth = width;
        captureHeight = height;
        restartForCaptureOptions("Resolution " + width + "x" + height);
    }

    void setCaptureFps(int fps) {
        if (fps != 60 && fps != 120) return;
        try {
            if (cameraManager != null) selectCaptureCamera(captureWidth, captureHeight, fps, false);
        } catch (Exception e) { flashStatus(e.getMessage(), 3500); return; }
        selectedCaptureFps = captureFps = fps;
        if (glView != null && glView.getLensMode() == GLView.LensMode.SLOW)
            playbackFps = Math.max(1, Math.round((float) fps / slowFactor));
        updateControlButtons();
        scheduleCameraRecovery("Capture FPS " + fps);
    }

    void setCaptureDuration(int durationMs) {
        captureDurationMs = durationMs;
        updateControlButtons();
        updateOverlay(
                "Capture length " +
                        durationMs +
                        " ms"
        );
    }

    void setCaptureBitrate(int bitrate) {
        captureBitrate = bitrate;
        updateControlButtons();
        updateOverlay(
                "Capture bitrate " +
                        (bitrate / 1_000_000) +
                        " Mb/s"
        );
    }

    void setRecordCueMs(int cueMs) {
        recordCueMs = cueMs;
        updateControlButtons();
        updateOverlay(
                "Record cue " +
                        cueMs +
                        " ms"
        );
    }

    void restartForCaptureOptions(
            String reason
    ) {
        TraceLog.i(
                "capture option restart: " +
                        reason
        );

        updateControlButtons();
        updateOverlay(
                reason +
                        "; restarting"
        );

        running = false;
        recordingFollowing = false;
        recordingCycleId = -1;
        loadingCycleId = -1;

        hideRecordCue();
        hideRecordProgress();
        setRecordingIndicator(false);

        if (splashPanel != null) {
            splashPanel.setVisibility(View.GONE);
        }

        if (glView != null) {
            glView.releaseAllPlayers();
        }

        if (cameraTexture != null) {
            cameraTexture.setDefaultBufferSize(
                    captureWidth,
                    captureHeight
            );
        }

        scheduleCameraRecovery("capture option restart");
    }

    String currentLensTitle() {
        if (glView == null) return "Timetoy";
        if (glView.getLensMode() == GLView.LensMode.REVERSE3) return "Reverse3";
        if (glView.getLensMode() == GLView.LensMode.DUBBUF_REVERSE) return "Reverse";
        if (glView.getLensMode() == GLView.LensMode.SLOW) return "Slow";
        if (glView.getLensMode() == GLView.LensMode.FREEZE) return "Freeze";
        if (glView.getLensMode() == GLView.LensMode.STUTTER) return "Stutter";
        if (glView.getLensMode() == GLView.LensMode.FAST) return "Fast";
        if (glView.getLensMode() == GLView.LensMode.SCRUB) return "Scrub";
        if (glView.getLensMode() == GLView.LensMode.REWIND) return "Rewind";
        if (glView.getLensMode() == GLView.LensMode.RAMREV) return "RAMRev";
        if (glView.getLensMode() == GLView.LensMode.CREV) return "CRev";
        if (glView.getLensMode() == GLView.LensMode.TAPE3) return "Wobble";
        if (glView.getLensMode() == GLView.LensMode.REPLAY) return "Replay";
        if (glView.getLensMode() == GLView.LensMode.MOLASSES) return "Molasses";
        if (glView.getLensMode() == GLView.LensMode.DOWNSHIFT) return "Downshift";
        return "Reverse";
    }

    void updateSplashTitle() {
        if (splashTitle != null) {
            splashTitle.setText("Timetoy");
        }
    }

    String zoomText() {
        if (cameraZoom == 1.5f) return "1.5×";
        if (cameraZoom == 2.0f) return "2×";
        return "1×";
    }

    Button makeControlButton(
            String text
    ) {
        Button button =
                new Button(this);

        button.setText(text);
        button.setAllCaps(false);
        button.setTextSize(11);
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        button.setPadding(dp(4), dp(1), dp(4), dp(1));

        return button;
    }

    Button makeSmallControlButton(String text) {
        Button button = makeControlButton(text);
        button.setTextSize(10);
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        button.setPadding(dp(5), dp(2), dp(5), dp(2));
        button.setSingleLine(false);
        return button;
    }

    LinearLayout.LayoutParams compactButtonParams() {
        return new LinearLayout.LayoutParams(dp(72), dp(60));
    }

    LinearLayout.LayoutParams tinyButtonParams() {
        return new LinearLayout.LayoutParams(0, dp(54), 1.0f);
    }

    LinearLayout.LayoutParams
    weightedButtonParams() {
        return new LinearLayout
                .LayoutParams(
                0,
                dp(54),
                1.0f
        );
    }


    LinearLayout.LayoutParams scrollingButtonParams() {
        return new LinearLayout.LayoutParams(dp(132), dp(54));
    }

    HorizontalScrollView makeHorizontalScroller(View row) {
        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setFillViewport(false);
        scroll.addView(row, new HorizontalScrollView.LayoutParams(-2, -2));
        return scroll;
    }

    void setPlaybackGain(
            float gain
    ) {
        playbackGain = gain;

        if (glView != null) {
            glView.setPlaybackGain(
                    gain
            );
        }

        updateControlButtons();

        updateOverlay(
                "Playback brightness: " +
                        gainText()
        );
    }

    void updateControlButtons() {
        if (glView != null && effectControlLabel != null) {
            GLView.LensMode m = glView.getLensMode();
            boolean slow = m == GLView.LensMode.SLOW;
            boolean stutter = m == GLView.LensMode.STUTTER;
            boolean fast = m == GLView.LensMode.FAST;
            boolean strobe = m == GLView.LensMode.FREEZE;
            effectControlLabel.setVisibility(isManualTapeMode() || m == GLView.LensMode.TAPE3 ? View.GONE : View.VISIBLE);
            effectControlLabel.setText(stutter ? "Time / Slices" :
                    fast ? "Time / Speed" :
                    slow ? "Time / Slowdown" : "Time");
            timeControlRow.setVisibility(isManualTapeMode() || m == GLView.LensMode.TAPE3 ? View.GONE : View.VISIBLE);
            slowControlRow.setVisibility(slow ? View.VISIBLE : View.GONE);
            stutterSlicesRow.setVisibility(stutter ? View.VISIBLE : View.GONE);
            fastSpeedRow.setVisibility(fast ? View.VISIBLE : View.GONE);
            freezeRateRow.setVisibility(View.GONE);
            updateModeRail();
        }
        if (speed16Button == null) {
            return;
        }

        if (reverseLensButton != null && glView != null) {
            reverseLensButton.setEnabled(glView.getLensMode() != GLView.LensMode.FAST);
            if (dubBufReverseButton != null) dubBufReverseButton.setEnabled(glView.getLensMode() != GLView.LensMode.DUBBUF_REVERSE);
            slowLensButton.setEnabled(
                    glView.getLensMode() != GLView.LensMode.SLOW
            );
            freezeLensButton.setEnabled(
                    glView.getLensMode() != GLView.LensMode.FREEZE
            );
            if (stutterLensButton != null) {
                stutterLensButton.setEnabled(
                        glView.getLensMode() != GLView.LensMode.STUTTER
                );
            }
        }
        if (fast1Button != null) {
            fast1Button.setEnabled(Math.abs(fastSpeed - 1.0f) > 0.001f);
            fast15Button.setEnabled(Math.abs(fastSpeed - 1.5f) > 0.001f);
            fast2Button.setEnabled(Math.abs(fastSpeed - 2.0f) > 0.001f);
            fast3Button.setEnabled(Math.abs(fastSpeed - 3.0f) > 0.001f);
            fast4Button.setEnabled(Math.abs(fastSpeed - 4.0f) > 0.001f);
        }
        if (freeze2Button != null && glView != null) {
            float hz = glView.getFreezeFrequency();
            freeze2Button.setEnabled(hz != 2.0f);
            freeze4Button.setEnabled(hz != 4.0f);
            freeze5Button.setEnabled(hz != 5.0f);
            freeze6Button.setEnabled(hz != 6.0f);
            freeze8Button.setEnabled(hz != 8.0f);
            freeze10Button.setEnabled(hz != 10.0f);
        }

        speed16Button.setEnabled(
                playbackFps != 15
        );

        speed8Button.setEnabled(
                playbackFps != 30
        );

        speed4Button.setEnabled(
                playbackFps != 60
        );

        if (reverseRes1080Button != null) {
            reverseRes1080Button.setEnabled(captureWidth != 1920 && isCaptureModeSupported(1920, 1080, captureFps));
            reverseRes720Button.setEnabled(captureWidth != 1280 && isCaptureModeSupported(1280, 720, captureFps));
            reverseRes540Button.setEnabled(captureWidth != 960 && isCaptureModeSupported(960, 540, captureFps));
            reverseResVgaButton.setEnabled(captureWidth != 640 && isCaptureModeSupported(640, 480, captureFps));
            boolean slowMode = glView != null && glView.getLensMode() == GLView.LensMode.SLOW;
            reverseTime16Button.setText(slowMode ? "1s" : "0.5s");
            reverseTime4Button.setText(slowMode ? "2s" : "1s");
            reverseTime8Button.setText(slowMode ? "3s" : "1.5s");
            standardReverseButton.setText(slowMode ? "4s" : "2s");
            int selectedMs = slowMode ? slowPlaybackMs :
                    (glView != null && glView.getLensMode() == GLView.LensMode.FAST
                            ? fastTimeMs : reversePlaybackMs);
            reverseTime16Button.setEnabled(selectedMs != (slowMode ? 1000 : 500));
            reverseTime4Button.setEnabled(selectedMs != (slowMode ? 2000 : 1000));
            reverseTime8Button.setEnabled(selectedMs != (slowMode ? 3000 : 1500));
            standardReverseButton.setEnabled(selectedMs != (slowMode ? 4000 : 2000));
        }

        resolution1080Button.setEnabled(
                captureWidth != 1920
        );
        resolution720Button.setEnabled(
                captureWidth != 1280
        );

        capture240Button.setEnabled(
                captureFps != 240
        );
        capture120Button.setEnabled(
                captureFps != 120
        );
        capture60Button.setEnabled(
                captureFps != 60
        );
        capture30Button.setEnabled(
                captureFps != 30
        );

        length05Button.setEnabled(
                captureDurationMs != 500
        );
        length10Button.setEnabled(
                captureDurationMs != 1000
        );

        bitrate20Button.setEnabled(
                captureBitrate != 20_000_000
        );
        bitrate40Button.setEnabled(
                captureBitrate != 40_000_000
        );
        bitrate80Button.setEnabled(
                captureBitrate != 80_000_000
        );

        cueOffButton.setEnabled(
                recordCueMs != 0
        );
        cue05Button.setEnabled(
                recordCueMs != 500
        );
        cue10Button.setEnabled(
                recordCueMs != 1000
        );

        if (standardReverseButton != null) {
            standardReverseButton.setEnabled(!"STANDARD_REVERSE".equals(activeTestPreset));
        }
    }

    String slowdownText() {
        if (playbackFps == 15) {
            return "16×";
        }

        if (playbackFps == 60) {
            return "4×";
        }

        return "8×";
    }

    String gainText() {
        if (playbackGain == 2.0f) {
            return "2×";
        }

        if (playbackGain == 4.0f) {
            return "4×";
        }

        return "1×";
    }

    void updateOverlay(
            String event
    ) {
        lastEvent = event;

        runOnUiThread(() -> {
            if (splashStatus != null &&
                    splashPanel != null &&
                    splashPanel.getVisibility() ==
                            View.VISIBLE) {

                splashStatus.setText(event);
            }
        });

        TraceLog.i(
                "overlay: " +
                        event
        );

        refreshOverlay();
    }

    void refreshOverlay() {
        runOnUiThread(() -> {
            if (overlay == null) return;
            if (tape3 != null && isManualTapeMode() && tape3.isPlaybackLive())
                glView.showRamLive();

            if (glView != null &&
                    GLView.isTapeMode(glView.getLensMode())) {
                Tape3 t = tape3;
                double sensorFps = tape3SensorFrameMs > 0.0
                        ? 1000.0 / tape3SensorFrameMs : 0.0;

                String aeText;
                switch (tape3AeState) {
                    case CaptureResult.CONTROL_AE_STATE_INACTIVE:
                        aeText = "INACTIVE"; break;
                    case CaptureResult.CONTROL_AE_STATE_SEARCHING:
                        aeText = "SEARCHING"; break;
                    case CaptureResult.CONTROL_AE_STATE_CONVERGED:
                        aeText = "CONVERGED"; break;
                    case CaptureResult.CONTROL_AE_STATE_LOCKED:
                        aeText = "LOCKED"; break;
                    case CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED:
                        aeText = "FLASH"; break;
                    case CaptureResult.CONTROL_AE_STATE_PRECAPTURE:
                        aeText = "PRECAPTURE"; break;
                    default:
                        aeText = "--";
                }

                if (t == null) {
                    overlay.setText("TAPE3\nStarting...");
                } else {
                    overlay.setText(String.format(Locale.US,
                            "TAPE3  %dx%d @ %d requested FPS\n" +
                            "%s exposure / FPS range %s\n\n" +
                            "SENSOR\n" +
                            "Frame     %.2f ms   %.1f fps\n" +
                            "Exposure  %.2f ms\n" +
                            "ISO       %d\n" +
                            "AE        %s\n\n" +
                            "CAMERA\n" +
                            "Callbacks %.1f /s\n\n" +
                            "CODEC\n" +
                            "Frames    %d\n" +
                            "Rate      %.1f fps\n" +
                            "All-I     %.1f%%\n" +
                            "Average   %.1f KB\n" +
                            "Peak      %.1f KB\n\n" +
                            "IMU\n" +
                            "Accel     %+.2f  %+.2f  %+.2f m/s²\n" +
                            "Gyro      %+.3f  %+.3f  %+.3f rad/s\n\n" +
                            "TAPE\n" +
                            "RAM       %.1f / 128 MiB\n" +
                            "Write     %.2f MB/s\n" +
                            "History   %.1f s",
                            captureWidth, captureHeight, captureFps,
                            captureHighSpeed || !manualExposure || !manualSensorAvailable ? "Auto" : "Manual",
                            tape3RequestedAe,
                            tape3SensorFrameMs,
                            sensorFps,
                            tape3ExposureMs,
                            tape3Iso,
                            aeText,
                            tape3CallbackFps,
                            t.getTotalFrames(),
                            t.getEncodedFps(),
                            t.getKeyPercent(),
                            t.getAverageFrameBytes() / 1024.0,
                            t.getPeakFrameBytes() / 1024.0,
                            imuAccelX,
                            imuAccelY,
                            imuAccelZ,
                            imuGyroX,
                            imuGyroY,
                            imuGyroZ,
                            t.getMegabytes(),
                            t.getMegabytesPerSecond(),
                            t.getEstimatedHistorySeconds()));
                }
                return;
            }


            GLView.LensMode lensMode = glView == null
                    ? GLView.LensMode.DUBBUF_REVERSE
                    : glView.getLensMode();

            String mode;
            if (lensMode == GLView.LensMode.SLOW) mode = "SLOW";
            else if (lensMode == GLView.LensMode.DUBBUF_REVERSE) mode = "REV";
            else if (lensMode == GLView.LensMode.FREEZE) mode = "FREEZE";
            else if (lensMode == GLView.LensMode.STUTTER) mode = "STUTTER";
            else if (lensMode == GLView.LensMode.FAST) mode = "FAST";
            else mode = "REV";

            String rendererState =
                    glView != null && glView.renderer != null
                            ? glView.renderer.stateText()
                            : "START";

            int playing;
            int recording;
            if (lensMode == GLView.LensMode.DUBBUF_REVERSE &&
                    glView != null && glView.renderer != null) {
                playing = glView.renderer.getDubBufPlayCycle();
                recording = glView.renderer.getDubBufRecordCycle();
            } else if (lensMode == GLView.LensMode.STUTTER &&
                    glView != null && glView.renderer != null) {
                playing = glView.renderer.getStutterCycle();
                recording = -1;
            } else if (lensMode == GLView.LensMode.FAST &&
                    glView != null && glView.renderer != null) {
                playing = glView.renderer.getFastCycle();
                recording = -1;
            } else {
                playing = currentItem == null || !currentItem.started
                        ? -1 : currentItem.cycleId;
                recording = recordingCycleId;
            }

            int ready = nextItem != null && nextItem.ready
                    ? nextItem.cycleId : -1;

            boolean previewFpsMode =
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

            long maxDrawGapMs = glView != null && glView.renderer != null
                    ? glView.renderer.maxDrawGapMs() : 0L;

            overlay.setText(
                    mode + "  " + captureFps + " fps\n" +
                    "Play " + clipText(playing) + "  Rec " + clipText(recording) + "\n" +
                    "Load " + clipText(loadingCycleId) + " Ready " + clipText(ready) + "\n" +
                    "Actual " + fpsText + " fps  F " + measuredCaptureFrames + "\n" +
                    "Draw gap " + maxDrawGapMs + " ms\n" +
                    rendererState
            );
        });
    }

    String clipText(int cycleId) {
        return cycleId < 0
                ? "---"
                : String.format(Locale.US, "%03d", cycleId);
    }

    void enterFullscreen() {
        getWindow().setFlags(
                WindowManager
                        .LayoutParams
                        .FLAG_FULLSCREEN,
                WindowManager
                        .LayoutParams
                        .FLAG_FULLSCREEN
        );

        getWindow()
                .getDecorView()
                .setSystemUiVisibility(
                        View.SYSTEM_UI_FLAG_FULLSCREEN |
                                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY |
                                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
                                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
                                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                );
    }

    @Override
    public void onWindowFocusChanged(
            boolean hasFocus
    ) {
        TraceLog.i("DEEP LIFE windowFocus ENTER hasFocus=" + hasFocus);
        super.onWindowFocusChanged(
                hasFocus
        );

        if (hasFocus) {
            enterFullscreen();
        }
    }

    @Override
    protected void onPause() {
        TraceLog.i("DEEP LIFE onPause ENTER");
        super.onPause();
        appPaused = true;
        if (guiOrientationListener != null) guiOrientationListener.disable();
        dismissChoices();
        stopImu();

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
        TraceLog.i("DEEP LIFE onResume ENTER");
        super.onResume();
        if (guiOrientationListener != null && guiOrientationListener.canDetectOrientation())
            guiOrientationListener.enable();
        enterFullscreen();
        if (!appPaused) return;
        appPaused = false;
        startImu();

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
        if (guiOrientationListener != null) guiOrientationListener.disable();
        running = false;

        hudHandler.removeCallbacks(
                hudHeartbeat
        );

        mainHandler.removeCallbacks(
                hideFlashLabel
        );

        mainHandler.removeCallbacksAndMessages(
                null
        );

        setRecordingIndicator(
                false
        );

        if (glView != null) {
            glView.stopFreeze();
            glView.stopFast();
            glView.releaseAllPlayers();
        }

        try {
            if (cameraHandler != null) {
                cameraHandler
                        .removeCallbacksAndMessages(
                                null
                        );
            }

            releasePersistentRecordingPipeline();

            if (cameraDevice != null) {
                cameraDevice.close();
            }

            if (previewSurface != null) {
                previewSurface.release();
            }

            if (cameraThread != null) {
                cameraThread.quitSafely();
            }

        } catch (Exception e) {
            TraceLog.e(
                    "onDestroy cleanup error",
                    e
            );
        }

        TraceLog.close();

        super.onDestroy();
    }
}

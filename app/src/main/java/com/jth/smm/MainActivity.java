// ============================================================
// Timetoy
// File: MainActivity.java
// Version: v0.6.27
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
import android.hardware.camera2.*;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
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
            "v0.6.27";

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
    TextView railTT, railView, railTime, railParam, railFX, railShare;
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
    int captureFps = REVERSE_CAPTURE_FPS;
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

    MediaSurfaceRecorder recorder;

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

    final Runnable hudHeartbeat =
            new Runnable() {
                @Override
                public void run() {
                    hudTicks++;
                    refreshOverlay();

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
        glView.setPortraitOrientation(
                getResources().getConfiguration().orientation ==
                        android.content.res.Configuration.ORIENTATION_PORTRAIT
        );
        glView.setLensMode(GLView.LensMode.DUBBUF_REVERSE);

        root.addView(
                glView,
                new FrameLayout.LayoutParams(
                        -1,
                        -1
                )
        );

        buildRecordingFrame(root);
        buildRecordCue(root);
        buildRecordProgress(root);
        buildHud(root);
        buildModeRail(root);
        buildWatermark(root);
        buildFlashLabel(root);
        buildSplash(root);

        setContentView(root);

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

        modeRail.addView(railTT);
        modeRail.addView(railView);
        modeRail.addView(railTime);
        modeRail.addView(railParam);
        modeRail.addView(railFX);
        modeRail.addView(railShare);

        railTT.setOnClickListener(v ->
                new android.app.AlertDialog.Builder(this)
                        .setTitle("TimeToy")
                        .setMessage("TimeToy\n" + VERSION + "\nby yo")
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
        showChoicePopup(anchor, labels, actions, null);
    }

    void showChoicePopup(View anchor, String[] labels, Runnable[] actions, int[] colors) {
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
            if (colors != null && i < colors.length) {
                b.setBackgroundColor(colors[i]);
                b.setTextColor(0xff000000);
            }
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
                },
                new int[]{
                        MODE_REVERSE, MODE_SLOW, MODE_FAST, MODE_FREEZE, MODE_STUTTER
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



    void startPreviewOnlySession(Runnable onReady) {
        if (cameraDevice == null || previewSurface == null) { if (onReady != null) onReady.run(); return; }
        try {
            if (session != null) { try { session.close(); } catch (Exception ignored) {} session = null; }
            cameraDevice.createCaptureSession(Collections.singletonList(previewSurface),
                    new CameraCaptureSession.StateCallback() {
                        @Override public void onConfigured(CameraCaptureSession s) {
                            try {
                                session = s; recordingSessionConfigured = false;
                                CaptureRequest.Builder b = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                                b.addTarget(previewSurface);
                                b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, new Range<Integer>(60, 60));
                                b.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
                                applyCameraZoom(b, cameraZoom);
                                s.setRepeatingRequest(b.build(), null, cameraHandler);
                                TraceLog.i("preview-only 60 fps session active");
                                if (onReady != null) mainHandler.post(onReady);
                            } catch (Exception e) { TraceLog.e("preview-only configure error", e); }
                        }
                        @Override public void onConfigureFailed(CameraCaptureSession s) {
                            TraceLog.i("preview-only session failed");
                        }
                    }, cameraHandler);
        } catch (Exception e) { TraceLog.e("startPreviewOnlySession failed", e); }
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

        final boolean highSpeed = captureFps > 60;

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
                    builder.set(
                            CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                            new Range<Integer>(captureFps, captureFps)
                    );
                    builder.set(
                            CaptureRequest.CONTROL_MODE,
                            CaptureRequest.CONTROL_MODE_AUTO
                    );
                    builder.set(
                            CaptureRequest.CONTROL_AE_MODE,
                            CaptureRequest.CONTROL_AE_MODE_ON
                    );
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
                int expectedFrames = Math.max(1, effectiveRecordDurationMs() * SLOW_CAPTURE_FPS / 1000);
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

            String cameraId =
                    findCaptureCamera();

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

    String findCaptureCamera()
            throws Exception {

        Size wantedSize =
                new Size(
                        captureWidth,
                        captureHeight
                );

        boolean highSpeed =
                captureFps > 60;

        for (String id :
                cameraManager
                        .getCameraIdList()) {

            CameraCharacteristics characteristics =
                    cameraManager
                            .getCameraCharacteristics(
                                    id
                            );

            StreamConfigurationMap map =
                    characteristics.get(
                            CameraCharacteristics
                                    .SCALER_STREAM_CONFIGURATION_MAP
                    );

            if (map == null) {
                continue;
            }

            if (!highSpeed) {
                boolean previewSupported =
                        containsSize(
                                map.getOutputSizes(
                                        SurfaceTexture.class
                                ),
                                wantedSize
                        );

                boolean encoderSupported =
                        containsSize(
                                map.getOutputSizes(
                                        MediaCodec.class
                                ),
                                wantedSize
                        );

                if (previewSupported &&
                        encoderSupported) {
                    TraceLog.i(
                            "Normal capture camera=" +
                                    id +
                                    " size=" +
                                    captureWidth +
                                    "x" +
                                    captureHeight +
                                    " fps=" +
                                    captureFps
                    );

                    return id;
                }

                continue;
            }

            Size[] highSpeedSizes =
                    map.getHighSpeedVideoSizes();

            if (!containsSize(
                    highSpeedSizes,
                    wantedSize
            )) {
                continue;
            }

            Range<Integer>[] ranges =
                    map.getHighSpeedVideoFpsRangesFor(
                            wantedSize
                    );

            if (ranges == null) {
                continue;
            }

            for (Range<Integer> range :
                    ranges) {

                if (range.getLower() ==
                        captureFps &&
                        range.getUpper() ==
                                captureFps) {

                    return id;
                }
            }
        }

        throw new RuntimeException(
                "No " +
                        captureWidth +
                        "x" +
                        captureHeight +
                        " " +
                        captureFps +
                        "fps camera found"
        );
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

        if (mode == GLView.LensMode.DUBBUF_REVERSE) {
            running = false; recordingFollowing = false; recordingCycleId = -1; loadingCycleId = -1;
            glView.stopFreeze(); glView.releaseAllPlayers(); glView.setLensMode(mode);
            captureFps = 60; captureWidth = 1280; captureHeight = 720; playbackFps = 30;
            activeTestPreset = "DUBBUF_REVERSE_60_30_TAPE";
            updateControlButtons(); updateOverlay("Reverse; restarting");
            if (splashPanel != null) splashPanel.setVisibility(View.GONE);
            scheduleCameraRecovery("DubBuf mode restart");
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
            recordingFollowing = false;
            pendingRecordingCycleId = -1;
            loadingCycleId = -1;
            glView.stopFreeze();
            glView.stopDubBufReverse();
            glView.releaseAllPlayers();
            glView.setLensMode(mode);
            captureFps = 60;
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
            captureFps = 60; captureWidth = 1280; captureHeight = 720; playbackFps = 60;
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

        // Slow Lens requires the constrained 240 fps camera path. Reverse is
        // intentionally left on its 120 fps high-speed request for this release.
        captureFps = mode == GLView.LensMode.SLOW
                ? SLOW_CAPTURE_FPS
                : REVERSE_CAPTURE_FPS;

        if (mode == GLView.LensMode.SLOW) {
            playbackFps = Math.max(1, Math.round((float) SLOW_CAPTURE_FPS / slowFactor));
            captureWidth = 1920;
            captureHeight = 1080;
            recordCueMs = 0;
            activeTestPreset = "SLOW_1080P240_TAPE";
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

    void setCaptureResolution(
            int width,
            int height
    ) {
        if (captureWidth == width &&
                captureHeight == height) {
            return;
        }

        captureWidth = width;
        captureHeight = height;
        restartForCaptureOptions(
                "Resolution " +
                        width +
                        "x" +
                        height
        );
    }

    void setCaptureFps(int fps) {
        if (captureFps == fps) {
            return;
        }

        captureFps = fps;
        restartForCaptureOptions(
                "Capture rate " +
                        fps +
                        " fps"
        );
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
        if (glView.getLensMode() == GLView.LensMode.DUBBUF_REVERSE) return "Reverse";
        if (glView.getLensMode() == GLView.LensMode.SLOW) return "Slow";
        if (glView.getLensMode() == GLView.LensMode.FREEZE) return "Freeze";
        if (glView.getLensMode() == GLView.LensMode.STUTTER) return "Stutter";
        if (glView.getLensMode() == GLView.LensMode.FAST) return "Fast";
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
            effectControlLabel.setText(stutter ? "Time / Slices" :
                    fast ? "Time / Speed" :
                    slow ? "Time / Slowdown" : "Time");
            timeControlRow.setVisibility(View.VISIBLE);
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
        super.onWindowFocusChanged(
                hasFocus
        );

        if (hasFocus) {
            enterFullscreen();
        }
    }

    @Override
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

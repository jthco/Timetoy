// ============================================================
// Timetoy
// File: GLView.java
// Version: v0.6.18
// Build: Live Freeze Mode
// Date: 2026-08-04
// ============================================================

package com.jth.smm;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.opengl.GLSurfaceView;
import android.os.Handler;
import android.os.Looper;
import android.view.Surface;

import java.io.File;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class GLView extends GLSurfaceView {
    public GLRenderer renderer;
    public CameraTextureReadyListener listener;
    private final LensPlayer[] players = new LensPlayer[2];
    private final LensMode[] slotModes = new LensMode[2];

    public enum LensMode { REVERSE, SLOW, FREEZE, STUTTER }

    private volatile LensMode lensMode = LensMode.REVERSE;
    private final boolean[] waitingForFirstTexture = new boolean[2];
    private final Runnable[] firstTextureCallbacks = new Runnable[2];

    private final Handler freezeHandler = new Handler(Looper.getMainLooper());
    private volatile boolean freezeRunning = false;
    private volatile float freezeFrequencyHz = 5.0f;

    private final Runnable freezeTick = new Runnable() {
        @Override public void run() {
            if (!freezeRunning || lensMode != LensMode.FREEZE) return;
            queueEvent(() -> {
                renderer.captureLiveFrameToFreezeTexture();
                requestRender();
            });
            long intervalMs = Math.max(100L, Math.round(1000.0f / freezeFrequencyHz));
            freezeHandler.postDelayed(this, intervalMs);
        }
    };

    public interface CameraTextureReadyListener {
        void onReady(SurfaceTexture surfaceTexture);
    }

    public GLView(Context context) {
        super(context);
        setEGLContextClientVersion(2);
        renderer = new GLRenderer(this);
        setRenderer(renderer);
        setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);
    }

    public void setLensMode(LensMode mode) {
        if (mode == null) throw new IllegalArgumentException("mode is null");
        if (mode != LensMode.FREEZE) stopFreeze();
        lensMode = mode;
        TraceLog.i("Lens mode=" + mode);
    }

    public LensMode getLensMode() { return lensMode; }
    public void requestCameraRender() { requestRender(); }
    public Surface getDecoderSurface(int slot) { return renderer.getDecoderSurface(slot); }

    public void setPlaybackGain(float gain) {
        queueEvent(() -> renderer.setPlaybackGain(gain));
    }

    public void startFreeze(float frequencyHz) {
        setFreezeFrequency(frequencyHz);
        releaseAllPlayers();
        lensMode = LensMode.FREEZE;
        freezeRunning = true;
        freezeHandler.removeCallbacks(freezeTick);
        queueEvent(() -> {
            renderer.showCameraOutput();
            requestRender();
        });
        long intervalMs = Math.max(100L, Math.round(1000.0f / freezeFrequencyHz));
        freezeHandler.postDelayed(freezeTick, intervalMs);
        TraceLog.i("Freeze started frequencyHz=" + freezeFrequencyHz);
    }

    public void stopFreeze() {
        freezeRunning = false;
        freezeHandler.removeCallbacks(freezeTick);
        if (renderer != null) {
            queueEvent(() -> {
                renderer.releaseFreezeTexture();
                renderer.showCameraOutput();
                requestRender();
            });
        }
    }

    public void setFreezeFrequency(float frequencyHz) {
        if (frequencyHz < 2.0f || frequencyHz > 10.0f) {
            throw new IllegalArgumentException("Freeze frequency must be 2-10 Hz");
        }
        freezeFrequencyHz = frequencyHz;
        if (freezeRunning) {
            freezeHandler.removeCallbacks(freezeTick);
            freezeHandler.post(freezeTick);
        }
        TraceLog.i("Freeze frequencyHz=" + freezeFrequencyHz);
    }

    public float getFreezeFrequency() { return freezeFrequencyHz; }

    public void prepareFile(int slot, File file, int outputFps, int playbackDurationMs,
                            Runnable onReady, Runnable onDone,
                            ReverseLensPlayer.FailureCallback onFailure) {
        if (slot < 0 || slot > 1) throw new IllegalArgumentException("Invalid decoder slot " + slot);
        TraceLog.i("GLView prepare slot=" + slot + " file=" +
                (file == null ? "null" : file.getName()) + " outputFps=" + outputFps);
        releaseSlot(slot);
        waitingForFirstTexture[slot] = true;
        firstTextureCallbacks[slot] = onReady;

        Runnable done = () -> {
            TraceLog.i("slot " + slot + " playback done callback");
            if (onDone != null) onDone.run();
        };

        LensMode modeForSlot = lensMode;
        slotModes[slot] = modeForSlot;
        waitingForFirstTexture[slot] = false;
        final boolean reverse = modeForSlot == LensMode.REVERSE;

        LensPlayer player = new ReverseLensPlayer(
                slot, file, getDecoderSurface(slot),
                new ReverseLensPlayer.GpuBankSink() {
                    @Override public void beginBank(int bankSlot, int width, int height, int maxFrames) {
                        CountDownLatchBridge.runOnGlAndWait(GLView.this,
                                () -> renderer.beginReverseBank(bankSlot, width, height, maxFrames));
                    }
                    @Override public void armFrameCapture(int bankSlot, int frameIndex, Runnable callback) {
                        queueEvent(() -> renderer.armReverseCapture(bankSlot, frameIndex, callback));
                    }
                    @Override public void showFrame(int bankSlot, int frameIndex, Runnable callback) {
                        queueEvent(() -> {
                            try { renderer.showReverseFrame(bankSlot, frameIndex); requestRender(); }
                            finally { if (callback != null) callback.run(); }
                        });
                    }
                },
                outputFps, playbackDurationMs, reverse,
                () -> {
                    TraceLog.i("slot " + slot + " " + (reverse ? "reverse" : "slow") + " GPU bank ready");
                    if (onReady != null) onReady.run();
                }, done, onFailure);
        players[slot] = player;
        player.prepareAsync();
    }

    public void onDecoderTextureFrameAvailable(int slot, int frameCount) {
        if (!waitingForFirstTexture[slot]) return;
        waitingForFirstTexture[slot] = false;
        TraceLog.i("slot " + slot + " first GL texture available frameCount=" + frameCount);
        Runnable callback = firstTextureCallbacks[slot];
        firstTextureCallbacks[slot] = null;
        if (callback != null) callback.run();
    }

    public void startPrepared(int slot) {
        LensPlayer player = players[slot];
        if (player == null || !player.isPrepared()) {
            TraceLog.i("startPrepared ignored slot=" + slot + " player=" + player);
            return;
        }
        queueEvent(() -> {
            TraceLog.i("GL switch to slot " + slot);
            if (slotModes[slot] != LensMode.REVERSE) renderer.showDecoderSlot(slot);
            player.startPrepared();
        });
    }

    public void releaseSlot(int slot) {
        LensPlayer player = players[slot];
        if (player != null) {
            player.cancel();
            players[slot] = null;
            slotModes[slot] = null;
        }
        waitingForFirstTexture[slot] = false;
        firstTextureCallbacks[slot] = null;
    }

    private static final class CountDownLatchBridge {
        static void runOnGlAndWait(GLView view, Runnable work) {
            CountDownLatch latch = new CountDownLatch(1);
            view.queueEvent(() -> { try { work.run(); } finally { latch.countDown(); } });
            try {
                if (!latch.await(5, TimeUnit.SECONDS))
                    throw new IllegalStateException("Timed out waiting for GL thread");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted waiting for GL thread", e);
            }
        }
    }

    public void releaseAllPlayers() {
        releaseSlot(0);
        releaseSlot(1);
    }
}

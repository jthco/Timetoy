// ============================================================
// SlowMo Lens
// File: SlowMoGLView.java
// Version: v0.4.4
// Build: Half-Second Capture
// Date: 2026-07-16
// ============================================================

package com.jth.smm;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.opengl.GLSurfaceView;
import android.view.Surface;

import java.io.File;

public class SlowMoGLView extends GLSurfaceView {
    public RenderEngine renderer;
    public CameraTextureReadyListener listener;
    private final H264SlowPlayer[] players =
            new H264SlowPlayer[2];

    private final boolean[] waitingForFirstTexture =
            new boolean[2];

    private final Runnable[] firstTextureCallbacks =
            new Runnable[2];

    public interface CameraTextureReadyListener {
        void onReady(
                SurfaceTexture surfaceTexture
        );
    }

    public SlowMoGLView(Context context) {
        super(context);

        setEGLContextClientVersion(2);

        renderer =
                new RenderEngine(this);

        setRenderer(renderer);

        setRenderMode(
                GLSurfaceView
                        .RENDERMODE_CONTINUOUSLY
        );
    }

    public void requestCameraRender() {
        requestRender();
    }

    public Surface getDecoderSurface(
            int slot
    ) {
        return renderer.getDecoderSurface(
                slot
        );
    }

    public void setPlaybackGain(float gain) {
        queueEvent(
                () -> renderer
                        .setPlaybackGain(gain)
        );
    }

    public void prepareFile(
            int slot,
            File file,
            int outputFps,
            int playbackSeconds,
            Runnable onReady,
            Runnable onDone
    ) {
        if (slot < 0 || slot > 1) {
            throw new IllegalArgumentException(
                    "Invalid decoder slot " +
                            slot
            );
        }

        TraceLog.i(
                "SlowMoGLView prepare slot=" +
                        slot +
                        " file=" +
                        (file == null
                                ? "null"
                                : file.getName()) +
                        " outputFps=" +
                        outputFps
        );

        releaseSlot(slot);

        waitingForFirstTexture[slot] = true;
        firstTextureCallbacks[slot] =
                onReady;

        H264SlowPlayer player =
                new H264SlowPlayer(
                        slot,
                        file,
                        getDecoderSurface(slot),
                        outputFps,
                        playbackSeconds,
                        () -> TraceLog.i(
                                "slot " +
                                        slot +
                                        " first frame released; awaiting GL texture"
                        ),
                        () -> {
                            TraceLog.i(
                                    "slot " +
                                            slot +
                                            " playback done callback"
                            );

                            if (onDone != null) {
                                onDone.run();
                            }
                        }
                );

        players[slot] = player;
        player.prepareAsync();
    }

    public void onDecoderTextureFrameAvailable(
            int slot,
            int frameCount
    ) {
        if (!waitingForFirstTexture[slot]) {
            return;
        }

        waitingForFirstTexture[slot] = false;

        TraceLog.i(
                "slot " +
                        slot +
                        " first GL texture available frameCount=" +
                        frameCount
        );

        Runnable callback =
                firstTextureCallbacks[slot];

        firstTextureCallbacks[slot] = null;

        if (callback != null) {
            callback.run();
        }
    }

    public void startPrepared(int slot) {
        H264SlowPlayer player =
                players[slot];

        if (player == null ||
                !player.isPrepared()) {

            TraceLog.i(
                    "startPrepared ignored slot=" +
                            slot +
                            " player=" +
                            player
            );

            return;
        }

        /*
         * The hidden texture already contains the first decoded frame.
         * Switch the GL source and release the playback latch in one
         * GL-thread operation.
         */
        queueEvent(() -> {
            TraceLog.i(
                    "GL switch to slot " +
                            slot
            );

            renderer.showDecoderSlot(slot);
            player.startPrepared();
        });
    }

    public void releaseSlot(int slot) {
        H264SlowPlayer player =
                players[slot];

        if (player != null) {
            player.cancel();
            players[slot] = null;
        }

        waitingForFirstTexture[slot] =
                false;

        firstTextureCallbacks[slot] =
                null;
    }

    public void releaseAllPlayers() {
        releaseSlot(0);
        releaseSlot(1);
    }
}

// ============================================================
// Timetoy
// File: GLRenderer.java
// Version: v0.6.30
// Build: 30 Hz Reverse Tape + Slice Stutter
// Date: 2026-08-09
// ============================================================

package com.jth.smm;

import android.graphics.SurfaceTexture;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.view.Surface;

public class GLRenderer implements GLSurfaceView.Renderer {
    static final int GL_TEXTURE_EXTERNAL_OES = 0x8D65;
    final GLView view;
    int extProgram, tex2dProgram, ramYuvProgram;
    int cameraTexId;
    final int[] decoderTexIds = new int[2];
    static final int MAX_REVERSE_FRAMES = 480;
    final int[][] reverseBankTexIds = new int[2][MAX_REVERSE_FRAMES];
    final SimpleTextureSource[][] reverseBankSources = new SimpleTextureSource[2][MAX_REVERSE_FRAMES];
    final int[] reverseBankCounts = new int[2];
    final int[] reverseBankWidths = new int[2];
    final int[] reverseBankHeights = new int[2];
    final int[] pendingCaptureFrame = {-1, -1};
    final Runnable[] pendingCaptureCallbacks = new Runnable[2];
    int reverseFboId;

    static final int MAX_DUBBUF_FRAMES = 96;
    final int[][] dubBufTexIds = new int[2][MAX_DUBBUF_FRAMES];
    final SimpleTextureSource[][] dubBufSources = new SimpleTextureSource[2][MAX_DUBBUF_FRAMES];
    int dubBufFrames = 96;
    int dubBufWritePage = 0;
    int dubBufReadPage = 1;
    int dubBufWriteIndex = 0;
    int dubBufPlayIndex = -1;
    boolean dubBufEnabled = false;
    boolean dubBufReadReady = false;
    boolean dubBufWriteReady = false;
    boolean dubBufFirstPlaybackReported = false;
    long dubBufLastCameraTimestampNs = -1L;
    long dubBufNextCaptureTimestampNs = -1L;
    int dubBufPlayCycle = -1;
    int dubBufRecordCycle = 1;

    // Camera2 preview arrives in sensor orientation. Apply this once at the
    // camera source so live preview and every effect inherit the same image.
    static final float CAMERA_LANDSCAPE_ROTATION_DEGREES = -90.0f;
    static final float CAMERA_PORTRAIT_ROTATION_DEGREES = -180.0f;
    volatile boolean portraitOrientation = false;
    final float[] rawCameraMatrix = new float[16];
    final float[] cameraCorrectionMatrix = new float[16];

    // Stutter is the first circular-history effect. Recording never pauses.
    static final int HISTORY_WIDTH = 1280;
    static final int HISTORY_HEIGHT = 720;
    static final int HISTORY_FPS = 60;
    static final int HISTORY_FRAMES = 120;       // two seconds
    int stutterTimeMs = 1000;
    int stutterSlices = 4;
    final int[] historyTexIds = new int[HISTORY_FRAMES];
    final SimpleTextureSource[] historySources = new SimpleTextureSource[HISTORY_FRAMES];
    long historyNewestSequence = -1L;
    int historyCount = 0;
    long historyLastCameraTimestampNs = -1L;
    boolean historyEnabled = false;
    boolean stutterEnabled = false;
    boolean stutterFirstPlaybackReported = false;
    int stutterOutputIndex = 0;
    int stutterCycle = 0;
    long stutterAnchorNewestSequence = -1L;
    boolean fastEnabled = false;
    boolean fastFirstPlaybackReported = false;
    int fastTimeMs = 1000;
    float fastSpeed = 2.0f;
    int fastOutputIndex = 0;
    int fastCycle = 0;
    long fastAnchorNewestSequence = -1L;

    RamFrameBuffer ramBuffer;
    final int[] ramYuvTexIds = new int[3];
    java.nio.ByteBuffer ramYUpload, ramUUpload, ramVUpload;
    int ramUploadWidth, ramUploadHeight;
    long ramOffsetMs;
    boolean ramVisible;

    int freezeTexId;
    int freezeWidth;
    int freezeHeight;
    SimpleTextureSource freezeSource;

    SurfaceTexture cameraSurfaceTexture;
    final SurfaceTexture[] decoderSurfaceTextures = new SurfaceTexture[2];
    final Surface[] decoderSurfaces = new Surface[2];
    SimpleTextureSource cameraSource;
    final SimpleTextureSource[] decoderSources = new SimpleTextureSource[2];
    TextureSource currentSource;
    int visibleDecoderSlot = -1;
    volatile float playbackGain = 1.0f;
    int screenW = 1, screenH = 1;
    int drawCount = 0, cameraFrameCount = 0;
    final int[] decoderFrameCounts = new int[2];
    long startNs = 0, lastDrawNs = 0;
    long cameraFpsWindowStartNs = 0L;
    int cameraFpsWindowFrames = 0;
    volatile double measuredCameraFps = 0.0;
    volatile long maxDrawGapNs = 0;
    String report = "Ready";

    final float[] vertices = {
            -1f,-1f,0f,0f, 1f,-1f,1f,0f,
            -1f,1f,0f,1f, 1f,1f,1f,1f
    };
    final java.nio.FloatBuffer vertexBuffer;

    public GLRenderer(GLView view) {
        this.view = view;
        java.nio.ByteBuffer bb = java.nio.ByteBuffer.allocateDirect(vertices.length * 4);
        bb.order(java.nio.ByteOrder.nativeOrder());
        vertexBuffer = bb.asFloatBuffer();
        vertexBuffer.put(vertices).position(0);
    }

    @Override public void onSurfaceCreated(javax.microedition.khronos.opengles.GL10 gl,
                                           javax.microedition.khronos.egl.EGLConfig config) {
        extProgram = makeProgram(VERT, FRAG_EXT);
        tex2dProgram = makeProgram(VERT, FRAG_2D);
        ramYuvProgram = makeProgram(VERT, FRAG_YUV);
        cameraTexId = makeExternalTexture();
        cameraSource = new SimpleTextureSource(cameraTexId, GL_TEXTURE_EXTERNAL_OES);
        updateCameraCorrectionMatrix();
        currentSource = cameraSource;
        cameraSurfaceTexture = new SurfaceTexture(cameraTexId);
        cameraSurfaceTexture.setOnFrameAvailableListener(st -> view.queueEvent(this::onCameraFrameAvailable));
        for (int slot = 0; slot < 2; slot++) {
            final int s = slot;
            decoderTexIds[slot] = makeExternalTexture();
            decoderSources[slot] = new SimpleTextureSource(decoderTexIds[slot], GL_TEXTURE_EXTERNAL_OES);
            decoderSurfaceTextures[slot] = new SurfaceTexture(decoderTexIds[slot]);
            decoderSurfaceTextures[slot].setOnFrameAvailableListener(st -> view.queueEvent(() -> onDecoderFrameAvailable(s)));
            decoderSurfaces[slot] = new Surface(decoderSurfaceTextures[slot]);
        }
        int[] fbo = new int[1];
        GLES20.glGenFramebuffers(1, fbo, 0);
        reverseFboId = fbo[0];
        startNs = System.nanoTime();
        if (view.listener != null) view.listener.onReady(cameraSurfaceTexture);
        glCheck("onSurfaceCreated");
    }

    public void setPortraitOrientation(boolean portrait) {
        portraitOrientation = portrait;
    }

    private void updateCameraCorrectionMatrix() {
        float degrees = portraitOrientation
                ? CAMERA_PORTRAIT_ROTATION_DEGREES
                : CAMERA_LANDSCAPE_ROTATION_DEGREES;
        android.opengl.Matrix.setIdentityM(cameraCorrectionMatrix, 0);
        android.opengl.Matrix.translateM(cameraCorrectionMatrix, 0, 0.5f, 0.5f, 0f);
        android.opengl.Matrix.rotateM(cameraCorrectionMatrix, 0,
                degrees, 0f, 0f, 1f);
        android.opengl.Matrix.translateM(cameraCorrectionMatrix, 0, -0.5f, -0.5f, 0f);
        TraceLog.i("Camera texture orientation portrait=" + portraitOrientation +
                " rotation=" + degrees);
    }

    @Override public void onSurfaceChanged(javax.microedition.khronos.opengles.GL10 gl, int w, int h) {
        screenW = Math.max(1, w); screenH = Math.max(1, h);
        GLES20.glViewport(0, 0, screenW, screenH);
        if (freezeTexId != 0 && (freezeWidth != screenW || freezeHeight != screenH)) releaseFreezeTexture();
    }

    @Override public void onDrawFrame(javax.microedition.khronos.opengles.GL10 gl) {
        drawCount++;
        long nowNs = System.nanoTime();
        if (lastDrawNs > 0) {
            long gap = nowNs - lastDrawNs;
            if (gap > maxDrawGapNs) {
                maxDrawGapNs = gap;
                if (gap >= 50_000_000L) TraceLog.i("HUD draw hitch gapMs=" + gap/1_000_000L + " source=" + stateText());
            }
        }
        lastDrawNs = nowNs;
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        GLES20.glViewport(0, 0, screenW, screenH);
        GLES20.glClearColor(0f,0f,0f,1f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        if (ramVisible) drawRamYuv();
        else drawTexture(currentSource);
        glCheck("onDrawFrame");
    }

    void onCameraFrameAvailable() {
        try {
            cameraSurfaceTexture.updateTexImage();
            cameraSurfaceTexture.getTransformMatrix(rawCameraMatrix);
            android.opengl.Matrix.multiplyMM(cameraSource.matrix, 0,
                    rawCameraMatrix, 0, cameraCorrectionMatrix, 0);
            cameraFrameCount++;
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
        } catch (Exception e) {
            android.util.Log.e("SlowMo240", "camera updateTexImage error: " + e);
        }
        view.requestCameraRender();
    }

    void onDecoderFrameAvailable(int slot) {
        try {
            decoderSurfaceTextures[slot].updateTexImage();
            decoderSurfaceTextures[slot].getTransformMatrix(decoderSources[slot].matrix);
            decoderFrameCounts[slot]++;
            if (pendingCaptureFrame[slot] >= 0) {
                int frameIndex = pendingCaptureFrame[slot];
                Runnable callback = pendingCaptureCallbacks[slot];
                pendingCaptureFrame[slot] = -1;
                pendingCaptureCallbacks[slot] = null;
                copyDecoderFrameToBank(slot, frameIndex);
                if (callback != null) callback.run();
            } else view.onDecoderTextureFrameAvailable(slot, decoderFrameCounts[slot]);
        } catch (Exception e) {
            android.util.Log.e("SlowMo240", "decoder " + slot + " updateTexImage error: " + e);
        }
        view.requestCameraRender();
    }

    public Surface getDecoderSurface(int slot) {
        if (slot < 0 || slot > 1) throw new IllegalArgumentException("Invalid decoder slot " + slot);
        return decoderSurfaces[slot];
    }

    public void captureLiveFrameToFreezeTexture() {
        ensureFreezeTexture();
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, reverseFboId);
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                GLES20.GL_TEXTURE_2D, freezeTexId, 0);
        int status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER);
        if (status != GLES20.GL_FRAMEBUFFER_COMPLETE)
            throw new IllegalStateException("Freeze FBO incomplete 0x" + Integer.toHexString(status));
        GLES20.glViewport(0, 0, freezeWidth, freezeHeight);
        GLES20.glClearColor(0f,0f,0f,1f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        drawTextureWithGain(cameraSource, 1.0f);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        GLES20.glViewport(0, 0, screenW, screenH);
        visibleDecoderSlot = -2;
        currentSource = freezeSource;
        report = "Freeze frame";
        glCheck("captureLiveFrameToFreezeTexture");
    }

    private void ensureFreezeTexture() {
        if (freezeTexId != 0 && freezeWidth == screenW && freezeHeight == screenH) return;
        releaseFreezeTexture();
        freezeWidth = Math.max(1, screenW);
        freezeHeight = Math.max(1, screenH);
        freezeTexId = make2dTexture();
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, freezeTexId);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA,
                freezeWidth, freezeHeight, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);
        freezeSource = new SimpleTextureSource(freezeTexId, GLES20.GL_TEXTURE_2D);
        android.opengl.Matrix.setIdentityM(freezeSource.matrix, 0);
    }

    public void releaseFreezeTexture() {
        if (freezeTexId != 0) {
            int[] ids = {freezeTexId};
            GLES20.glDeleteTextures(1, ids, 0);
        }
        if (currentSource == freezeSource) currentSource = cameraSource;
        freezeTexId = 0; freezeWidth = 0; freezeHeight = 0; freezeSource = null;
        if (visibleDecoderSlot == -2) visibleDecoderSlot = -1;
    }

    public void beginReverseBank(int slot, int width, int height, int maxFrames) {
        if (slot < 0 || slot > 1) throw new IllegalArgumentException("Invalid reverse slot " + slot);
        releaseReverseBank(slot);
        reverseBankWidths[slot] = width; reverseBankHeights[slot] = height;
        reverseBankCounts[slot] = Math.min(maxFrames, MAX_REVERSE_FRAMES);
        decoderSurfaceTextures[slot].setDefaultBufferSize(width, height);
        for (int i=0;i<reverseBankCounts[slot];i++) {
            int texId = make2dTexture();
            reverseBankTexIds[slot][i] = texId;
            reverseBankSources[slot][i] = new SimpleTextureSource(texId, GLES20.GL_TEXTURE_2D);
            android.opengl.Matrix.setIdentityM(reverseBankSources[slot][i].matrix, 0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId);
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D,0,GLES20.GL_RGBA,width,height,0,
                    GLES20.GL_RGBA,GLES20.GL_UNSIGNED_BYTE,null);
        }
        glCheck("beginReverseBank " + slot);
    }

    public void armReverseCapture(int slot, int frameIndex, Runnable callback) {
        if (slot < 0 || slot > 1) throw new IllegalArgumentException("Invalid reverse slot " + slot);
        if (frameIndex < 0 || frameIndex >= reverseBankCounts[slot]) throw new IllegalArgumentException("Invalid reverse frame " + frameIndex);
        pendingCaptureFrame[slot] = frameIndex;
        pendingCaptureCallbacks[slot] = callback;
    }

    public void showReverseFrame(int slot, int frameIndex) {
        if (slot < 0 || slot > 1) throw new IllegalArgumentException("Invalid reverse slot " + slot);
        if (frameIndex < 0 || frameIndex >= reverseBankCounts[slot] || reverseBankSources[slot][frameIndex] == null)
            throw new IllegalArgumentException("Invalid reverse frame " + frameIndex);
        visibleDecoderSlot = slot;
        currentSource = reverseBankSources[slot][frameIndex];
        report = "Showing reverse slot " + slot + " frame " + frameIndex;
    }

    private void copyDecoderFrameToBank(int slot, int frameIndex) {
        int width = reverseBankWidths[slot], height = reverseBankHeights[slot];
        int texId = reverseBankTexIds[slot][frameIndex];
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, reverseFboId);
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER,GLES20.GL_COLOR_ATTACHMENT0,
                GLES20.GL_TEXTURE_2D,texId,0);
        int status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER);
        if (status != GLES20.GL_FRAMEBUFFER_COMPLETE)
            throw new IllegalStateException("Reverse FBO incomplete 0x" + Integer.toHexString(status));
        GLES20.glViewport(0,0,width,height);
        GLES20.glClearColor(0f,0f,0f,1f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        drawTextureWithGain(decoderSources[slot],1.0f);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER,0);
        GLES20.glViewport(0,0,screenW,screenH);
        glCheck("copyDecoderFrameToBank " + slot + ":" + frameIndex);
    }

    private void releaseReverseBank(int slot) {
        int count = reverseBankCounts[slot];
        if (count > 0) {
            int[] ids = new int[count]; int n=0;
            for (int i=0;i<count;i++) {
                if (reverseBankTexIds[slot][i] != 0) ids[n++] = reverseBankTexIds[slot][i];
                reverseBankTexIds[slot][i] = 0; reverseBankSources[slot][i] = null;
            }
            if (n > 0) GLES20.glDeleteTextures(n,ids,0);
        }
        reverseBankCounts[slot]=0; pendingCaptureFrame[slot]=-1; pendingCaptureCallbacks[slot]=null;
    }


    public void beginDubBufReverse(int playbackMs) {
        releaseDubBufReverse();
        // Reverse tape follows the unique preview rate observed on this device.
        // Capture and playback both use 30 samples/s.
        dubBufFrames = Math.max(15,
                Math.min(MAX_DUBBUF_FRAMES, playbackMs * 30 / 1000));
        for (int page = 0; page < 2; page++) {
            for (int i = 0; i < dubBufFrames; i++) {
                int id = make2dTexture();
                dubBufTexIds[page][i] = id;
                dubBufSources[page][i] = new SimpleTextureSource(id, GLES20.GL_TEXTURE_2D);
                android.opengl.Matrix.setIdentityM(dubBufSources[page][i].matrix, 0);
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, id);
                GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA,
                        1280, 720, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);
            }
        }
        dubBufWritePage = 0; dubBufReadPage = 1; dubBufWriteIndex = 0;
        dubBufPlayIndex = -1; dubBufReadReady = false; dubBufWriteReady = false; dubBufEnabled = true;
        dubBufFirstPlaybackReported = false; dubBufLastCameraTimestampNs = -1L;
        dubBufNextCaptureTimestampNs = -1L;
        dubBufPlayCycle = -1; dubBufRecordCycle = 1;
        showCameraOutput();
        TraceLog.i("DubBuf allocated frames=" + dubBufFrames + " size=1280x720");
    }

    private void captureCameraFrameToDubBuf() {
        long ts = cameraSurfaceTexture.getTimestamp();
        if (ts == dubBufLastCameraTimestampNs) return;
        dubBufLastCameraTimestampNs = ts;
        final long intervalNs = 1_000_000_000L / 30L;
        if (dubBufNextCaptureTimestampNs < 0L)
            dubBufNextCaptureTimestampNs = ts;
        if (ts < dubBufNextCaptureTimestampNs) return;
        do {
            dubBufNextCaptureTimestampNs += intervalNs;
        } while (dubBufNextCaptureTimestampNs <= ts);
        if (dubBufWriteReady || dubBufWriteIndex >= dubBufFrames) return;
        int texId = dubBufTexIds[dubBufWritePage][dubBufWriteIndex];
        if (texId == 0) return;
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, reverseFboId);
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                GLES20.GL_TEXTURE_2D, texId, 0);
        GLES20.glViewport(0, 0, 1280, 720);
        drawTextureWithGain(cameraSource, 1.0f);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        GLES20.glViewport(0, 0, screenW, screenH);
        dubBufWriteIndex++;
        if (dubBufWriteIndex >= dubBufFrames) {
            if (!dubBufReadReady) {
                int oldRead = dubBufReadPage;
                dubBufReadPage = dubBufWritePage;
                dubBufWritePage = oldRead;
                dubBufWriteIndex = 0;
                dubBufReadReady = true;
                dubBufPlayIndex = dubBufFrames - 1;
                dubBufPlayCycle = 1;
                dubBufRecordCycle = 2;
                TraceLog.i("DubBuf first page ready read=" + dubBufReadPage +
                        " write=" + dubBufWritePage + " playCycle=" + dubBufPlayCycle +
                        " recordCycle=" + dubBufRecordCycle);
            } else {
                dubBufWriteReady = true;
                TraceLog.i("DubBuf next page ready page=" + dubBufWritePage);
            }
        }
    }

    public boolean advanceDubBufPlayback() {
        if (!dubBufEnabled || !dubBufReadReady || dubBufPlayIndex < 0) return false;
        int sourceIndex = dubBufPlayIndex;
        currentSource = dubBufSources[dubBufReadPage][sourceIndex];
        dubBufPlayIndex--;
        visibleDecoderSlot = -3;
        boolean first = !dubBufFirstPlaybackReported;
        dubBufFirstPlaybackReported = true;
        if (dubBufPlayIndex < 0) {
            if (dubBufWriteReady) {
                int oldRead = dubBufReadPage;
                dubBufReadPage = dubBufWritePage;
                dubBufWritePage = oldRead;
                dubBufWriteIndex = 0;
                dubBufWriteReady = false;
                dubBufPlayIndex = dubBufFrames - 1;
                dubBufPlayCycle = dubBufRecordCycle;
                dubBufRecordCycle = dubBufPlayCycle + 1;
                TraceLog.i("DubBuf swap read=" + dubBufReadPage + " write=" + dubBufWritePage +
                        " playCycle=" + dubBufPlayCycle + " recordCycle=" + dubBufRecordCycle);
            } else {
                dubBufPlayIndex = dubBufFrames - 1; // emergency repeat only
                TraceLog.i("DubBuf writer not ready; repeating page writeIndex=" + dubBufWriteIndex);
            }
        }
        return first;
    }

    public void releaseDubBufReverse() {
        dubBufEnabled = false;
        for (int p = 0; p < 2; p++) {
            int[] ids = new int[MAX_DUBBUF_FRAMES]; int n = 0;
            for (int i = 0; i < MAX_DUBBUF_FRAMES; i++) {
                if (dubBufTexIds[p][i] != 0) ids[n++] = dubBufTexIds[p][i];
                dubBufTexIds[p][i] = 0; dubBufSources[p][i] = null;
            }
            if (n > 0) GLES20.glDeleteTextures(n, ids, 0);
        }
        dubBufPlayCycle = -1;
        dubBufRecordCycle = -1;
        if (visibleDecoderSlot == -3) showCameraOutput();
    }

    public int getDubBufPlayCycle() { return dubBufPlayCycle; }
    public int getDubBufRecordCycle() { return dubBufRecordCycle; }

    public void beginStutter() {
        releaseStutterHistory();
        for (int i = 0; i < HISTORY_FRAMES; i++) {
            int id = make2dTexture();
            historyTexIds[i] = id;
            historySources[i] = new SimpleTextureSource(id, GLES20.GL_TEXTURE_2D);
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
        stutterEnabled = true;
        stutterFirstPlaybackReported = false;
        stutterOutputIndex = 0;
        stutterCycle = 0;
        stutterAnchorNewestSequence = -1L;
        showCameraOutput();
        TraceLog.i("Stutter history allocated frames=" + HISTORY_FRAMES +
                " size=" + HISTORY_WIDTH + "x" + HISTORY_HEIGHT);
    }

    private void captureCameraFrameToHistory() {
        long ts = cameraSurfaceTexture.getTimestamp();
        if (ts == historyLastCameraTimestampNs) return;
        historyLastCameraTimestampNs = ts;

        long sequence = historyNewestSequence + 1L;
        int slot = (int) (sequence % HISTORY_FRAMES);
        int texId = historyTexIds[slot];
        if (texId == 0) return;

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, reverseFboId);
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER,
                GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, texId, 0);
        int status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER);
        if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) {
            throw new IllegalStateException("History FBO incomplete 0x" +
                    Integer.toHexString(status));
        }
        GLES20.glViewport(0, 0, HISTORY_WIDTH, HISTORY_HEIGHT);
        drawTextureWithGain(cameraSource, 1.0f);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        GLES20.glViewport(0, 0, screenW, screenH);

        historyNewestSequence = sequence;
        if (historyCount < HISTORY_FRAMES) historyCount++;
    }

    public boolean advanceStutterPlayback() {
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
                sliceFrames + 1L + frameInSlice;

        long oldest = historyNewestSequence - historyCount + 1L;
        if (sequence < oldest || sequence > historyNewestSequence) return false;

        int slot = (int) (sequence % HISTORY_FRAMES);
        currentSource = historySources[slot];
        visibleDecoderSlot = -4;
        report = "Stutter cycle " + (stutterCycle + 1) + " pass " + (pass + 1);

        boolean first = !stutterFirstPlaybackReported;
        stutterFirstPlaybackReported = true;

        stutterOutputIndex++;
        if (stutterOutputIndex >= sliceFrames * stutterSlices) {
            stutterOutputIndex = 0;
            stutterAnchorNewestSequence = -1L;
            stutterCycle++;
            TraceLog.i("Stutter cycle complete=" + stutterCycle);
        }
        return first;
    }

    public void setStutterTimeMs(int timeMs) {
        stutterTimeMs = Math.max(200, Math.min(2000, timeMs));
        stutterOutputIndex = 0; stutterAnchorNewestSequence = -1L;
        TraceLog.i("Stutter Time=" + stutterTimeMs + "ms");
    }
    public void setStutterSlices(int slices) {
        stutterSlices = Math.max(1, Math.min(12, slices));
        stutterOutputIndex = 0; stutterAnchorNewestSequence = -1L;
        TraceLog.i("Stutter Slices=" + stutterSlices);
    }
    public int getStutterCycle() { return stutterCycle; }


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

        int maxSourceOffset = Math.max(0,
                Math.round((cycleFrames - 1) * fastSpeed));
        int sourceFrames = maxSourceOffset + 1;

        if (sourceFrames > HISTORY_FRAMES || historyCount < sourceFrames)
            return false;

        if (fastOutputIndex == 0 || fastAnchorNewestSequence < 0L)
            fastAnchorNewestSequence = historyNewestSequence;

        long firstSequence = fastAnchorNewestSequence - maxSourceOffset;
        long oldest = historyNewestSequence - historyCount + 1L;
        if (firstSequence < oldest) return false;

        long sequence = firstSequence +
                Math.round(fastOutputIndex * fastSpeed);
        if (sequence > fastAnchorNewestSequence)
            sequence = fastAnchorNewestSequence;

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
            TraceLog.i("Fast cycle complete=" + fastCycle +
                    " sourceFrames=" + sourceFrames +
                    " factor=" + fastSpeed);
        }
        return first;
    }

    public void setFastTimeMs(int timeMs) {
        fastTimeMs = Math.max(100, Math.min(2000, timeMs));
        if (fastSpeed > 1.0f &&
                fastSpeed * fastTimeMs > 2000.0f)
            fastSpeed = 2000.0f / fastTimeMs;
        fastOutputIndex = 0;
        fastAnchorNewestSequence = -1L;
        TraceLog.i("Fast Time=" + fastTimeMs + "ms speed=" + fastSpeed);
    }

    public void setFastSpeed(float speed) {
        fastSpeed = Math.max(1.0f, Math.min(4.0f, speed));
        if (fastSpeed > 1.0f &&
                fastSpeed * fastTimeMs > 2000.0f)
            fastTimeMs = Math.max(100,
                    Math.round(2000.0f / fastSpeed));
        fastOutputIndex = 0;
        fastAnchorNewestSequence = -1L;
        TraceLog.i("Fast Speed=" + fastSpeed + "x timeMs=" + fastTimeMs);
    }

    public int getFastCycle() { return fastCycle; }

    public void releaseStutterHistory() {
        historyEnabled = false;
        stutterEnabled = false;
        fastEnabled = false;
        int[] ids = new int[HISTORY_FRAMES];
        int n = 0;
        for (int i = 0; i < HISTORY_FRAMES; i++) {
            if (historyTexIds[i] != 0) ids[n++] = historyTexIds[i];
            historyTexIds[i] = 0;
            historySources[i] = null;
        }
        if (n > 0) GLES20.glDeleteTextures(n, ids, 0);
        historyNewestSequence = -1L;
        historyCount = 0;
        stutterOutputIndex = 0;
        stutterAnchorNewestSequence = -1L;
        fastOutputIndex = 0;
        fastAnchorNewestSequence = -1L;
        if (visibleDecoderSlot == -4 || visibleDecoderSlot == -5)
            showCameraOutput();
    }

    public void showDecoderSlot(int slot) {
        if (slot < 0 || slot > 1) throw new IllegalArgumentException("Invalid decoder slot " + slot);
        visibleDecoderSlot = slot; currentSource = decoderSources[slot]; report = "Showing decoder slot " + slot;
    }

    public void showCameraOutput() {
        ramVisible = false;
        visibleDecoderSlot = -1; currentSource = cameraSource; report = "Showing camera output";
    }

    public void setRamBuffer(RamFrameBuffer buffer) {
        ramBuffer = buffer;
    }

    public void showRamOffsetMs(long offsetMs) {
        if (ramBuffer == null || ramBuffer.getCount() <= 0) {
            showCameraOutput();
            return;
        }
        ramOffsetMs = Math.min(0L, offsetMs);
        ramVisible = true;
        visibleDecoderSlot = -6;
        uploadRamFrame();
        report = "RAM Scrub " + ramOffsetMs + " ms";
    }

    private void ensureRamTextures(int width, int height) {
        if (width <= 0 || height <= 0) return;
        if (ramYuvTexIds[0] != 0 &&
                ramUploadWidth == width && ramUploadHeight == height) return;

        if (ramYuvTexIds[0] != 0) {
            GLES20.glDeleteTextures(3, ramYuvTexIds, 0);
            ramYuvTexIds[0] = ramYuvTexIds[1] = ramYuvTexIds[2] = 0;
        }

        GLES20.glGenTextures(3, ramYuvTexIds, 0);
        int[] widths = {width, width / 2, width / 2};
        int[] heights = {height, height / 2, height / 2};
        for (int i = 0; i < 3; i++) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ramYuvTexIds[i]);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_LUMINANCE,
                    widths[i], heights[i], 0,
                    GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE, null);
        }

        ramYUpload = java.nio.ByteBuffer.allocateDirect(width * height);
        ramUUpload = java.nio.ByteBuffer.allocateDirect(width * height / 4);
        ramVUpload = java.nio.ByteBuffer.allocateDirect(width * height / 4);
        ramUploadWidth = width;
        ramUploadHeight = height;
    }

    private void uploadRamFrame() {
        if (ramBuffer == null) return;
        int width = ramBuffer.getWidth();
        int height = ramBuffer.getHeight();
        if (width <= 0 || height <= 0) return;
        ensureRamTextures(width, height);

        if (!ramBuffer.copyFrameAtOffsetMs(
                ramOffsetMs, ramYUpload, ramUUpload, ramVUpload)) return;

        java.nio.ByteBuffer[] data = {ramYUpload, ramUUpload, ramVUpload};
        int[] widths = {width, width / 2, width / 2};
        int[] heights = {height, height / 2, height / 2};
        for (int i = 0; i < 3; i++) {
            data[i].position(0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ramYuvTexIds[i]);
            GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0,
                    widths[i], heights[i],
                    GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE, data[i]);
        }
    }

    private void drawRamYuv() {
        if (ramYuvTexIds[0] == 0) {
            drawTexture(cameraSource);
            return;
        }
        GLES20.glUseProgram(ramYuvProgram);
        int aPos = GLES20.glGetAttribLocation(ramYuvProgram, "aPosition");
        int aTex = GLES20.glGetAttribLocation(ramYuvProgram, "aTexCoord");
        int uMatrix = GLES20.glGetUniformLocation(ramYuvProgram, "uTexMatrix");

        vertexBuffer.position(0);
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 16, vertexBuffer);
        GLES20.glEnableVertexAttribArray(aPos);
        vertexBuffer.position(2);
        GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 16, vertexBuffer);
        GLES20.glEnableVertexAttribArray(aTex);
        GLES20.glUniformMatrix4fv(uMatrix, 1, false, cameraSource.transform(), 0);

        int[] uniforms = {
                GLES20.glGetUniformLocation(ramYuvProgram, "sY"),
                GLES20.glGetUniformLocation(ramYuvProgram, "sU"),
                GLES20.glGetUniformLocation(ramYuvProgram, "sV")
        };
        for (int i = 0; i < 3; i++) {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + i);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ramYuvTexIds[i]);
            GLES20.glUniform1i(uniforms[i], i);
        }
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
    }
    public void setPlaybackGain(float gain) { playbackGain = gain; report = "Playback gain " + gain + "x"; }
    public String stateText() {
        if (visibleDecoderSlot == -6) return "SCRUB";
        if (visibleDecoderSlot == -5) return "FAST";
        if (visibleDecoderSlot == -4) return "STUTTER";
        if (visibleDecoderSlot == -3) return "DUBBUF";
        if (visibleDecoderSlot == -2) return "FREEZE";
        if (visibleDecoderSlot < 0) return "LIVE";
        return "PLAYBACK " + visibleDecoderSlot;
    }
    public long maxDrawGapMs() { return maxDrawGapNs / 1_000_000L; }
    public double cameraFps() { return measuredCameraFps; }
    public String stats() {
        double elapsed=(lastDrawNs-startNs)/1e9; double drawFps=elapsed>0?drawCount/elapsed:0;
        return "GL draws: "+drawCount+"\nGL draw fps: "+String.format("%.1f",drawFps)+
                "\nCamera frames: "+cameraFrameCount+"\nDecoder A frames: "+decoderFrameCounts[0]+
                "\nDecoder B frames: "+decoderFrameCounts[1]+"\nVisible slot: "+visibleDecoderSlot+
                "\nGain: "+playbackGain+"x\n"+report;
    }

    private boolean isVisibleReverseSource(TextureSource src,int slot) {
        if (slot<0||slot>1) return false;
        for(int i=0;i<reverseBankCounts[slot];i++) if(src==reverseBankSources[slot][i]) return true;
        return false;
    }
    void drawTexture(TextureSource src) {
        if (src == null) return;
        float gain=1.0f;
        if (visibleDecoderSlot>=0 && (src==decoderSources[visibleDecoderSlot] || isVisibleReverseSource(src,visibleDecoderSlot))) gain=playbackGain;
        drawTextureWithGain(src,gain);
    }
    void drawTextureWithGain(TextureSource src,float gain) {
        int program=src.textureTarget()==GL_TEXTURE_EXTERNAL_OES?extProgram:tex2dProgram;
        GLES20.glUseProgram(program);
        int aPos=GLES20.glGetAttribLocation(program,"aPosition");
        int aTex=GLES20.glGetAttribLocation(program,"aTexCoord");
        int uMatrix=GLES20.glGetUniformLocation(program,"uTexMatrix");
        int uGain=GLES20.glGetUniformLocation(program,"uGain");
        int uTexture=GLES20.glGetUniformLocation(program,"sTexture");
        vertexBuffer.position(0);
        GLES20.glVertexAttribPointer(aPos,2,GLES20.GL_FLOAT,false,16,vertexBuffer);
        GLES20.glEnableVertexAttribArray(aPos);
        vertexBuffer.position(2);
        GLES20.glVertexAttribPointer(aTex,2,GLES20.GL_FLOAT,false,16,vertexBuffer);
        GLES20.glEnableVertexAttribArray(aTex);
        GLES20.glUniformMatrix4fv(uMatrix,1,false,src.transform(),0);
        GLES20.glUniform1f(uGain,gain);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(src.textureTarget(),src.textureId());
        GLES20.glUniform1i(uTexture,0);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP,0,4);
    }

    int make2dTexture() {
        int[] tex=new int[1]; GLES20.glGenTextures(1,tex,0); GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,tex[0]);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_MIN_FILTER,GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_MAG_FILTER,GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_WRAP_S,GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_WRAP_T,GLES20.GL_CLAMP_TO_EDGE);
        return tex[0];
    }
    int makeExternalTexture() {
        int[] tex=new int[1]; GLES20.glGenTextures(1,tex,0); GLES20.glBindTexture(GL_TEXTURE_EXTERNAL_OES,tex[0]);
        GLES20.glTexParameteri(GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_MIN_FILTER,GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_MAG_FILTER,GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_WRAP_S,GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_WRAP_T,GLES20.GL_CLAMP_TO_EDGE);
        return tex[0];
    }
    int makeProgram(String vs,String fs) {
        int v=compile(GLES20.GL_VERTEX_SHADER,vs), f=compile(GLES20.GL_FRAGMENT_SHADER,fs), p=GLES20.glCreateProgram();
        GLES20.glAttachShader(p,v); GLES20.glAttachShader(p,f); GLES20.glLinkProgram(p);
        int[] ok=new int[1]; GLES20.glGetProgramiv(p,GLES20.GL_LINK_STATUS,ok,0);
        if(ok[0]==0) android.util.Log.e("SlowMo240","Program link: "+GLES20.glGetProgramInfoLog(p));
        return p;
    }
    int compile(int type,String src) {
        int s=GLES20.glCreateShader(type); GLES20.glShaderSource(s,src); GLES20.glCompileShader(s);
        int[] ok=new int[1]; GLES20.glGetShaderiv(s,GLES20.GL_COMPILE_STATUS,ok,0);
        if(ok[0]==0) android.util.Log.e("SlowMo240","Shader compile: "+GLES20.glGetShaderInfoLog(s));
        return s;
    }
    void glCheck(String where) {
        int err; while((err=GLES20.glGetError())!=GLES20.GL_NO_ERROR)
            android.util.Log.e("SlowMo240",where+" GL error 0x"+Integer.toHexString(err));
    }

    static final String VERT="attribute vec4 aPosition;\nattribute vec4 aTexCoord;\nuniform mat4 uTexMatrix;\nvarying vec2 vTexCoord;\nvoid main(){\n gl_Position=aPosition;\n vTexCoord=(uTexMatrix*aTexCoord).xy;\n}\n";
    static final String FRAG_EXT="#extension GL_OES_EGL_image_external : require\nprecision mediump float;\nuniform samplerExternalOES sTexture;\nuniform float uGain;\nvarying vec2 vTexCoord;\nvoid main(){\n vec4 c=texture2D(sTexture,vTexCoord);\n c.rgb=clamp(c.rgb*uGain,0.0,1.0);\n gl_FragColor=c;\n}\n";
    static final String FRAG_2D="precision mediump float;\nuniform sampler2D sTexture;\nuniform float uGain;\nvarying vec2 vTexCoord;\nvoid main(){\n vec4 c=texture2D(sTexture,vTexCoord);\n c.rgb=clamp(c.rgb*uGain,0.0,1.0);\n gl_FragColor=c;\n}\n";
    static final String FRAG_YUV =
            "precision mediump float;\n" +
            "uniform sampler2D sY;\n" +
            "uniform sampler2D sU;\n" +
            "uniform sampler2D sV;\n" +
            "varying vec2 vTexCoord;\n" +
            "void main(){\n" +
            " float y=texture2D(sY,vTexCoord).r;\n" +
            " float u=texture2D(sU,vTexCoord).r-0.5;\n" +
            " float v=texture2D(sV,vTexCoord).r-0.5;\n" +
            " vec3 rgb=vec3(y+1.402*v, y-0.344136*u-0.714136*v, y+1.772*u);\n" +
            " gl_FragColor=vec4(clamp(rgb,0.0,1.0),1.0);\n" +
            "}\n";

}

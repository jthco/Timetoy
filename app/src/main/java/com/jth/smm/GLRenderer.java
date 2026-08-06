// ============================================================
// Timetoy
// File: GLRenderer.java
// Version: v0.6.18
// Build: Live Freeze Texture
// Date: 2026-08-04
// ============================================================

package com.jth.smm;

import android.graphics.SurfaceTexture;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.view.Surface;

public class GLRenderer implements GLSurfaceView.Renderer {
    static final int GL_TEXTURE_EXTERNAL_OES = 0x8D65;
    final GLView view;
    int extProgram, tex2dProgram;
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
        cameraTexId = makeExternalTexture();
        cameraSource = new SimpleTextureSource(cameraTexId, GL_TEXTURE_EXTERNAL_OES);
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
        drawTexture(currentSource);
        glCheck("onDrawFrame");
    }

    void onCameraFrameAvailable() {
        try {
            cameraSurfaceTexture.updateTexImage();
            cameraSurfaceTexture.getTransformMatrix(cameraSource.matrix);
            cameraFrameCount++;
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

    public void showDecoderSlot(int slot) {
        if (slot < 0 || slot > 1) throw new IllegalArgumentException("Invalid decoder slot " + slot);
        visibleDecoderSlot = slot; currentSource = decoderSources[slot]; report = "Showing decoder slot " + slot;
    }

    public void showCameraOutput() {
        visibleDecoderSlot = -1; currentSource = cameraSource; report = "Showing camera output";
    }
    public void setPlaybackGain(float gain) { playbackGain = gain; report = "Playback gain " + gain + "x"; }
    public String stateText() {
        if (visibleDecoderSlot == -2) return "FREEZE";
        if (visibleDecoderSlot < 0) return "LIVE";
        return "PLAYBACK " + visibleDecoderSlot;
    }
    public long maxDrawGapMs() { return maxDrawGapNs / 1_000_000L; }
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
}

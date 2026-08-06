// ============================================================
// SlowMo Lens
// File: RenderEngine.java
// Version: v0.4.5
// Build: Capture Options
// Date: 2026-07-17
// ============================================================

package com.jth.smm;

import android.graphics.SurfaceTexture;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.view.Surface;

public class RenderEngine implements GLSurfaceView.Renderer {
    static final int GL_TEXTURE_EXTERNAL_OES = 0x8D65;

    final SlowMoGLView view;

    int extProgram;
    int tex2dProgram;

    int cameraTexId;
    final int[] decoderTexIds = new int[2];

    SurfaceTexture cameraSurfaceTexture;
    final SurfaceTexture[] decoderSurfaceTextures =
            new SurfaceTexture[2];

    final Surface[] decoderSurfaces =
            new Surface[2];

    SimpleTextureSource cameraSource;
    final SimpleTextureSource[] decoderSources =
            new SimpleTextureSource[2];

    TextureSource currentSource;
    int visibleDecoderSlot = -1;

    volatile float playbackGain = 1.0f;

    int screenW = 1;
    int screenH = 1;

    int drawCount = 0;
    int cameraFrameCount = 0;
    final int[] decoderFrameCounts = new int[2];


    long startNs = 0;
    long lastDrawNs = 0;

    String report = "Ready";

    final float[] vertices = {
            -1f, -1f, 0f, 0f,
             1f, -1f, 1f, 0f,
            -1f,  1f, 0f, 1f,
             1f,  1f, 1f, 1f
    };

    final java.nio.FloatBuffer vertexBuffer;

    public RenderEngine(SlowMoGLView view) {
        this.view = view;

        java.nio.ByteBuffer bb =
                java.nio.ByteBuffer.allocateDirect(
                        vertices.length * 4
                );

        bb.order(java.nio.ByteOrder.nativeOrder());

        vertexBuffer = bb.asFloatBuffer();
        vertexBuffer.put(vertices);
        vertexBuffer.position(0);
    }

    @Override
    public void onSurfaceCreated(
            javax.microedition.khronos.opengles.GL10 gl,
            javax.microedition.khronos.egl.EGLConfig config
    ) {
        extProgram = makeProgram(VERT, FRAG_EXT);
        tex2dProgram = makeProgram(VERT, FRAG_2D);

        cameraTexId = makeExternalTexture();

        cameraSource =
                new SimpleTextureSource(
                        cameraTexId,
                        GL_TEXTURE_EXTERNAL_OES
                );

        currentSource = cameraSource;

        cameraSurfaceTexture =
                new SurfaceTexture(cameraTexId);

        cameraSurfaceTexture.setOnFrameAvailableListener(
                st -> view.queueEvent(
                        this::onCameraFrameAvailable
                )
        );

        for (int slot = 0; slot < 2; slot++) {
            final int decoderSlot = slot;

            decoderTexIds[slot] = makeExternalTexture();

            decoderSources[slot] =
                    new SimpleTextureSource(
                            decoderTexIds[slot],
                            GL_TEXTURE_EXTERNAL_OES
                    );

            decoderSurfaceTextures[slot] =
                    new SurfaceTexture(
                            decoderTexIds[slot]
                    );

            decoderSurfaceTextures[slot]
                    .setOnFrameAvailableListener(
                            st -> view.queueEvent(
                                    () -> onDecoderFrameAvailable(
                                            decoderSlot
                                    )
                            )
                    );

            decoderSurfaces[slot] =
                    new Surface(
                            decoderSurfaceTextures[slot]
                    );
        }

        startNs = System.nanoTime();

        if (view.listener != null) {
            view.listener.onReady(
                    cameraSurfaceTexture
            );
        }

        glCheck("onSurfaceCreated");
    }

    @Override
    public void onSurfaceChanged(
            javax.microedition.khronos.opengles.GL10 gl,
            int w,
            int h
    ) {
        screenW = w;
        screenH = h;

        GLES20.glViewport(0, 0, w, h);
    }

    @Override
    public void onDrawFrame(
            javax.microedition.khronos.opengles.GL10 gl
    ) {
        drawCount++;
        lastDrawNs = System.nanoTime();

        GLES20.glBindFramebuffer(
                GLES20.GL_FRAMEBUFFER,
                0
        );

        GLES20.glViewport(
                0,
                0,
                screenW,
                screenH
        );

        GLES20.glClearColor(
                0f,
                0f,
                0f,
                1f
        );

        GLES20.glClear(
                GLES20.GL_COLOR_BUFFER_BIT
        );

        drawTexture(currentSource);

        glCheck("onDrawFrame");
    }

    void onCameraFrameAvailable() {
        try {
            cameraSurfaceTexture.updateTexImage();

            cameraSurfaceTexture.getTransformMatrix(
                    cameraSource.matrix
            );

            cameraFrameCount++;

        } catch (Exception e) {
            android.util.Log.e(
                    "SlowMo240",
                    "camera updateTexImage error: " + e
            );
        }

        view.requestCameraRender();
    }

    void onDecoderFrameAvailable(int slot) {
        try {
            decoderSurfaceTextures[slot]
                    .updateTexImage();

            decoderSurfaceTextures[slot]
                    .getTransformMatrix(
                            decoderSources[slot].matrix
                    );

            decoderFrameCounts[slot]++;

            view.onDecoderTextureFrameAvailable(
                    slot,
                    decoderFrameCounts[slot]
            );

        } catch (Exception e) {
            android.util.Log.e(
                    "SlowMo240",
                    "decoder " +
                            slot +
                            " updateTexImage error: " +
                            e
            );
        }

        view.requestCameraRender();
    }

    public Surface getDecoderSurface(int slot) {
        if (slot < 0 || slot > 1) {
            throw new IllegalArgumentException(
                    "Invalid decoder slot " + slot
            );
        }

        return decoderSurfaces[slot];
    }

    public void showDecoderSlot(int slot) {
        if (slot < 0 || slot > 1) {
            throw new IllegalArgumentException(
                    "Invalid decoder slot " + slot
            );
        }

        visibleDecoderSlot = slot;
        currentSource = decoderSources[slot];

        report =
                "Showing decoder slot " +
                        slot;
    }

    public void showCameraOutput() {
        visibleDecoderSlot = -1;
        currentSource = cameraSource;

        report = "Showing camera output";
    }

    public void setPlaybackGain(float gain) {
        playbackGain = gain;

        report =
                "Playback gain " +
                        gain +
                        "x";
    }

    public String stateText() {
        if (visibleDecoderSlot < 0) {
            return "LIVE";
        }

        return "PLAYBACK " +
                visibleDecoderSlot;
    }

    public String stats() {
        double elapsed =
                (lastDrawNs - startNs) /
                        1000000000.0;

        double drawFps =
                elapsed > 0
                        ? drawCount / elapsed
                        : 0;

        return "GL draws: " +
                drawCount +
                "\nGL draw fps: " +
                String.format("%.1f", drawFps) +
                "\nCamera frames: " +
                cameraFrameCount +
                "\nDecoder A frames: " +
                decoderFrameCounts[0] +
                "\nDecoder B frames: " +
                decoderFrameCounts[1] +
                "\nVisible slot: " +
                visibleDecoderSlot +
                "\nGain: " +
                playbackGain +
                "x" +
                "\n" +
                report;
    }

    void drawTexture(TextureSource src) {
        int program =
                src.textureTarget() ==
                        GL_TEXTURE_EXTERNAL_OES
                        ? extProgram
                        : tex2dProgram;

        GLES20.glUseProgram(program);

        int aPos =
                GLES20.glGetAttribLocation(
                        program,
                        "aPosition"
                );

        int aTex =
                GLES20.glGetAttribLocation(
                        program,
                        "aTexCoord"
                );

        int uMatrix =
                GLES20.glGetUniformLocation(
                        program,
                        "uTexMatrix"
                );

        int uGain =
                GLES20.glGetUniformLocation(
                        program,
                        "uGain"
                );

        vertexBuffer.position(0);

        GLES20.glVertexAttribPointer(
                aPos,
                2,
                GLES20.GL_FLOAT,
                false,
                16,
                vertexBuffer
        );

        GLES20.glEnableVertexAttribArray(aPos);

        vertexBuffer.position(2);

        GLES20.glVertexAttribPointer(
                aTex,
                2,
                GLES20.GL_FLOAT,
                false,
                16,
                vertexBuffer
        );

        GLES20.glEnableVertexAttribArray(aTex);

        GLES20.glUniformMatrix4fv(
                uMatrix,
                1,
                false,
                src.transform(),
                0
        );

        float gain =
                visibleDecoderSlot >= 0 &&
                        src ==
                                decoderSources[
                                        visibleDecoderSlot
                                ]
                        ? playbackGain
                        : 1.0f;

        GLES20.glUniform1f(
                uGain,
                gain
        );

        GLES20.glActiveTexture(
                GLES20.GL_TEXTURE0
        );

        GLES20.glBindTexture(
                src.textureTarget(),
                src.textureId()
        );

        GLES20.glDrawArrays(
                GLES20.GL_TRIANGLE_STRIP,
                0,
                4
        );
    }

    int makeExternalTexture() {
        int[] tex = new int[1];

        GLES20.glGenTextures(
                1,
                tex,
                0
        );

        GLES20.glBindTexture(
                GL_TEXTURE_EXTERNAL_OES,
                tex[0]
        );

        GLES20.glTexParameteri(
                GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_MIN_FILTER,
                GLES20.GL_LINEAR
        );

        GLES20.glTexParameteri(
                GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_MAG_FILTER,
                GLES20.GL_LINEAR
        );

        GLES20.glTexParameteri(
                GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_WRAP_S,
                GLES20.GL_CLAMP_TO_EDGE
        );

        GLES20.glTexParameteri(
                GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_WRAP_T,
                GLES20.GL_CLAMP_TO_EDGE
        );

        return tex[0];
    }

    int makeProgram(String vs, String fs) {
        int v =
                compile(
                        GLES20.GL_VERTEX_SHADER,
                        vs
                );

        int f =
                compile(
                        GLES20.GL_FRAGMENT_SHADER,
                        fs
                );

        int p =
                GLES20.glCreateProgram();

        GLES20.glAttachShader(p, v);
        GLES20.glAttachShader(p, f);
        GLES20.glLinkProgram(p);

        int[] ok = new int[1];

        GLES20.glGetProgramiv(
                p,
                GLES20.GL_LINK_STATUS,
                ok,
                0
        );

        if (ok[0] == 0) {
            android.util.Log.e(
                    "SlowMo240",
                    "Program link: " +
                            GLES20.glGetProgramInfoLog(p)
            );
        }

        return p;
    }

    int compile(int type, String src) {
        int s =
                GLES20.glCreateShader(type);

        GLES20.glShaderSource(s, src);
        GLES20.glCompileShader(s);

        int[] ok = new int[1];

        GLES20.glGetShaderiv(
                s,
                GLES20.GL_COMPILE_STATUS,
                ok,
                0
        );

        if (ok[0] == 0) {
            android.util.Log.e(
                    "SlowMo240",
                    "Shader compile: " +
                            GLES20.glGetShaderInfoLog(s)
            );
        }

        return s;
    }

    void glCheck(String where) {
        int err;

        while ((err = GLES20.glGetError()) !=
                GLES20.GL_NO_ERROR) {

            android.util.Log.e(
                    "SlowMo240",
                    where +
                            " GL error 0x" +
                            Integer.toHexString(err)
            );
        }
    }

    static final String VERT =
            "attribute vec4 aPosition;\n" +
            "attribute vec4 aTexCoord;\n" +
            "uniform mat4 uTexMatrix;\n" +
            "varying vec2 vTexCoord;\n" +
            "void main(){\n" +
            " gl_Position=aPosition;\n" +
            " vTexCoord=(uTexMatrix*aTexCoord).xy;\n" +
            "}\n";

    static final String FRAG_EXT =
            "#extension GL_OES_EGL_image_external : require\n" +
            "precision mediump float;\n" +
            "uniform samplerExternalOES sTexture;\n" +
            "uniform float uGain;\n" +
            "varying vec2 vTexCoord;\n" +
            "void main(){\n" +
            " vec4 c=texture2D(sTexture,vTexCoord);\n" +
            " c.rgb=clamp(c.rgb*uGain,0.0,1.0);\n" +
            " gl_FragColor=c;\n" +
            "}\n";

    static final String FRAG_2D =
            "precision mediump float;\n" +
            "uniform sampler2D sTexture;\n" +
            "uniform float uGain;\n" +
            "varying vec2 vTexCoord;\n" +
            "void main(){\n" +
            " vec4 c=texture2D(sTexture,vTexCoord);\n" +
            " c.rgb=clamp(c.rgb*uGain,0.0,1.0);\n" +
            " gl_FragColor=c;\n" +
            "}\n";
}

package com.jth.smm;

import android.opengl.GLES20;

public class FrameBuffer60 {
    static final int WIDTH = 1920;
    static final int HEIGHT = 1080;
    static final int COUNT = 60;

    int[] textures = new int[COUNT];
    int[] fbos = new int[COUNT];

    SimpleTextureSource[] sources = new SimpleTextureSource[COUNT];

    int captured = 0;
    long startNs = 0;
    long endNs = 0;
    long[] times = new long[COUNT];

    public void init() {
        GLES20.glGenTextures(COUNT, textures, 0);
        GLES20.glGenFramebuffers(COUNT, fbos, 0);

        for (int i = 0; i < COUNT; i++) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textures[i]);

            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

            GLES20.glTexImage2D(
                    GLES20.GL_TEXTURE_2D,
                    0,
                    GLES20.GL_RGBA,
                    WIDTH,
                    HEIGHT,
                    0,
                    GLES20.GL_RGBA,
                    GLES20.GL_UNSIGNED_BYTE,
                    null
            );

            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbos[i]);
            GLES20.glFramebufferTexture2D(
                    GLES20.GL_FRAMEBUFFER,
                    GLES20.GL_COLOR_ATTACHMENT0,
                    GLES20.GL_TEXTURE_2D,
                    textures[i],
                    0
            );

            sources[i] = new SimpleTextureSource(textures[i], GLES20.GL_TEXTURE_2D);
        }

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
    }

    public void reset() {
        captured = 0;
        startNs = 0;
        endNs = 0;
    }

    public boolean hasSpace() {
        return captured < COUNT;
    }

    public int nextIndex() {
        return captured;
    }

    public void markCaptured(long timeNs) {
        if (captured == 0) {
            startNs = timeNs;
        }

        times[captured] = timeNs;
        captured++;

        if (captured >= COUNT) {
            endNs = timeNs;
        }
    }

    public boolean isComplete() {
        return captured >= COUNT;
    }

    public int capturedCount() {
        return captured;
    }

    public int fbo(int i) {
        return fbos[i];
    }

    public TextureSource source(int i) {
        return sources[i];
    }

    public String report() {
        if (captured == 0) {
            return "Capture: empty";
        }

        if (!isComplete()) {
            return "Capture: " + captured + "/" + COUNT;
        }

        double totalMs = (endNs - startNs) / 1000000.0;
        double fps = totalMs > 0 ? ((COUNT - 1) * 1000.0 / totalMs) : 0;

        double minMs = 999999;
        double maxMs = 0;
        double sumMs = 0;

        for (int i = 1; i < COUNT; i++) {
            double dt = (times[i] - times[i - 1]) / 1000000.0;
            if (dt < minMs) minMs = dt;
            if (dt > maxMs) maxMs = dt;
            sumMs += dt;
        }

        double avgMs = sumMs / (COUNT - 1);

        return "Capture complete\n" +
                "Frames: " + COUNT + "\n" +
                "Duration: " + String.format("%.2f", totalMs) + " ms\n" +
                "Est FPS: " + String.format("%.1f", fps) + "\n" +
                "Avg dt: " + String.format("%.2f", avgMs) + " ms\n" +
                "Min dt: " + String.format("%.2f", minMs) + " ms\n" +
                "Max dt: " + String.format("%.2f", maxMs) + " ms";
    }
}
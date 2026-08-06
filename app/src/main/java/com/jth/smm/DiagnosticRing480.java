package com.jth.smm;

import android.opengl.GLES20;

public class DiagnosticRing480 {
    static final int WIDTH = 320;
    static final int HEIGHT = 180;
    static final int COUNT = 480;

    int[] textures = new int[COUNT];
    int[] fbos = new int[COUNT];

    int writeIndex = 0;
    long totalFrames = 0;

    long firstNs = 0;
    long lastNs = 0;
    long lastWrapNs = 0;
    double lastWrapMs = 0;

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
        }

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
    }

    public int currentFbo() {
        return fbos[writeIndex];
    }

    public void markWritten(long nowNs) {
        if (totalFrames == 0) {
            firstNs = nowNs;
            lastWrapNs = nowNs;
        }

        totalFrames++;
        lastNs = nowNs;
        writeIndex++;

        if (writeIndex >= COUNT) {
            writeIndex = 0;
            lastWrapMs = (nowNs - lastWrapNs) / 1000000.0;
            lastWrapNs = nowNs;
        }
    }

    public String report() {
        if (totalFrames == 0) {
            return "Ring480: empty";
        }

        double totalSec = (lastNs - firstNs) / 1000000000.0;
        double fps = totalSec > 0 ? totalFrames / totalSec : 0;

        return "Ring480 diagnostic\n" +
                "Total writes: " + totalFrames + "\n" +
                "Write index: " + writeIndex + "/480\n" +
                "FPS: " + String.format("%.1f", fps) + "\n" +
                "Last 480 duration: " + String.format("%.1f", lastWrapMs) + " ms";
    }
}
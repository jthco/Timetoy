package com.jth.smm;

import android.opengl.GLES20;
import android.opengl.Matrix;

public interface TextureSource {
    int textureId();
    int textureTarget();
    float[] transform();
}

class SimpleTextureSource implements TextureSource {
    int id;
    int target;
    float[] matrix = new float[16];

    SimpleTextureSource(int id, int target) {
        this.id = id;
        this.target = target;
        Matrix.setIdentityM(matrix, 0);
    }

    public int textureId() {
        return id;
    }

    public int textureTarget() {
        return target;
    }

    public float[] transform() {
        return matrix;
    }
}
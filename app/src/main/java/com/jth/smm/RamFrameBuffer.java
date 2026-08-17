// ============================================================
// Timetoy
// File: RamFrameBuffer.java
// Version: v0.6.33
// Build: C Engine Java bridge
// ============================================================
package com.jth.smm;

import android.media.Image;
import java.nio.ByteBuffer;
import java.util.Locale;

public final class RamFrameBuffer {
    static { System.loadLibrary("timetoy_c"); }

    private long handle=0L;
    private int width,height;
    private final int fps=60;
    private long fpsWindowStartNs;
    private int fpsWindowFrames;
    private double measuredFps;

    public void addImage(Image image) {
        if (image==null) return;
        if (handle==0L) {
            width=image.getWidth(); height=image.getHeight();
            handle=nativeCreate(width,height,fps);
            if (handle==0L) throw new OutOfMemoryError("C Engine native allocation failed");
            TraceLog.i("C Engine initialized "+width+"x"+height+"@"+fps+
                    " capacityFrames="+getCapacityFrames()+
                    " allocatedMiB="+getAllocatedMiB()+
                    " capacitySec="+String.format(Locale.US,"%.2f",getCapacityFrames()/(double)fps));
        }
        Image.Plane[] p=image.getPlanes(); if(p==null || p.length<3) return;
        ByteBuffer y=p[0].getBuffer(),u=p[1].getBuffer(),v=p[2].getBuffer();
        nativePush(handle,
                y,y.position(),p[0].getRowStride(),p[0].getPixelStride(),
                u,u.position(),p[1].getRowStride(),p[1].getPixelStride(),
                v,v.position(),p[2].getRowStride(),p[2].getPixelStride(),
                image.getTimestamp());
        long now=System.nanoTime();
        if(fpsWindowStartNs==0L) fpsWindowStartNs=now;
        fpsWindowFrames++;
        long span=now-fpsWindowStartNs;
        if(span>=500_000_000L) {
            measuredFps=fpsWindowFrames*1_000_000_000.0/span;
            fpsWindowStartNs=now; fpsWindowFrames=0;
        }
    }

    public boolean copyFrameAtOffsetMs(long offsetMs,ByteBuffer y,ByteBuffer u,ByteBuffer v) {
        if(handle==0L) return false;
        y.clear(); u.clear(); v.clear();
        return nativeCopyOffsetMs(handle,offsetMs,y,u,v);
    }
    public int getWidth(){return width;}
    public int getHeight(){return height;}
    public int getCount(){return handle==0L?0:nativeGetCount(handle);}
    public int getCapacityFrames(){return handle==0L?0:nativeGetCapacityFrames(handle);}
    public int getAllocatedMiB(){return handle==0L?0:nativeGetAllocatedMiB(handle);}
    public long getTotalFrames(){return handle==0L?0L:nativeGetTotalFrames(handle);}
    public double getMeasuredFps(){return measuredFps;}
    public double getHistorySeconds(){return handle==0L?0.0:nativeGetHistorySeconds(handle);}
    public void clear(){fpsWindowStartNs=0;fpsWindowFrames=0;measuredFps=0;if(handle!=0L)nativeClear(handle);}
    public void release(){if(handle!=0L){nativeDestroy(handle);handle=0L;}}
    @Override protected void finalize() throws Throwable {try{release();}finally{super.finalize();}}

    private static native long nativeCreate(int width,int height,int fps);
    private static native void nativeDestroy(long handle);
    private static native boolean nativePush(long handle,
            ByteBuffer y,int yo,int yrs,int yps,
            ByteBuffer u,int uo,int urs,int ups,
            ByteBuffer v,int vo,int vrs,int vps,long timestampNs);
    private static native boolean nativeCopyOffsetMs(long handle,long offsetMs,ByteBuffer y,ByteBuffer u,ByteBuffer v);
    private static native int nativeGetCount(long handle);
    private static native int nativeGetCapacityFrames(long handle);
    private static native int nativeGetAllocatedMiB(long handle);
    private static native long nativeGetTotalFrames(long handle);
    private static native double nativeGetHistorySeconds(long handle);
    private static native void nativeClear(long handle);
}

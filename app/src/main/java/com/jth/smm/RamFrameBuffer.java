// ============================================================
// Timetoy
// File: RamFrameBuffer.java
// Version: v0.6.32
// Build: RAM Engine / Scrub proof-of-concept
// Date: 2026-08-14
// ============================================================

package com.jth.smm;

import android.media.Image;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public final class RamFrameBuffer {
    private static final int[] BUDGET_MIB = {512, 384, 256, 128};

    private ByteBuffer storage;
    private int width, height, slotBytes, capacityFrames, writeIndex, count;
    private final int[] planeBytes = new int[3];
    private final int[] planeOffsets = new int[3];
    private final int[] rowStride = new int[3];
    private final int[] pixelStride = new int[3];
    private long[] timestampsNs;
    private long totalFrames;
    private long fpsWindowStartNs;
    private int fpsWindowFrames;
    private double measuredFps;
    private int allocatedMiB;

    public synchronized void addImage(Image image) {
        if (image == null) return;
        if (storage == null) initializeFrom(image);
        if (image.getWidth() != width || image.getHeight() != height) return;

        Image.Plane[] planes = image.getPlanes();
        if (planes == null || planes.length < 3) return;

        int slot = writeIndex;
        int base = slot * slotBytes;

        for (int p = 0; p < 3; p++) {
            ByteBuffer src = planes[p].getBuffer().duplicate();
            int n = Math.min(src.remaining(), planeBytes[p]);
            ByteBuffer dst = storage.duplicate();
            dst.position(base + planeOffsets[p]);
            dst.limit(base + planeOffsets[p] + n);
            ByteBuffer slice = src.slice();
            slice.limit(n);
            dst.put(slice);
        }

        timestampsNs[slot] = image.getTimestamp();
        writeIndex = (writeIndex + 1) % capacityFrames;
        if (count < capacityFrames) count++;
        totalFrames++;

        long now = System.nanoTime();
        if (fpsWindowStartNs == 0L) fpsWindowStartNs = now;
        fpsWindowFrames++;
        long span = now - fpsWindowStartNs;
        if (span >= 500_000_000L) {
            measuredFps = fpsWindowFrames * 1_000_000_000.0 / span;
            fpsWindowStartNs = now;
            fpsWindowFrames = 0;
        }
    }

    private void initializeFrom(Image image) {
        width = image.getWidth();
        height = image.getHeight();
        Image.Plane[] planes = image.getPlanes();

        int runningOffset = 0;
        for (int p = 0; p < 3; p++) {
            ByteBuffer b = planes[p].getBuffer().duplicate();
            planeBytes[p] = b.remaining();
            planeOffsets[p] = runningOffset;
            runningOffset += planeBytes[p];
            rowStride[p] = planes[p].getRowStride();
            pixelStride[p] = planes[p].getPixelStride();
        }
        slotBytes = runningOffset;

        OutOfMemoryError last = null;
        for (int budget : BUDGET_MIB) {
            try {
                long bytes = budget * 1024L * 1024L;
                int frames = (int)Math.max(1L, bytes / Math.max(1, slotBytes));
                long exactBytes = (long)frames * slotBytes;
                if (exactBytes > Integer.MAX_VALUE) {
                    frames = Integer.MAX_VALUE / slotBytes;
                    exactBytes = (long)frames * slotBytes;
                }
                storage = ByteBuffer.allocateDirect((int)exactBytes).order(ByteOrder.nativeOrder());
                capacityFrames = frames;
                allocatedMiB = (int)(exactBytes / (1024L * 1024L));
                break;
            } catch (OutOfMemoryError oom) {
                last = oom;
                storage = null;
                System.gc();
            }
        }
        if (storage == null) throw last != null ? last :
                new OutOfMemoryError("Could not allocate RAM frame buffer");

        timestampsNs = new long[capacityFrames];

        TraceLog.i("RAM initialized " + width + "x" + height +
                " slotBytes=" + slotBytes +
                " capacityFrames=" + capacityFrames +
                " allocatedMiB=" + allocatedMiB +
                " Y(row=" + rowStride[0] + ",pix=" + pixelStride[0] + ")" +
                " U(row=" + rowStride[1] + ",pix=" + pixelStride[1] + ")" +
                " V(row=" + rowStride[2] + ",pix=" + pixelStride[2] + ")");
    }

    public synchronized int getWidth() { return width; }
    public synchronized int getHeight() { return height; }
    public synchronized int getCount() { return count; }
    public synchronized int getCapacityFrames() { return capacityFrames; }
    public synchronized int getAllocatedMiB() { return allocatedMiB; }
    public synchronized long getTotalFrames() { return totalFrames; }
    public synchronized double getMeasuredFps() { return measuredFps; }

    public synchronized double getHistorySeconds() {
        if (count <= 1 || timestampsNs == null) return 0.0;
        int newest = mod(writeIndex - 1, capacityFrames);
        int oldest = count < capacityFrames ? 0 : writeIndex;
        long a = timestampsNs[oldest];
        long b = timestampsNs[newest];
        if (a > 0L && b >= a) return (b - a) / 1_000_000_000.0;
        return (count - 1) / 60.0;
    }

    public synchronized boolean copyFrameAtOffsetMs(
            long offsetMs, ByteBuffer yDst, ByteBuffer uDst, ByteBuffer vDst) {
        if (storage == null || count <= 0) return false;

        long framesBack = Math.round(Math.abs(offsetMs) * 60.0 / 1000.0);
        if (framesBack >= count) framesBack = count - 1L;

        int newest = mod(writeIndex - 1, capacityFrames);
        int slot = mod(newest - (int)framesBack, capacityFrames);
        int base = slot * slotBytes;

        yDst.clear(); uDst.clear(); vDst.clear();
        copyPlaneToPacked(base, 0, width, height, yDst);
        copyPlaneToPacked(base, 1, width / 2, height / 2, uDst);
        copyPlaneToPacked(base, 2, width / 2, height / 2, vDst);
        yDst.flip(); uDst.flip(); vDst.flip();
        return true;
    }

    private void copyPlaneToPacked(
            int frameBase, int plane, int cols, int rows, ByteBuffer dst) {
        ByteBuffer src = storage.duplicate();
        int planeBase = frameBase + planeOffsets[plane];
        int maxBytes = planeBytes[plane];
        int rs = rowStride[plane];
        int ps = pixelStride[plane];

        for (int y = 0; y < rows; y++) {
            int rowStart = y * rs;
            if (rowStart >= maxBytes) break;

            if (ps == 1) {
                int n = Math.min(cols, maxBytes - rowStart);
                ByteBuffer row = src.duplicate();
                row.position(planeBase + rowStart);
                row.limit(planeBase + rowStart + n);
                dst.put(row);
                for (int x = n; x < cols; x++) dst.put((byte)0);
            } else {
                for (int x = 0; x < cols; x++) {
                    int index = rowStart + x * ps;
                    dst.put(index < maxBytes ? src.get(planeBase + index) : (byte)0);
                }
            }
        }
    }

    public synchronized void clear() {
        writeIndex = 0; count = 0; totalFrames = 0;
        fpsWindowStartNs = 0; fpsWindowFrames = 0; measuredFps = 0.0;
        if (timestampsNs != null) java.util.Arrays.fill(timestampsNs, 0L);
    }

    private static int mod(int value, int modulus) {
        int r = value % modulus;
        return r < 0 ? r + modulus : r;
    }
}

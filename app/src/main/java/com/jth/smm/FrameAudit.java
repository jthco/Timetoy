// ============================================================
// SlowMo Lens
// File: FrameAudit.java
// Version: v0.6.5
// Build: Measured Capture Rate
// Date: 2026-07-26
// ============================================================
package com.jth.smm;

import android.media.MediaExtractor;
import android.media.MediaFormat;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/** Lightweight per-clip counters and timestamp summaries for CameraLab. */
public final class FrameAudit {
    private FrameAudit() {}

    private static final Object LOCK = new Object();
    private static String activeFile;
    private static final AtomicInteger captureCallbacks = new AtomicInteger();
    private static long firstCaptureNs = -1;
    private static long lastCaptureNs = -1;
    private static volatile int lastCompletedFrames = 0;
    private static volatile long lastCompletedSpanUs = -1L;
    private static volatile double lastCompletedFps = 0.0;

    public static void beginCapture(String fileName) {
        synchronized (LOCK) {
            activeFile = fileName;
            captureCallbacks.set(0);
            firstCaptureNs = -1;
            lastCaptureNs = -1;
        }
        TraceLog.i("AUDIT begin file=" + fileName);
    }

    public static void cameraCapture(long sensorTimestampNs) {
        synchronized (LOCK) {
            if (activeFile == null) return;
            captureCallbacks.incrementAndGet();
            if (firstCaptureNs < 0) firstCaptureNs = sensorTimestampNs;
            lastCaptureNs = sensorTimestampNs;
        }
    }

    public static int endCapture(String fileName) {
        int count;
        long first;
        long last;
        synchronized (LOCK) {
            count = captureCallbacks.get();
            first = firstCaptureNs;
            last = lastCaptureNs;
            activeFile = null;
        }
        long spanUs = first >= 0 && last >= first ? (last - first) / 1000L : -1;
        lastCompletedFrames = count;
        lastCompletedSpanUs = spanUs;
        lastCompletedFps = spanUs > 0 && count > 1
                ? (count - 1) * 1_000_000.0 / spanUs
                : 0.0;
        TraceLog.i("AUDIT camera file=" + fileName +
                " captureCallbacks=" + count +
                " firstSensorNs=" + first +
                " lastSensorNs=" + last +
                " spanUs=" + spanUs);
        return count;
    }


    public static int lastCaptureFrames() { return lastCompletedFrames; }
    public static long lastCaptureSpanUs() { return lastCompletedSpanUs; }
    public static double lastCaptureFps() { return lastCompletedFps; }

    public static ExtractorStats inspectMp4(File file) {
        ExtractorStats stats = new ExtractorStats();
        MediaExtractor extractor = new MediaExtractor();
        try {
            extractor.setDataSource(file.getAbsolutePath());
            int track = -1;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat format = extractor.getTrackFormat(i);
                String mime = format.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("video/")) {
                    track = i;
                    break;
                }
            }
            if (track < 0) {
                stats.error = "no-video-track";
                return stats;
            }
            extractor.selectTrack(track);
            while (true) {
                long pts = extractor.getSampleTime();
                if (pts < 0) break;
                stats.samples++;
                if (stats.firstPtsUs < 0) stats.firstPtsUs = pts;
                stats.lastPtsUs = pts;
                if (stats.headPtsUs.size() < 5) stats.headPtsUs.add(pts);
                if (stats.tailPtsUs.size() == 5) stats.tailPtsUs.remove(0);
                stats.tailPtsUs.add(pts);
                if (!extractor.advance()) break;
            }
        } catch (Exception e) {
            stats.error = e.getClass().getSimpleName() + ":" + e.getMessage();
        } finally {
            try { extractor.release(); } catch (Exception ignored) {}
        }
        return stats;
    }

    public static void logMp4(File file) {
        ExtractorStats s = inspectMp4(file);
        TraceLog.i("AUDIT mp4 file=" + file.getName() +
                " samples=" + s.samples +
                " firstPtsUs=" + s.firstPtsUs +
                " lastPtsUs=" + s.lastPtsUs +
                " spanUs=" + (s.firstPtsUs >= 0 ? s.lastPtsUs - s.firstPtsUs : -1) +
                " headPts=" + s.headPtsUs +
                " tailPts=" + s.tailPtsUs +
                (s.error == null ? "" : " error=" + s.error));
    }

    public static final class ExtractorStats {
        public int samples;
        public long firstPtsUs = -1;
        public long lastPtsUs = -1;
        public final List<Long> headPtsUs = new ArrayList<>();
        public final List<Long> tailPtsUs = new ArrayList<>();
        public String error;
    }
}

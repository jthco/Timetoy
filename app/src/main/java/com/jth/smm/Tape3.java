package com.jth.smm;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.view.Surface;

import java.nio.ByteBuffer;

/*
 * Tape3. This is the one.
 *
 * All-I hardware-compressed circular tape.
 *
 * Milestone 1:
 *   Camera -> AVC encoder -> preallocated RAM arena
 *
 * No decoder yet.
 * No playback yet.
 * No per-frame allocation in our steady-state code.
 */
public final class Tape3 {

    private static final String MIME = "video/avc";

    private static final int ARENA_MIB = 128;
    private static final int ARENA_BYTES = ARENA_MIB * 1024 * 1024;

    /*
     * Far more index entries than 128 MiB should ever need.
     * Primitive arrays only.
     */
    private static final int MAX_FRAMES = 32768;

    private final int width;
    private final int height;
    private final int fps;
    private final int bitrate;

    private MediaCodec encoder;
    private Surface inputSurface;

    private ByteBuffer arena;

    private final int[] offsets = new int[MAX_FRAMES];
    private final int[] lengths = new int[MAX_FRAMES];
    private final long[] frameNumbers = new long[MAX_FRAMES];
    private final long[] ptsUs = new long[MAX_FRAMES];

    private int indexWrite = 0;
    private int indexCount = 0;
    private int byteWrite = 0;

    private long totalFrames = 0;
    private long keyFrames = 0;
    private long totalBytes = 0;
    private int peakFrameBytes = 0;

    private long firstPtsUs = -1;
    private long lastPtsUs = -1;
    private long startNs = 0;

    private volatile boolean running = false;
    private Thread drainThread;


    public Tape3(
            int width,
            int height,
            int fps,
            int bitrate
    ) {
        this.width = width;
        this.height = height;
        this.fps = fps;
        this.bitrate = bitrate;
    }


    public void prepare() throws Exception {

        /*
         * One allocation for the encoded tape.
         * Nothing in pushFrame() allocates.
         */
        arena = ByteBuffer.allocateDirect(ARENA_BYTES);

        MediaFormat format =
                MediaFormat.createVideoFormat(MIME, width, height);

        format.setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
        );

        format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, fps);

        /*
         * Request GOP-1 / All-Intra.
         *
         * We do NOT trust this setting blindly.
         * drainEncoder() counts actual KEY_FRAME output.
         */
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 0);

        encoder = MediaCodec.createEncoderByType(MIME);
        encoder.configure(
                format,
                null,
                null,
                MediaCodec.CONFIGURE_FLAG_ENCODE
        );

        inputSurface = encoder.createInputSurface();

        TraceLog.i(
                "TAPE3 prepared " +
                width + "x" + height +
                "@" + fps +
                " bitrate=" + bitrate +
                " arenaMiB=" + ARENA_MIB
        );
    }


    public Surface getInputSurface() {
        return inputSurface;
    }


    public void start() {

        if (encoder == null) {
            throw new IllegalStateException("Tape3 not prepared");
        }

        running = true;
        startNs = System.nanoTime();

        encoder.start();

        drainThread = new Thread(
                this::drainEncoder,
                "Tape3Drain"
        );

        drainThread.start();

        TraceLog.i("TAPE3 started");
    }


    private void drainEncoder() {

        /*
         * Allocated once when the thread starts, not per frame.
         */
        MediaCodec.BufferInfo info =
                new MediaCodec.BufferInfo();

        while (running) {

            final int status;

            try {
                status =
                        encoder.dequeueOutputBuffer(
                                info,
                                10000
                        );
            } catch (Throwable t) {
                TraceLog.e(
                        "TAPE3 dequeue failed",
                        t instanceof Exception
                                ? (Exception)t
                                : new RuntimeException(t)
                );
                break;
            }


            if (status == MediaCodec.INFO_TRY_AGAIN_LATER) {

                continue;

            } else if (
                    status ==
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED
            ) {

                TraceLog.i(
                        "TAPE3 output format " +
                        encoder.getOutputFormat()
                );

                continue;

            } else if (status < 0) {

                continue;
            }


            ByteBuffer src =
                    encoder.getOutputBuffer(status);

            if (src == null) {

                encoder.releaseOutputBuffer(
                        status,
                        false
                );

                continue;
            }


            final int flags = info.flags;

            final boolean codecConfig =
                    (flags &
                     MediaCodec.BUFFER_FLAG_CODEC_CONFIG)
                    != 0;

            final boolean keyFrame =
                    (flags &
                     MediaCodec.BUFFER_FLAG_KEY_FRAME)
                    != 0;


            if (!codecConfig && info.size > 0) {

                storeFrame(
                        src,
                        info.offset,
                        info.size,
                        info.presentationTimeUs,
                        keyFrame
                );
            }


            encoder.releaseOutputBuffer(
                    status,
                    false
            );
        }

        TraceLog.i("TAPE3 drain stopped");
    }


    private void storeFrame(
            ByteBuffer src,
            int srcOffset,
            int size,
            long presentationTimeUs,
            boolean keyFrame
    ) {

        /*
         * A single encoded frame larger than the entire
         * arena is unusable.  Log and discard it.
         */
        if (size > ARENA_BYTES) {

            TraceLog.i(
                    "TAPE3 frame too large bytes=" +
                    size
            );

            return;
        }


        /*
         * Keep every frame contiguous.
         *
         * If it would cross the end of the byte arena,
         * abandon the tail and wrap to zero.
         */
        if (byteWrite + size > ARENA_BYTES) {
            byteWrite = 0;
        }


        src.position(srcOffset);
        src.limit(srcOffset + size);

        arena.position(byteWrite);
        arena.put(src);


        offsets[indexWrite] = byteWrite;
        lengths[indexWrite] = size;
        frameNumbers[indexWrite] = totalFrames;
        ptsUs[indexWrite] = presentationTimeUs;


        byteWrite += size;

        indexWrite++;

        if (indexWrite == MAX_FRAMES) {
            indexWrite = 0;
        }

        if (indexCount < MAX_FRAMES) {
            indexCount++;
        }


        totalFrames++;
        totalBytes += size;

        if (keyFrame) {
            keyFrames++;
        }

        if (size > peakFrameBytes) {
            peakFrameBytes = size;
        }

        if (firstPtsUs < 0) {
            firstPtsUs = presentationTimeUs;
        }

        lastPtsUs = presentationTimeUs;


        /*
         * Sparse diagnostics only.
         * No String construction on every frame.
         */
        if ((totalFrames % (fps * 5L)) == 0) {
            logStats();
        }
    }


    public void logStats() {

        final long nowNs = System.nanoTime();

        final double wallSeconds =
                startNs == 0
                        ? 0.0
                        : (nowNs - startNs) /
                          1_000_000_000.0;

        final double encodedFps =
                wallSeconds > 0.0
                        ? totalFrames / wallSeconds
                        : 0.0;

        final double mb =
                totalBytes /
                (1024.0 * 1024.0);

        final double mbPerSec =
                wallSeconds > 0.0
                        ? mb / wallSeconds
                        : 0.0;

        final double avgBytes =
                totalFrames > 0
                        ? (double)totalBytes /
                          totalFrames
                        : 0.0;

        final double keyPercent =
                totalFrames > 0
                        ? 100.0 *
                          keyFrames /
                          totalFrames
                        : 0.0;

        final double ptsSeconds =
                firstPtsUs >= 0 &&
                lastPtsUs >= firstPtsUs
                        ? (lastPtsUs -
                           firstPtsUs) /
                          1_000_000.0
                        : 0.0;

        final double arenaHistorySeconds =
                mbPerSec > 0.0
                        ? ARENA_MIB /
                          mbPerSec
                        : 0.0;


        TraceLog.i(
                "TAPE3 STATS" +
                " frames=" + totalFrames +
                " key=" + keyFrames +
                " keyPct=" + keyPercent +
                " fps=" + encodedFps +
                " MB=" + mb +
                " MBps=" + mbPerSec +
                " avgBytes=" + avgBytes +
                " peakBytes=" + peakFrameBytes +
                " ptsSec=" + ptsSeconds +
                " arenaMiB=" + ARENA_MIB +
                " estHistorySec=" +
                arenaHistorySeconds
        );
    }


    public long getTotalFrames() { return totalFrames; }

    public long getKeyFrames() { return keyFrames; }

    public double getEncodedFps() {
        long nowNs = System.nanoTime();
        double seconds = startNs == 0 ? 0.0 :
                (nowNs - startNs) / 1000000000.0;
        return seconds > 0.0 ? totalFrames / seconds : 0.0;
    }

    public double getMegabytes() {
        return totalBytes / (1024.0 * 1024.0);
    }

    public double getMegabytesPerSecond() {
        long nowNs = System.nanoTime();
        double seconds = startNs == 0 ? 0.0 :
                (nowNs - startNs) / 1000000000.0;
        return seconds > 0.0 ? getMegabytes() / seconds : 0.0;
    }

    public double getAverageFrameBytes() {
        return totalFrames > 0 ? (double) totalBytes / totalFrames : 0.0;
    }

    public int getPeakFrameBytes() { return peakFrameBytes; }

    public double getKeyPercent() {
        return totalFrames > 0 ? 100.0 * keyFrames / totalFrames : 0.0;
    }

    public double getEstimatedHistorySeconds() {
        double rate = getMegabytesPerSecond();
        return rate > 0.0 ? ARENA_MIB / rate : 0.0;
    }

    public void stopAndRelease() {

        running = false;

        try {
            if (encoder != null) {
                encoder.signalEndOfInputStream();
            }
        } catch (Throwable ignored) {
        }


        try {
            if (drainThread != null) {
                drainThread.join(1500);
            }
        } catch (InterruptedException ignored) {
        }


        logStats();


        try {
            if (encoder != null) {
                encoder.stop();
            }
        } catch (Throwable ignored) {
        }

        try {
            if (encoder != null) {
                encoder.release();
            }
        } catch (Throwable ignored) {
        }

        try {
            if (inputSurface != null) {
                inputSurface.release();
            }
        } catch (Throwable ignored) {
        }


        encoder = null;
        inputSurface = null;
        drainThread = null;

        TraceLog.i("TAPE3 released");
    }
}

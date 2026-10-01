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
 * Milestone 2:
 *   Camera -> AVC encoder -> compressed RAM
 *                         -> 1 second Delay -> AVC decoder -> Surface
 */
public final class Tape3 {

    private static final String MIME = "video/avc";

    private static final int ARENA_MIB = 128;
    private static final int ARENA_BYTES = ARENA_MIB * 1024 * 1024;
    private static final int MAX_FRAMES = 32768;

    private static final long DELAY_US = 1_000_000L;

    private final int width;
    private final int height;
    private final int fps;
    private final int bitrate;
    private final Surface decoderSurface;

    private MediaCodec encoder;
    private MediaCodec decoder;
    private Surface inputSurface;

    private ByteBuffer arena;

    private final int[] offsets = new int[MAX_FRAMES];
    private final int[] lengths = new int[MAX_FRAMES];
    private final long[] frameNumbers = new long[MAX_FRAMES];
    private final long[] ptsUs = new long[MAX_FRAMES];
    private final int[] codecFlags = new int[MAX_FRAMES];

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

    private boolean decoderReady = false;
    private long decodedFrames = 0;
    private long lastDecodedFrameNumber = -1;
    private int delayTraceFrames = 0;


    public Tape3(
            int width,
            int height,
            int fps,
            int bitrate,
            Surface decoderSurface
    ) {
        this.width = width;
        this.height = height;
        this.fps = fps;
        this.bitrate = bitrate;
        this.decoderSurface = decoderSurface;
    }


    public void prepare() throws Exception {

        arena = ByteBuffer.allocateDirect(ARENA_BYTES);

        MediaFormat format =
                MediaFormat.createVideoFormat(MIME, width, height);

        format.setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
        );

        format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
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
                " arenaMiB=" + ARENA_MIB +
                " delayUs=" + DELAY_US
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


    private void prepareDecoder(MediaFormat encoderFormat) {

        if (decoderReady || decoderSurface == null) return;

        try {
            MediaFormat f =
                    MediaFormat.createVideoFormat(
                            MIME,
                            width,
                            height
                    );

            ByteBuffer csd0 =
                    encoderFormat.getByteBuffer("csd-0");

            traceBytes("CSD-0", csd0, csd0 == null ? 0 : csd0.position(),
                    csd0 == null ? 0 : csd0.remaining());
            ByteBuffer csd1 =
                    encoderFormat.getByteBuffer("csd-1");

            traceBytes("CSD-1", csd1, csd1 == null ? 0 : csd1.position(),
                    csd1 == null ? 0 : csd1.remaining());
            if (csd0 != null) {
                f.setByteBuffer("csd-0", csd0);
            }

            if (csd1 != null) {
                f.setByteBuffer("csd-1", csd1);
            }

            decoder =
                    MediaCodec.createDecoderByType(MIME);

            decoder.configure(
                    f,
                    decoderSurface,
                    null,
                    0
            );

            decoder.start();
            decoderReady = true;

            TraceLog.i(
                    "TAPE3 DELAY decoder started " +
                    width + "x" + height
            );

        } catch (Throwable t) {

            TraceLog.e(
                    "TAPE3 DELAY decoder prepare failed",
                    t instanceof Exception
                            ? (Exception)t
                            : new RuntimeException(t)
            );

            decoderReady = false;
        }
    }


    private void drainEncoder() {

        MediaCodec.BufferInfo info =
                new MediaCodec.BufferInfo();

        while (running) {

            final int status;

            try {
                if (decodedFrames < 12)
                    TraceLog.i("DEEP DEC dequeueOutput BEGIN");
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

                drainDecoder();
                continue;

            } else if (
                    status ==
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED
            ) {

                MediaFormat outputFormat =
                        encoder.getOutputFormat();

                TraceLog.i(
                        "TAPE3 output format " +
                        outputFormat
                );

                prepareDecoder(outputFormat);
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
                        keyFrame, flags
                );

                feedDelayFrame(
                        info.presentationTimeUs
                );
            }


            encoder.releaseOutputBuffer(
                    status,
                    false
            );

            drainDecoder();
        }

        TraceLog.i("TAPE3 drain stopped");
    }


    private void storeFrame(
            ByteBuffer src,
            int srcOffset,
            int size,
            long presentationTimeUs,
            boolean keyFrame, int flags
    ) {

        if (size > ARENA_BYTES) {

            TraceLog.i(
                    "TAPE3 frame too large bytes=" +
                    size
            );

            return;
        }


        if (byteWrite + size > ARENA_BYTES) {
            byteWrite = 0;
        }


        src.position(srcOffset);
        src.limit(srcOffset + size);

        arena.clear();
        arena.position(byteWrite);
        arena.put(src);


        offsets[indexWrite] = byteWrite;
        lengths[indexWrite] = size;
        frameNumbers[indexWrite] = totalFrames;
        ptsUs[indexWrite] = presentationTimeUs;
        codecFlags[indexWrite] = flags;


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


        if ((totalFrames % (fps * 5L)) == 0) {
            logStats();
        }
    }


    /*
     * Find the newest frame whose PTS is <= now - 1 second.
     *
     * We walk backward from the newest index.  For this first
     * Delay proof the reader runs on the same thread as the writer,
     * so the metadata and arena cannot change underneath us.
     */

    private static String hexByte(int v) {
        final char[] h = "0123456789ABCDEF".toCharArray();
        return "" + h[(v >> 4) & 15] + h[v & 15];
    }

    private static String nalName(int type) {
        switch (type) {
            case 1: return "NON_IDR_SLICE";
            case 5: return "IDR_SLICE";
            case 6: return "SEI";
            case 7: return "SPS";
            case 8: return "PPS";
            case 9: return "AUD";
            default: return "NAL_" + type;
        }
    }

    private void traceBytes(String label, ByteBuffer b, int start, int length) {
        if (b == null) {
            TraceLog.i("DEEP AVC " + label + " NULL");
            return;
        }

        int oldPos = b.position();
        int oldLim = b.limit();
        int n = Math.min(length, 64);
        StringBuilder s = new StringBuilder();

        try {
            for (int i = 0; i < n; i++) {
                if (i != 0) s.append(" ");
                s.append(hexByte(b.get(start + i) & 0xFF));
            }
            TraceLog.i("DEEP AVC " + label + " bytes[" + n + "]=" + s);
        } catch (Throwable t) {
            TraceLog.i("DEEP AVC " + label + " HEX FAILED " + t);
        } finally {
            b.position(oldPos);
            b.limit(oldLim);
        }
    }

    private void traceAnnexBNals(ByteBuffer b, int start, int length) {
        int end = start + length;
        int found = 0;

        for (int p = start; p + 4 < end && found < 32; p++) {
            int b0 = b.get(p) & 0xFF;
            int b1 = b.get(p + 1) & 0xFF;
            int b2 = b.get(p + 2) & 0xFF;

            int header = -1;
            int prefix = 0;

            if (b0 == 0 && b1 == 0 && b2 == 1) {
                header = p + 3;
                prefix = 3;
            } else if (p + 4 < end &&
                    b0 == 0 && b1 == 0 && b2 == 0 &&
                    (b.get(p + 3) & 0xFF) == 1) {
                header = p + 4;
                prefix = 4;
            }

            if (header >= 0 && header < end) {
                int h = b.get(header) & 0xFF;
                int type = h & 0x1F;
                int refIdc = (h >> 5) & 3;

                TraceLog.i(
                        "DEEP AVC NAL#" + found +
                        " at=" + (p - start) +
                        " prefix=" + prefix +
                        " header=0x" + hexByte(h) +
                        " type=" + type +
                        " " + nalName(type) +
                        " refIdc=" + refIdc);

                found++;
                p = header;
            }
        }

        TraceLog.i("DEEP AVC AnnexB NAL count=" + found);
    }

    private void traceDelayCandidate(int i, long currentPtsUs) {
        int off = offsets[i];
        int len = lengths[i];
        int flags = codecFlags[i];

        TraceLog.i("========== DEEP AVC CANDIDATE ==========");
        TraceLog.i(
                "DEEP AVC frame=" + frameNumbers[i] +
                " index=" + i +
                " pts=" + ptsUs[i] +
                " currentPts=" + currentPtsUs +
                " ageUs=" + (currentPtsUs - ptsUs[i]));

        TraceLog.i(
                "DEEP AVC arenaOffset=" + off +
                " length=" + len +
                " codecFlags=0x" + Integer.toHexString(flags) +
                " KEY=" + ((flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0) +
                " CONFIG=" + ((flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0));

        traceBytes("FRAME", arena, off, len);
        traceAnnexBNals(arena, off, len);
        TraceLog.i("========================================");
    }

    private int findDelayIndex(long currentPtsUs) {

        if (indexCount <= 0) return -1;

        final long target =
                currentPtsUs - DELAY_US;

        if (target < 0) return -1;

        int i =
                indexWrite - 1;

        if (i < 0) i = MAX_FRAMES - 1;

        for (int n = 0; n < indexCount; n++) {

            if (ptsUs[i] <= target) {
                return i;
            }

            i--;

            if (i < 0) {
                i = MAX_FRAMES - 1;
            }
        }

        return -1;
    }


    private void feedDelayFrame(long currentPtsUs) {

        if (!decoderReady || decoder == null) return;

        final boolean deepTrace = delayTraceFrames < 12;
        if (deepTrace) TraceLog.i("DEEP DELAY feed ENTER currentPts=" + currentPtsUs);
        final int tapeIndex =
                findDelayIndex(currentPtsUs);

        if (tapeIndex < 0) return;

        if (deepTrace) TraceLog.i(
                "DEEP DELAY found index=" + tapeIndex +
                " frame=" + frameNumbers[tapeIndex] +
                " pts=" + ptsUs[tapeIndex] +
                " ageUs=" + (currentPtsUs - ptsUs[tapeIndex]) +
                " offset=" + offsets[tapeIndex] +
                " length=" + lengths[tapeIndex]);

        if (lastDecodedFrameNumber < 0) {
            traceDelayCandidate(tapeIndex, currentPtsUs);
        }
        final long frameNumber =
                frameNumbers[tapeIndex];

        /*
         * Don't submit the same stored frame repeatedly if camera
         * cadence and encoder callbacks momentarily differ.
         */
        if (frameNumber == lastDecodedFrameNumber) {
            return;
        }


        final int inputIndex;

        try {
            inputIndex =
                    decoder.dequeueInputBuffer(0);
            if (deepTrace) TraceLog.i("DEEP DELAY dequeueInput RESULT=" + inputIndex);
        } catch (Throwable t) {
            return;
        }

        if (inputIndex < 0) return;


        ByteBuffer dst =
                decoder.getInputBuffer(inputIndex);

        if (dst == null) return;

        if (lastDecodedFrameNumber < 0) {
            TraceLog.i(
                    "DEEP AVC decoderInput index=" + inputIndex +
                    " capacity=" + dst.capacity() +
                    " frameLength=" + lengths[tapeIndex]);
        }


        final int offset =
                offsets[tapeIndex];

        final int length =
                lengths[tapeIndex];

        if (deepTrace) TraceLog.i("DEEP DELAY copy BEGIN");
        arena.position(offset);
        arena.limit(offset + length);

        dst.clear();
        dst.put(arena);
        if (deepTrace) TraceLog.i("DEEP DELAY copy END bytes=" + length);

        if (deepTrace) TraceLog.i("DEEP DELAY queueInput BEGIN");
        if (lastDecodedFrameNumber < 0)
            TraceLog.i("DEEP AVC >>> QUEUE FIRST ACCESS UNIT");
        decoder.queueInputBuffer(
                inputIndex,
                0,
                length,
                ptsUs[tapeIndex],
                MediaCodec.BUFFER_FLAG_KEY_FRAME
        );

        if (lastDecodedFrameNumber < 0)
            TraceLog.i("DEEP AVC <<< QUEUE FIRST ACCESS UNIT RETURNED");
        if (deepTrace) {
            TraceLog.i("DEEP DELAY queueInput END");
            delayTraceFrames++;
        }
        lastDecodedFrameNumber =
                frameNumber;
    }


    private void drainDecoder() {

        if (decodedFrames < 12)
            TraceLog.i("DEEP DEC drain ENTER decoded=" + decodedFrames);

        if (!decoderReady || decoder == null) return;

        MediaCodec.BufferInfo info =
                new MediaCodec.BufferInfo();

        while (true) {

            final int status;

            try {
                if (decodedFrames < 12)
                    TraceLog.i("DEEP DEC dequeueOutput BEGIN");
                status =
                        decoder.dequeueOutputBuffer(
                                info,
                                0
                        );
            } catch (Throwable t) {
                return;
            }

            if (status ==
                    MediaCodec.INFO_TRY_AGAIN_LATER) {
                return;
            }

            if (status ==
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {

                TraceLog.i(
                        "TAPE3 DELAY decoder format " +
                        decoder.getOutputFormat()
                );

                continue;
            }

            if (status < 0) {
                continue;
            }

            if (decodedFrames < 12)
                TraceLog.i("DEEP DEC releaseOutput TRUE BEGIN status=" + status +
                        " pts=" + info.presentationTimeUs);
            decoder.releaseOutputBuffer(
                    status,
                    true
            );

            if (decodedFrames < 12)
                TraceLog.i("DEEP DEC releaseOutput TRUE END status=" + status);
            decodedFrames++;

            if (decodedFrames == 1) {
                TraceLog.i(
                        "TAPE3 DELAY first frame rendered"
                );
            }
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
                arenaHistorySeconds +
                " decoded=" + decodedFrames
        );
    }


    public long getTotalFrames() { return totalFrames; }

    public long getKeyFrames() { return keyFrames; }

    public long getDecodedFrames() {
        return decodedFrames;
    }

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
            if (decoder != null) {
                decoder.stop();
            }
        } catch (Throwable ignored) {
        }

        try {
            if (decoder != null) {
                decoder.release();
            }
        } catch (Throwable ignored) {
        }

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


        decoder = null;
        encoder = null;
        inputSurface = null;
        drainThread = null;
        decoderReady = false;

        TraceLog.i("TAPE3 released");
    }
}

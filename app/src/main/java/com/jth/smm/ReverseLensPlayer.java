// ============================================================
// SlowMo Lens
// File: ReverseLensPlayer.java
// Version: v0.6.17
// Build: Variable Reverse Banks + Full-Frame Slow at 24 fps
// Date: 2026-08-02
// ============================================================

package com.jth.smm;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.view.Surface;

import java.io.File;
import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Sequentially hardware-decodes the clip once into a GPU texture bank.
 * Every output frame is rendered to a SurfaceTexture and copied GPU-to-GPU
 * into a persistent GL_TEXTURE_2D texture. Playback then walks the bank
 * backwards without seeking, bitmap allocation, or CPU pixel conversion.
 */
public class ReverseLensPlayer implements LensPlayer {
    public interface GpuBankSink {
        void beginBank(int slot, int width, int height, int maxFrames);
        void armFrameCapture(int slot, int frameIndex, Runnable capturedCallback);
        void showFrame(int slot, int frameIndex, Runnable shownCallback);
    }

    public interface FailureCallback {
        void onFailure(String reason);
    }

    private static final long CODEC_TIMEOUT_US = 10_000L;
    private static final int MAX_BANK_FRAMES = 480;
    private static final int REVERSE_CAPTURE_DECIMATION = 2;
    private static final int SLOW_CAPTURE_DECIMATION = 1;

    private final int slot;
    private final File file;
    private final Surface decoderSurface;
    private final GpuBankSink gpuBankSink;
    private final int outputFps;
    private final int playbackDurationMs;
    private final Runnable preparedCallback;
    private final Runnable doneCallback;
    private final FailureCallback failureCallback;
    private final boolean reverse;

    private final CountDownLatch startLatch = new CountDownLatch(1);

    private volatile boolean cancelled;
    private volatile boolean prepared;
    private volatile int decodedFrameCount;
    private volatile long[] decodedFramePtsUs = new long[0];
    private Thread workerThread;

    public ReverseLensPlayer(
            int slot,
            File file,
            Surface decoderSurface,
            GpuBankSink gpuBankSink,
            int outputFps,
            int playbackDurationMs,
            Runnable preparedCallback,
            Runnable doneCallback,
            FailureCallback failureCallback
    ) {
        this(slot, file, decoderSurface, gpuBankSink, outputFps, playbackDurationMs, true, preparedCallback, doneCallback, failureCallback);
    }

    public ReverseLensPlayer(
            int slot,
            File file,
            Surface decoderSurface,
            GpuBankSink gpuBankSink,
            int outputFps,
            int playbackDurationMs,
            boolean reverse,
            Runnable preparedCallback,
            Runnable doneCallback,
            FailureCallback failureCallback
    ) {
        this.slot = slot;
        this.file = file;
        this.decoderSurface = decoderSurface;
        this.gpuBankSink = gpuBankSink;
        this.outputFps = Math.max(1, outputFps);
        this.playbackDurationMs = Math.max(1, playbackDurationMs);
        this.preparedCallback = preparedCallback;
        this.doneCallback = doneCallback;
        this.failureCallback = failureCallback;
        this.reverse = reverse;
    }

    @Override
    public void prepareAsync() {
        workerThread = new Thread(() -> {
            Thread.currentThread().setName("ReverseGPU-" + slot);
            try {
                decodeOnceToGpuBank();
                if (cancelled) return;
                if (decodedFrameCount <= 0) {
                    fail("zero decoded frames");
                    return;
                }

                /*
                 * Do not select a frame from a bank while it is merely being
                 * prepared. showFrame() affects the shared GL display state,
                 * so selecting the first frame of the next bank here can flash
                 * that frame over the currently playing bank. The timing of
                 * that flash follows decode completion: about 1 s into a 2 s
                 * cycle and about 2 s into a 4 s cycle.
                 *
                 * The first frame is selected only after startPrepared(), in
                 * playBankLoop(), when this player actually owns the display.
                 */
                TraceLog.i("reverse GPU slot " + slot +
                        " prepared without display selection");

                prepared = true;
                TraceLog.i("reverse GPU slot " + slot +
                        " ready frames=" + decodedFrameCount);
                if (preparedCallback != null) preparedCallback.run();

                startLatch.await();
                if (cancelled) return;
                playBankLoop();
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                if (!cancelled) {
                    TraceLog.e("reverse GPU slot " + slot + " error", e);
                    fail(e.getClass().getSimpleName() + ": " + String.valueOf(e.getMessage()));
                }
            }
        });
        workerThread.start();
    }

    @Override
    public void startPrepared() {
        if (!cancelled) startLatch.countDown();
    }

    @Override
    public void cancel() {
        cancelled = true;
        startLatch.countDown();
        if (workerThread != null) workerThread.interrupt();
    }

    @Override
    public boolean isPrepared() {
        return prepared;
    }

    private void decodeOnceToGpuBank() throws Exception {
        MediaExtractor extractor = new MediaExtractor();
        MediaCodec decoder = null;
        try {
            extractor.setDataSource(file.getAbsolutePath());
            int trackIndex = findVideoTrack(extractor);
            if (trackIndex < 0) {
                throw new IllegalArgumentException("No video track in " + file.getName());
            }

            extractor.selectTrack(trackIndex);
            MediaFormat format = extractor.getTrackFormat(trackIndex);
            String mime = format.getString(MediaFormat.KEY_MIME);
            int width = format.containsKey(MediaFormat.KEY_WIDTH)
                    ? format.getInteger(MediaFormat.KEY_WIDTH) : 1280;
            int height = format.containsKey(MediaFormat.KEY_HEIGHT)
                    ? format.getInteger(MediaFormat.KEY_HEIGHT) : 720;

            long bytesPerFrame = Math.max(1L, (long) width * (long) height * 4L);

            /*
             * The bank is a playback bank, not a capture-rate bank. Allocate
             * only the number of frames that can actually be displayed during
             * this cycle. A 2 s cycle at 24 fps therefore needs 48 textures,
             * not the old memory-budget maximum of 109 at 720p.
             *
             * Clips commonly contain one or two extra 30 fps frames because
             * recorder stop is asynchronous. Those surplus tail frames are
             * deliberately ignored. This also gives a fixed playback rate.
             */
            int playbackFrames = (int) Math.ceil(
                    (playbackDurationMs * (double) outputFps) / 1000.0
            );
            int requestedBankFrames = playbackFrames;
            int budgetFrames = Math.max(
                    1,
                    Math.min(MAX_BANK_FRAMES, requestedBankFrames)
            );
            int captureDecimation = reverse
                    ? REVERSE_CAPTURE_DECIMATION
                    : SLOW_CAPTURE_DECIMATION;

            TraceLog.i("reverse GPU slot " + slot +
                    " bank target frames=" + budgetFrames +
                    " playbackMs=" + playbackDurationMs +
                    " outputFps=" + outputFps +
                    " resolution=" + width + "x" + height +
                    " approxMiB=" + ((budgetFrames * bytesPerFrame) / (1024L * 1024L)));
            gpuBankSink.beginBank(slot, width, height, budgetFrames);
            TraceLog.i("reverse GPU slot " + slot +
                    " bank allocated frames=" + budgetFrames);

            decoder = MediaCodec.createDecoderByType(mime);
            decoder.configure(format, decoderSurface, null, 0);
            decoder.start();

            TraceLog.i("reverse GPU slot " + slot +
                    " decode begin " + width + "x" + height +
                    " mime=" + mime);

            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inputDone = false;
            boolean outputDone = false;
            int frameIndex = 0;
            int sourceFrameIndex = 0;
            long[] framePtsUs = new long[budgetFrames];
            long beginNs = System.nanoTime();

            while (!outputDone && !cancelled) {
                if (!inputDone) {
                    int inputIndex = decoder.dequeueInputBuffer(CODEC_TIMEOUT_US);
                    if (inputIndex >= 0) {
                        ByteBuffer input = decoder.getInputBuffer(inputIndex);
                        int sampleSize = extractor.readSampleData(input, 0);
                        if (sampleSize < 0) {
                            decoder.queueInputBuffer(
                                    inputIndex, 0, 0, 0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            );
                            inputDone = true;
                        } else {
                            decoder.queueInputBuffer(
                                    inputIndex,
                                    0,
                                    sampleSize,
                                    extractor.getSampleTime(),
                                    extractor.getSampleFlags()
                            );
                            extractor.advance();
                        }
                    }
                }

                int outputIndex = decoder.dequeueOutputBuffer(info, CODEC_TIMEOUT_US);
                if (outputIndex >= 0) {
                    boolean eos = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    boolean hasFrame = info.size > 0;
                    boolean retain = hasFrame &&
                            (sourceFrameIndex % captureDecimation == 0) &&
                            frameIndex < budgetFrames;

                    if (retain) {
                        CountDownLatch copied = new CountDownLatch(1);
                        gpuBankSink.armFrameCapture(slot, frameIndex, copied::countDown);
                        decoder.releaseOutputBuffer(outputIndex, true);

                        if (!copied.await(2, TimeUnit.SECONDS)) {
                            throw new IllegalStateException(
                                    "Timed out copying decoded frame " + frameIndex
                            );
                        }
                        framePtsUs[frameIndex] = info.presentationTimeUs;
                        if (frameIndex < 12) {
                            TraceLog.i("reverse decode head slot=" + slot +
                                    " frame=" + frameIndex +
                                    " sourceFrame=" + sourceFrameIndex +
                                    " ptsUs=" + info.presentationTimeUs);
                        }
                        frameIndex++;
                    } else {
                        decoder.releaseOutputBuffer(outputIndex, false);
                    }

                    if (hasFrame) {
                        sourceFrameIndex++;
                    }

                    if (eos || frameIndex >= budgetFrames) {
                        outputDone = true;
                    }
                }
            }

            decodedFrameCount = frameIndex;
            decodedFramePtsUs = java.util.Arrays.copyOf(framePtsUs, frameIndex);
            for (int i = Math.max(0, frameIndex - 12); i < frameIndex; i++) {
                TraceLog.i("reverse decode tail slot=" + slot +
                        " frame=" + i +
                        " ptsUs=" + decodedFramePtsUs[i]);
            }
            long elapsedMs = (System.nanoTime() - beginNs) / 1_000_000L;
            TraceLog.i("reverse GPU slot " + slot +
                    " file=" + file.getName() +
                    " decodedFrames=" + decodedFrameCount +
                    " sourceFramesConsumed=" + sourceFrameIndex +
                    " decimation=" + captureDecimation + ":1" +
                    " gpuTextureCopies=" + decodedFrameCount +
                    " elapsedMs=" + elapsedMs);
        } finally {
            if (decoder != null) {
                try { decoder.stop(); } catch (Exception ignored) {}
                try { decoder.release(); } catch (Exception ignored) {}
            }
            try { extractor.release(); } catch (Exception ignored) {}
        }
    }

    private int findVideoTrack(MediaExtractor extractor) {
        for (int i = 0; i < extractor.getTrackCount(); i++) {
            MediaFormat format = extractor.getTrackFormat(i);
            String mime = format.getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("video/")) return i;
        }
        return -1;
    }

    private void playBankLoop() throws InterruptedException {
        // Keep the requested output cadence even when Reverse deliberately
        // retains fewer than playbackDurationMs * outputFps frames.
        // Reverse bank size now follows the selected 0.5/1/1.5/2 s duration.
        // Slow: all captured frames / 24 fps gives the intended slow-motion span.
        long framePeriodNs = Math.max(
                1L,
                1_000_000_000L / Math.max(1, outputFps)
        );

        double effectiveFps =
                1_000_000_000.0 / framePeriodNs;

        TraceLog.i((reverse ? "reverse" : "slow") +
                " GPU slot " + slot +
                " playback frames=" + decodedFrameCount +
                " durationMs=" + playbackDurationMs +
                " effectiveFps=" +
                String.format(
                        java.util.Locale.US,
                        "%.2f",
                        effectiveFps
                ));

        /*
         * One ReverseLensPlayer owns one prepared clip and plays it once.
         * The old implementation stayed in an outer while loop after
         * signalling completion, so an obsolete player could keep issuing
         * showFrame() calls while the same slot was being decoded or played
         * by a newer cycle.
         *
         * OpenGL naturally keeps displaying the last selected texture after
         * this worker exits, so no replay loop is needed to hold the image.
         */
        long nextNs = System.nanoTime();

        if (reverse) {
            for (int i = decodedFrameCount - 1;
                 i >= 0 && !cancelled;
                 i--) {

                int reverseDisplayIndex =
                        decodedFrameCount - 1 - i;

                if (i < 12) {
                    long ptsUs =
                            i < decodedFramePtsUs.length
                                    ? decodedFramePtsUs[i]
                                    : -1L;

                    TraceLog.i(
                            "reverse playback end slot=" + slot +
                                    " displayIndex=" + reverseDisplayIndex +
                                    " sourceFrame=" + i +
                                    " sourcePtsUs=" + ptsUs
                    );
                }

                gpuBankSink.showFrame(slot, i, null);
                nextNs += framePeriodNs;
                sleepUntil(nextNs);
            }
        } else {
            for (int i = 0;
                 i < decodedFrameCount && !cancelled;
                 i++) {

                gpuBankSink.showFrame(slot, i, null);
                nextNs += framePeriodNs;
                sleepUntil(nextNs);
            }
        }

        if (!cancelled) {
            TraceLog.i((reverse ? "reverse" : "slow") +
                    " GPU slot " + slot +
                    " playback complete; worker exiting");

            if (doneCallback != null) {
                doneCallback.run();
            }
        }
    }

    private void fail(String reason) {
        TraceLog.i("reverse GPU slot " + slot + " prepare failed reason=" + reason);
        if (failureCallback != null) failureCallback.onFailure(reason);
    }

    private void sleepUntil(long targetNs) throws InterruptedException {
        while (!cancelled) {
            long remaining = targetNs - System.nanoTime();
            if (remaining <= 0) return;
            Thread.sleep(
                    remaining / 1_000_000L,
                    (int) (remaining % 1_000_000L)
            );
        }
    }
}

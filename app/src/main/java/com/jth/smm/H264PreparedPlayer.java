package com.jth.smm;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.view.Surface;

import java.io.File;
import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class H264PreparedPlayer {
    public interface PreparedCallback {
        void onFirstFrameReleased();
    }

    public interface DoneCallback {
        void onDone();
    }

    private final int slot;
    private final File file;
    private final Surface outputSurface;
    private final int outputFps;
    private final int playbackSeconds;
    private final int maxRenderedFrames;
    private final PreparedCallback preparedCallback;
    private final DoneCallback doneCallback;

    private final CountDownLatch startLatch =
            new CountDownLatch(1);

    private volatile boolean cancelled = false;
    private volatile boolean prepared = false;
    private volatile boolean started = false;

    private Thread playbackThread;

    public H264PreparedPlayer(
            int slot,
            File file,
            Surface outputSurface,
            int outputFps,
            int playbackSeconds,
            PreparedCallback preparedCallback,
            DoneCallback doneCallback
    ) {
        this.slot = slot;
        this.file = file;
        this.outputSurface = outputSurface;
        this.outputFps = outputFps;
        this.playbackSeconds = playbackSeconds;
        this.maxRenderedFrames =
                outputFps * playbackSeconds;
        this.preparedCallback =
                preparedCallback;
        this.doneCallback =
                doneCallback;
    }

    public void prepareAsync() {
        playbackThread = new Thread(() -> {
            Thread.currentThread().setName(
                    "PreparedPlayer-" + slot
            );

            try {
                prepareAndPlayBlocking();
            } catch (Exception e) {
                if (!cancelled) {
                    TraceLog.e(
                            "slot " +
                                    slot +
                                    " prepared playback error",
                            e
                    );
                }
            }
        });

        playbackThread.start();
    }

    public void startPrepared() {
        if (cancelled) {
            return;
        }

        started = true;

        TraceLog.i(
                "slot " +
                        slot +
                        " startPrepared"
        );

        startLatch.countDown();
    }

    public void cancel() {
        cancelled = true;
        startLatch.countDown();

        if (playbackThread != null) {
            playbackThread.interrupt();
        }
    }

    public boolean isPrepared() {
        return prepared;
    }

    private void prepareAndPlayBlocking()
            throws Exception {

        MediaExtractor extractor = null;
        MediaCodec decoder = null;

        int inputSamples = 0;
        int decodedFrames = 0;
        int renderedFrames = 0;
        int outputBuffers = 0;

        long playbackStartNs = 0;

        try {
            TraceLog.i(
                    "slot " +
                            slot +
                            " prepare begin file=" +
                            file.getName() +
                            " outputFps=" +
                            outputFps +
                            " targetFrames=" +
                            maxRenderedFrames
            );

            extractor = new MediaExtractor();

            extractor.setDataSource(
                    file.getAbsolutePath()
            );

            int videoTrack = -1;
            MediaFormat videoFormat = null;

            for (int i = 0;
                 i < extractor.getTrackCount();
                 i++) {

                MediaFormat format =
                        extractor.getTrackFormat(i);

                String mime =
                        format.getString(
                                MediaFormat.KEY_MIME
                        );

                if (mime != null &&
                        mime.startsWith("video/")) {

                    videoTrack = i;
                    videoFormat = format;
                    break;
                }
            }

            if (videoTrack < 0 ||
                    videoFormat == null) {

                throw new RuntimeException(
                        "No video track found"
                );
            }

            extractor.selectTrack(videoTrack);

            String mime =
                    videoFormat.getString(
                            MediaFormat.KEY_MIME
                    );

            decoder =
                    MediaCodec.createDecoderByType(
                            mime
                    );

            decoder.configure(
                    videoFormat,
                    outputSurface,
                    null,
                    0
            );

            decoder.start();

            TraceLog.i(
                    "slot " +
                            slot +
                            " codec started"
            );

            MediaCodec.BufferInfo info =
                    new MediaCodec.BufferInfo();

            boolean inputDone = false;
            boolean firstFrameReleased = false;

            /*
             * Preparation stage:
             * feed the decoder until one real frame is released to the
             * hidden SurfaceTexture. The GL callback confirms texture
             * readiness separately.
             */
            while (!cancelled &&
                    !firstFrameReleased) {

                if (!inputDone) {
                    int inputIndex =
                            decoder.dequeueInputBuffer(
                                    10_000
                            );

                    if (inputIndex >= 0) {
                        ByteBuffer inputBuffer =
                                decoder.getInputBuffer(
                                        inputIndex
                                );

                        if (inputBuffer == null) {
                            throw new RuntimeException(
                                    "Decoder input buffer null"
                            );
                        }

                        inputBuffer.clear();

                        int sampleSize =
                                extractor.readSampleData(
                                        inputBuffer,
                                        0
                                );

                        if (sampleSize < 0) {
                            decoder.queueInputBuffer(
                                    inputIndex,
                                    0,
                                    0,
                                    0,
                                    MediaCodec
                                            .BUFFER_FLAG_END_OF_STREAM
                            );

                            inputDone = true;
                        } else {
                            decoder.queueInputBuffer(
                                    inputIndex,
                                    0,
                                    sampleSize,
                                    extractor.getSampleTime(),
                                    0
                            );

                            inputSamples++;
                            extractor.advance();
                        }
                    }
                }

                int outputIndex =
                        decoder.dequeueOutputBuffer(
                                info,
                                10_000
                        );

                if (outputIndex ==
                        MediaCodec.INFO_TRY_AGAIN_LATER) {

                    continue;
                }

                if (outputIndex ==
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED ||
                        outputIndex ==
                                MediaCodec
                                        .INFO_OUTPUT_BUFFERS_CHANGED) {

                    continue;
                }

                if (outputIndex >= 0) {
                    outputBuffers++;

                    boolean hasFrame =
                            info.size > 0;

                    if (hasFrame) {
                        decoder.releaseOutputBuffer(
                                outputIndex,
                                true
                        );

                        decodedFrames++;
                        renderedFrames++;
                        firstFrameReleased = true;
                        prepared = true;

                        TraceLog.i(
                                "slot " +
                                        slot +
                                        " first frame released ptsUs=" +
                                        info.presentationTimeUs
                        );

                        if (preparedCallback != null) {
                            preparedCallback
                                    .onFirstFrameReleased();
                        }
                    } else {
                        decoder.releaseOutputBuffer(
                                outputIndex,
                                false
                        );
                    }
                }
            }

            while (!cancelled &&
                    !started) {

                try {
                    if (startLatch.await(
                            100,
                            TimeUnit.MILLISECONDS
                    )) {
                        break;
                    }
                } catch (InterruptedException e) {
                    if (cancelled) {
                        return;
                    }
                }
            }

            if (cancelled) {
                return;
            }

            playbackStartNs =
                    System.nanoTime();

            long frameIntervalNs =
                    1_000_000_000L /
                            outputFps;

            long nextFrameNs =
                    playbackStartNs +
                            frameIntervalNs;

            long playbackEndNs =
                    playbackStartNs +
                            ((long) playbackSeconds *
                                    1_000_000_000L);

            boolean outputDone = false;

            while (!cancelled &&
                    !outputDone &&
                    renderedFrames <
                            maxRenderedFrames) {

                if (!inputDone) {
                    int inputIndex =
                            decoder.dequeueInputBuffer(
                                    10_000
                            );

                    if (inputIndex >= 0) {
                        ByteBuffer inputBuffer =
                                decoder.getInputBuffer(
                                        inputIndex
                                );

                        if (inputBuffer == null) {
                            throw new RuntimeException(
                                    "Decoder input buffer null"
                            );
                        }

                        inputBuffer.clear();

                        int sampleSize =
                                extractor.readSampleData(
                                        inputBuffer,
                                        0
                                );

                        if (sampleSize < 0) {
                            decoder.queueInputBuffer(
                                    inputIndex,
                                    0,
                                    0,
                                    0,
                                    MediaCodec
                                            .BUFFER_FLAG_END_OF_STREAM
                            );

                            inputDone = true;
                        } else {
                            decoder.queueInputBuffer(
                                    inputIndex,
                                    0,
                                    sampleSize,
                                    extractor.getSampleTime(),
                                    0
                            );

                            inputSamples++;
                            extractor.advance();
                        }
                    }
                }

                int outputIndex =
                        decoder.dequeueOutputBuffer(
                                info,
                                10_000
                        );

                if (outputIndex ==
                        MediaCodec.INFO_TRY_AGAIN_LATER) {

                    continue;
                }

                if (outputIndex ==
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED ||
                        outputIndex ==
                                MediaCodec
                                        .INFO_OUTPUT_BUFFERS_CHANGED) {

                    continue;
                }

                if (outputIndex >= 0) {
                    outputBuffers++;

                    boolean eos =
                            (info.flags &
                                    MediaCodec
                                            .BUFFER_FLAG_END_OF_STREAM)
                                    != 0;

                    boolean hasFrame =
                            info.size > 0;

                    if (hasFrame &&
                            renderedFrames <
                                    maxRenderedFrames) {

                        decodedFrames++;

                        sleepUntil(
                                nextFrameNs -
                                        2_000_000L
                        );

                        decoder.releaseOutputBuffer(
                                outputIndex,
                                nextFrameNs
                        );

                        renderedFrames++;
                        nextFrameNs +=
                                frameIntervalNs;

                    } else {
                        decoder.releaseOutputBuffer(
                                outputIndex,
                                false
                        );
                    }

                    if (eos) {
                        outputDone = true;
                    }
                }
            }

            /*
             * Preserve exactly the selected three-second cycle. If the
             * recording has fewer frames than requested, its last frame
             * remains displayed until the cycle boundary.
             */
            sleepUntil(playbackEndNs);

            long playbackMs =
                    (System.nanoTime() -
                            playbackStartNs) /
                            1_000_000L;

            TraceLog.i(
                    "slot " +
                            slot +
                            " playback complete" +
                            " inputSamples=" +
                            inputSamples +
                            " decodedFrames=" +
                            decodedFrames +
                            " renderedFrames=" +
                            renderedFrames +
                            " outputBuffers=" +
                            outputBuffers +
                            " outputFps=" +
                            outputFps +
                            " durationMs=" +
                            playbackMs
            );

        } finally {
            if (decoder != null) {
                try {
                    decoder.stop();
                } catch (Exception ignored) {
                }

                try {
                    decoder.release();
                } catch (Exception ignored) {
                }
            }

            if (extractor != null) {
                try {
                    extractor.release();
                } catch (Exception ignored) {
                }
            }
        }

        if (!cancelled &&
                doneCallback != null) {

            doneCallback.onDone();
        }
    }

    private void sleepUntil(long targetNs) {
        while (!cancelled) {
            long remainingNs =
                    targetNs -
                            System.nanoTime();

            if (remainingNs <= 0) {
                return;
            }

            try {
                Thread.sleep(
                        remainingNs /
                                1_000_000L,
                        (int) (
                                remainingNs %
                                        1_000_000L
                        )
                );
            } catch (InterruptedException e) {
                if (cancelled) {
                    return;
                }
            }
        }
    }
}

// ============================================================
// SlowMo Lens
// File: MediaSurfaceRecorder.java
// Version: v0.6.1
// Build: Persistent Camera Session
// Date: 2026-07-25
// ============================================================

package com.jth.smm;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.view.Surface;

import java.io.File;
import java.nio.ByteBuffer;

/**
 * One MP4 clip recorder.
 *
 * The encoder and muxer are intentionally per-clip, but the input Surface may
 * be persistent and owned by MainActivity.  That stable Surface allows the
 * CameraCaptureSession to remain open while consecutive clips use fresh
 * MediaCodec/MediaMuxer instances.
 */
public class MediaSurfaceRecorder {
    static final String MIME = "video/avc";

    final int width;
    final int height;
    final int fps;
    final int bitrate;
    final File outputFile;
    final Surface suppliedInputSurface;
    final boolean ownsInputSurface;

    MediaCodec encoder;
    MediaMuxer muxer;
    Surface inputSurface;

    int trackIndex = -1;
    boolean muxerStarted = false;
    volatile boolean running = false;

    volatile int encodedSamples = 0;
    volatile long firstEncodedPtsUs = -1;
    volatile long lastEncodedPtsUs = -1;
    final java.util.ArrayList<Long> headEncodedPtsUs = new java.util.ArrayList<>();
    final java.util.ArrayList<Long> tailEncodedPtsUs = new java.util.ArrayList<>();

    Thread drainThread;

    /** Legacy constructor: creates and owns a one-use input Surface. */
    public MediaSurfaceRecorder(
            int width,
            int height,
            int fps,
            int bitrate,
            File outputFile
    ) {
        this(width, height, fps, bitrate, outputFile, null);
    }

    /**
     * Persistent-session constructor.
     * suppliedInputSurface must have been created by
     * MediaCodec.createPersistentInputSurface().
     */
    public MediaSurfaceRecorder(
            int width,
            int height,
            int fps,
            int bitrate,
            File outputFile,
            Surface suppliedInputSurface
    ) {
        this.width = width;
        this.height = height;
        this.fps = fps;
        this.bitrate = bitrate;
        this.outputFile = outputFile;
        this.suppliedInputSurface = suppliedInputSurface;
        this.ownsInputSurface = suppliedInputSurface == null;
    }

    public void prepare() throws Exception {
        MediaFormat format = MediaFormat.createVideoFormat(MIME, width, height);

        format.setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
        );
        format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);

        encoder = MediaCodec.createEncoderByType(MIME);
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);

        if (suppliedInputSurface != null) {
            inputSurface = suppliedInputSurface;
            encoder.setInputSurface(inputSurface);
        } else {
            inputSurface = encoder.createInputSurface();
        }

        muxer = new MediaMuxer(
                outputFile.getAbsolutePath(),
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
        );
    }

    public Surface getInputSurface() {
        return inputSurface;
    }

    public void start() {
        running = true;
        encoder.start();

        drainThread = new Thread(
                this::drainEncoder,
                "EncoderDrain-" + outputFile.getName()
        );
        drainThread.start();
    }

    public void stopAndRelease() {
        try {
            if (encoder != null) {
                encoder.signalEndOfInputStream();
            }
        } catch (Exception ignored) {
        }

        /*
         * Let the drain thread consume the EOS buffer.  It has its own bounded
         * wait, so a broken codec cannot hold shutdown forever.
         */
        try {
            if (drainThread != null) {
                drainThread.join(2000);
            }
        } catch (Exception ignored) {
        }

        running = false;

        try {
            if (encoder != null) {
                encoder.stop();
                encoder.release();
            }
        } catch (Exception ignored) {
        }

        try {
            if (muxerStarted && muxer != null) {
                muxer.stop();
            }
        } catch (Exception ignored) {
        }

        try {
            if (muxer != null) {
                muxer.release();
            }
        } catch (Exception ignored) {
        }

        /* MainActivity owns and retains a supplied persistent Surface. */
        if (ownsInputSurface) {
            try {
                if (inputSurface != null) {
                    inputSurface.release();
                }
            } catch (Exception ignored) {
            }
        }

        TraceLog.i("AUDIT encoder file=" + outputFile.getName() +
                " muxedSamples=" + encodedSamples +
                " firstPtsUs=" + firstEncodedPtsUs +
                " lastPtsUs=" + lastEncodedPtsUs +
                " spanUs=" + (firstEncodedPtsUs >= 0 ? lastEncodedPtsUs - firstEncodedPtsUs : -1) +
                " headPts=" + headEncodedPtsUs +
                " tailPts=" + tailEncodedPtsUs);

        encoder = null;
        muxer = null;
        inputSurface = null;
        drainThread = null;
    }

    void drainEncoder() {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        long eosWaitStartedMs = -1;

        while (running) {
            int status;
            try {
                status = encoder.dequeueOutputBuffer(info, 10000);
            } catch (Exception e) {
                android.util.Log.e("SlowMo240", "Encoder drain failed", e);
                break;
            }

            if (status == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (eosWaitStartedMs >= 0 &&
                        android.os.SystemClock.elapsedRealtime() - eosWaitStartedMs > 1500) {
                    android.util.Log.e("SlowMo240", "Timed out waiting for encoder EOS");
                    break;
                }
            } else if (status == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (muxerStarted) {
                    android.util.Log.e("SlowMo240", "Muxer format changed twice");
                    break;
                }

                MediaFormat newFormat = encoder.getOutputFormat();
                trackIndex = muxer.addTrack(newFormat);
                muxer.start();
                muxerStarted = true;

                android.util.Log.d(
                        "SlowMo240",
                        "Muxer started: " + outputFile.getAbsolutePath()
                );

            } else if (status >= 0) {
                ByteBuffer encodedData = encoder.getOutputBuffer(status);

                if (encodedData == null) {
                    android.util.Log.e("SlowMo240", "Encoder output buffer was null");
                    encoder.releaseOutputBuffer(status, false);
                    continue;
                }

                if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                    info.size = 0;
                }

                if (info.size != 0 && muxerStarted) {
                    encodedData.position(info.offset);
                    encodedData.limit(info.offset + info.size);
                    muxer.writeSampleData(trackIndex, encodedData, info);
                    encodedSamples++;
                    if (firstEncodedPtsUs < 0) firstEncodedPtsUs = info.presentationTimeUs;
                    lastEncodedPtsUs = info.presentationTimeUs;
                    if (headEncodedPtsUs.size() < 5) headEncodedPtsUs.add(info.presentationTimeUs);
                    if (tailEncodedPtsUs.size() == 5) tailEncodedPtsUs.remove(0);
                    tailEncodedPtsUs.add(info.presentationTimeUs);
                }

                encoder.releaseOutputBuffer(status, false);

                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    android.util.Log.d("SlowMo240", "Encoder end of stream");
                    break;
                }
            }

            /* signalEndOfInputStream() has been called once stop begins. */
            if (!running && eosWaitStartedMs < 0) {
                eosWaitStartedMs = android.os.SystemClock.elapsedRealtime();
            }
        }
    }
}

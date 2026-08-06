package com.jth.smm;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraConstrainedHighSpeedCaptureSession;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Range;
import android.util.Size;
import android.view.Surface;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class HighSpeedCaptureEngine {
    public static final int WIDTH = 1920;
    public static final int HEIGHT = 1080;
    public static final int TARGET_FPS = 240;
    public static final int BIT_RATE = 80_000_000;
    public static final long RECORD_DURATION_MS = 1000;

    public interface ReadyCallback {
        void onReady();
    }

    public interface ClipCallback {
        void onComplete(File file, boolean success, long sizeBytes);
    }

    public interface StatusCallback {
        void onStatus(String message);
    }

    private final Context context;
    private final Surface previewSurface;
    private final StatusCallback statusCallback;

    private CameraManager cameraManager;
    private CameraDevice cameraDevice;
    private CameraConstrainedHighSpeedCaptureSession session;

    private HandlerThread cameraThread;
    private Handler cameraHandler;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private MediaSurfaceRecorder recorder;
    private Surface encoderSurface;

    private boolean cameraReady = false;
    private boolean recording = false;
    private boolean shuttingDown = false;

    public HighSpeedCaptureEngine(
            Context context,
            SurfaceTexture previewTexture,
            StatusCallback statusCallback
    ) {
        this.context = context.getApplicationContext();
        this.statusCallback = statusCallback;

        previewTexture.setDefaultBufferSize(WIDTH, HEIGHT);
        previewSurface = new Surface(previewTexture);
    }

    public void open(ReadyCallback readyCallback) {
        TraceLog.i("CaptureEngine open");

        if (cameraReady && cameraDevice != null) {
            TraceLog.i("CaptureEngine already ready");

            if (readyCallback != null) {
                readyCallback.onReady();
            }

            return;
        }

        if (cameraThread == null) {
            cameraThread = new HandlerThread("CameraThread");
            cameraThread.start();
            cameraHandler = new Handler(cameraThread.getLooper());
        }

        cameraManager =
                (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);

        try {
            String cameraId = find240Camera();

            status("Opening camera " + cameraId);
            TraceLog.i("CaptureEngine openCamera begin id=" + cameraId);

            cameraManager.openCamera(
                    cameraId,
                    new CameraDevice.StateCallback() {
                        @Override
                        public void onOpened(CameraDevice device) {
                            TraceLog.i("CaptureEngine camera opened");

                            cameraDevice = device;
                            cameraReady = true;

                            status("Camera opened");

                            if (readyCallback != null) {
                                mainHandler.post(readyCallback::onReady);
                            }
                        }

                        @Override
                        public void onDisconnected(CameraDevice device) {
                            TraceLog.i("CaptureEngine camera disconnected");

                            cameraReady = false;
                            device.close();

                            if (cameraDevice == device) {
                                cameraDevice = null;
                            }

                            status("Camera disconnected");
                        }

                        @Override
                        public void onError(CameraDevice device, int error) {
                            TraceLog.i("CaptureEngine camera error=" + error);

                            cameraReady = false;
                            device.close();

                            if (cameraDevice == device) {
                                cameraDevice = null;
                            }

                            status("Camera error " + error);
                        }
                    },
                    cameraHandler
            );

        } catch (Exception e) {
            TraceLog.e("CaptureEngine open failed", e);
            status("Camera open error: " + e);
        }
    }

    public void recordClip(File outputFile, ClipCallback callback) {
        TraceLog.i("CaptureEngine recordClip enter file=" + outputFile.getName());

        if (shuttingDown) {
            TraceLog.i("CaptureEngine recordClip rejected: shutting down");
            complete(callback, outputFile, false);
            return;
        }

        if (!cameraReady || cameraDevice == null) {
            TraceLog.i("CaptureEngine recordClip rejected: camera not ready");
            status("Camera not ready");
            complete(callback, outputFile, false);
            return;
        }

        if (recording) {
            TraceLog.i("CaptureEngine recordClip rejected: already recording");
            status("Already recording");
            complete(callback, outputFile, false);
            return;
        }

        recording = true;

        try {
            TraceLog.i("CaptureEngine recorder create");

            recorder = new MediaSurfaceRecorder(
                    WIDTH,
                    HEIGHT,
                    TARGET_FPS,
                    BIT_RATE,
                    outputFile
            );

            TraceLog.i("CaptureEngine recorder prepare begin");
            recorder.prepare();
            TraceLog.i("CaptureEngine recorder prepare done");

            encoderSurface = recorder.getInputSurface();

            ArrayList<Surface> surfaces = new ArrayList<>();
            surfaces.add(previewSurface);
            surfaces.add(encoderSurface);

            status("Creating high-speed camera session");
            TraceLog.i("CaptureEngine create session begin");

            cameraDevice.createConstrainedHighSpeedCaptureSession(
                    surfaces,
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(CameraCaptureSession rawSession) {
                            TraceLog.i("CaptureEngine session configured");

                            try {
                                session =
                                        (CameraConstrainedHighSpeedCaptureSession) rawSession;

                                CaptureRequest.Builder requestBuilder =
                                        cameraDevice.createCaptureRequest(
                                                CameraDevice.TEMPLATE_RECORD
                                        );

                                requestBuilder.addTarget(previewSurface);
                                requestBuilder.addTarget(encoderSurface);

                                requestBuilder.set(
                                        CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                                        new Range<Integer>(
                                                TARGET_FPS,
                                                TARGET_FPS
                                        )
                                );

                                requestBuilder.set(
                                        CaptureRequest.CONTROL_MODE,
                                        CaptureRequest.CONTROL_MODE_AUTO
                                );

                                requestBuilder.set(
                                        CaptureRequest.CONTROL_AE_MODE,
                                        CaptureRequest.CONTROL_AE_MODE_ON
                                );

                                List<CaptureRequest> burst =
                                        session.createHighSpeedRequestList(
                                                requestBuilder.build()
                                        );

                                TraceLog.i("CaptureEngine recorder.start begin");
                                recorder.start();
                                TraceLog.i("CaptureEngine recorder.start done");

                                TraceLog.i("CaptureEngine setRepeatingBurst begin");
                                session.setRepeatingBurst(
                                        burst,
                                        null,
                                        cameraHandler
                                );
                                TraceLog.i("CaptureEngine setRepeatingBurst done");

                                status("Recording 1.0 seconds");
                                TraceLog.i(
                                        "CaptureEngine recording timer started " +
                                                RECORD_DURATION_MS + " ms"
                                );

                                mainHandler.postDelayed(
                                        () -> stopCurrentRecording(
                                                outputFile,
                                                callback
                                        ),
                                        RECORD_DURATION_MS
                                );

                            } catch (Exception e) {
                                TraceLog.e(
                                        "CaptureEngine onConfigured failed",
                                        e
                                );

                                cleanupFailedRecording();
                                complete(callback, outputFile, false);
                            }
                        }

                        @Override
                        public void onConfigureFailed(
                                CameraCaptureSession failedSession
                        ) {
                            TraceLog.i(
                                    "CaptureEngine session configuration failed"
                            );

                            cleanupFailedRecording();
                            status("High-speed camera session failed");
                            complete(callback, outputFile, false);
                        }
                    },
                    cameraHandler
            );

        } catch (Exception e) {
            TraceLog.e("CaptureEngine recordClip failed", e);

            cleanupFailedRecording();
            status("Recording error: " + e);
            complete(callback, outputFile, false);
        }
    }

    private void stopCurrentRecording(
            File outputFile,
            ClipCallback callback
    ) {
        TraceLog.i(
                "CaptureEngine stopCurrentRecording enter file=" +
                        outputFile.getName()
        );

        status("Stopping recording");

        try {
            if (session != null) {
                TraceLog.i("CaptureEngine session.stopRepeating begin");
                session.stopRepeating();
                TraceLog.i("CaptureEngine session.stopRepeating done");

                TraceLog.i("CaptureEngine session.close begin");
                session.close();
                TraceLog.i("CaptureEngine session.close done");

                session = null;
            }

            if (recorder != null) {
                TraceLog.i("CaptureEngine recorder.stopAndRelease begin");
                recorder.stopAndRelease();
                TraceLog.i("CaptureEngine recorder.stopAndRelease done");

                recorder = null;
            }

            releaseEncoderSurface();

            recording = false;

            long size = outputFile.length();
            boolean success = size > 0;

            TraceLog.i(
                    "CaptureEngine clip complete file=" +
                            outputFile.getName() +
                            " size=" + size +
                            " success=" + success
            );

            status(
                    success
                            ? "Saved " + size + " bytes"
                            : "Recording failed: empty file"
            );

            complete(callback, outputFile, success);

        } catch (Exception e) {
            TraceLog.e("CaptureEngine stop failed", e);

            recording = false;
            releaseEncoderSurface();

            status("Stop recording error: " + e);
            complete(callback, outputFile, false);
        }
    }

    private void cleanupFailedRecording() {
        TraceLog.i("CaptureEngine cleanupFailedRecording");

        try {
            if (session != null) {
                session.close();
                session = null;
            }
        } catch (Exception e) {
            TraceLog.e("CaptureEngine session cleanup failed", e);
        }

        try {
            if (recorder != null) {
                recorder.stopAndRelease();
                recorder = null;
            }
        } catch (Exception e) {
            TraceLog.e("CaptureEngine recorder cleanup failed", e);
        }

        releaseEncoderSurface();
        recording = false;
    }

    private void releaseEncoderSurface() {
        try {
            if (encoderSurface != null) {
                encoderSurface.release();
                encoderSurface = null;
            }
        } catch (Exception e) {
            TraceLog.e("CaptureEngine encoder surface release failed", e);
        }
    }

    private void complete(
            ClipCallback callback,
            File file,
            boolean success
    ) {
        long size = file == null ? 0 : file.length();

        if (callback != null) {
            mainHandler.post(
                    () -> callback.onComplete(file, success, size)
            );
        }
    }

    private String find240Camera() throws Exception {
        TraceLog.i("CaptureEngine find240Camera begin");

        for (String id : cameraManager.getCameraIdList()) {
            CameraCharacteristics characteristics =
                    cameraManager.getCameraCharacteristics(id);

            StreamConfigurationMap map =
                    characteristics.get(
                            CameraCharacteristics
                                    .SCALER_STREAM_CONFIGURATION_MAP
                    );

            if (map == null) {
                continue;
            }

            Range<Integer>[] ranges =
                    map.getHighSpeedVideoFpsRangesFor(
                            new Size(WIDTH, HEIGHT)
                    );

            if (ranges == null) {
                continue;
            }

            for (Range<Integer> range : ranges) {
                TraceLog.i(
                        "CaptureEngine camera=" + id +
                                " range=" + range
                );

                if (range.getLower() == TARGET_FPS &&
                        range.getUpper() == TARGET_FPS) {
                    TraceLog.i(
                            "CaptureEngine selected camera=" + id
                    );
                    return id;
                }
            }
        }

        throw new RuntimeException(
                "No 1920x1080 240fps camera found"
        );
    }

    private void status(String message) {
        TraceLog.i("CaptureEngine status: " + message);

        if (statusCallback != null) {
            mainHandler.post(
                    () -> statusCallback.onStatus(message)
            );
        }
    }

    public boolean isReady() {
        return cameraReady && cameraDevice != null;
    }

    public boolean isRecording() {
        return recording;
    }

    public Surface getPreviewSurface() {
        return previewSurface;
    }

    public void shutdown() {
        TraceLog.i("CaptureEngine shutdown");

        shuttingDown = true;
        cameraReady = false;
        recording = false;

        try {
            mainHandler.removeCallbacksAndMessages(null);
        } catch (Exception ignored) {
        }

        try {
            if (session != null) {
                session.close();
                session = null;
            }
        } catch (Exception e) {
            TraceLog.e("CaptureEngine shutdown session error", e);
        }

        try {
            if (recorder != null) {
                recorder.stopAndRelease();
                recorder = null;
            }
        } catch (Exception e) {
            TraceLog.e("CaptureEngine shutdown recorder error", e);
        }

        releaseEncoderSurface();

        try {
            if (cameraDevice != null) {
                cameraDevice.close();
                cameraDevice = null;
            }
        } catch (Exception e) {
            TraceLog.e("CaptureEngine shutdown camera error", e);
        }

        try {
            previewSurface.release();
        } catch (Exception e) {
            TraceLog.e("CaptureEngine shutdown preview surface error", e);
        }

        try {
            if (cameraThread != null) {
                cameraThread.quitSafely();
                cameraThread = null;
                cameraHandler = null;
            }
        } catch (Exception e) {
            TraceLog.e("CaptureEngine shutdown thread error", e);
        }
    }
}
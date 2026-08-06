// ============================================================
// SlowMo Lens
// File: CameraDiagnostics.java
// Version: v0.6.0
// Build: CameraLab - Diagnostics Extraction
// Date: 2026-07-21
// ============================================================

package com.jth.smm;

import android.os.SystemClock;

import java.util.Locale;

/**
 * Owns camera-pipeline lifecycle counters and their human-readable HUD text.
 *
 * This class deliberately contains no Camera2 objects and cannot change capture
 * behaviour. MainActivity reports events to it; it records and formats them.
 */
public final class CameraDiagnostics {
    private final long appStartMs;

    private int cameraOpenRequests;
    private int cameraOpenCount;
    private int cameraDisconnectCount;
    private int cameraErrorCount;
    private int cameraCloseCount;

    private int sessionCreateCount;
    private int sessionConfiguredCount;
    private int sessionFailedCount;
    private int sessionClosedCount;
    private int currentSessionId;

    private int recorderPrepareCount;
    private int recorderStartCount;
    private int recorderStopCount;

    private int previewSurfaceCount;
    private int encoderSurfaceCount;

    private long cameraOpenedMs = -1L;
    private String cameraState = "CLOSED";
    private String recorderState = "IDLE";
    private String lastEvent = "Diagnostics created";

    public CameraDiagnostics(long appStartMs) {
        this.appStartMs = appStartMs;
        mark("LIFE", lastEvent);
    }

    public synchronized void cameraOpenRequested(String cameraId) {
        cameraOpenRequests++;
        cameraState = "OPENING";
        mark("CAM", "open requested camera=" + cameraId +
                " request=" + cameraOpenRequests);
    }

    public synchronized void cameraOpened(String cameraId) {
        cameraOpenCount++;
        cameraOpenedMs = SystemClock.elapsedRealtime();
        cameraState = "OPEN";
        mark("CAM", "opened camera=" + cameraId +
                " count=" + cameraOpenCount);
    }

    public synchronized void cameraDisconnected() {
        cameraDisconnectCount++;
        cameraOpenedMs = -1L;
        cameraState = "DISCONNECTED";
        mark("CAM", "disconnected count=" + cameraDisconnectCount);
    }

    public synchronized void cameraError(int error) {
        cameraErrorCount++;
        cameraOpenedMs = -1L;
        cameraState = "ERROR " + error;
        mark("CAM", "error code=" + error +
                " count=" + cameraErrorCount);
    }

    public synchronized void cameraClosed() {
        cameraCloseCount++;
        cameraOpenedMs = -1L;
        cameraState = "CLOSED";
        mark("CAM", "closed count=" + cameraCloseCount);
    }

    public synchronized int sessionCreating(boolean highSpeed, String reason) {
        sessionCreateCount++;
        currentSessionId = sessionCreateCount;
        mark("SESSION", "create #" + currentSessionId +
                " highSpeed=" + highSpeed +
                " reason=" + reason);
        return currentSessionId;
    }

    public synchronized void sessionConfigured(int sessionId) {
        sessionConfiguredCount++;
        currentSessionId = sessionId;
        mark("SESSION", "configured #" + sessionId +
                " configuredCount=" + sessionConfiguredCount);
    }

    public synchronized void sessionConfigureFailed(int sessionId) {
        sessionFailedCount++;
        mark("SESSION", "configure failed #" + sessionId +
                " failedCount=" + sessionFailedCount);
    }

    public synchronized void sessionClosed(int sessionId) {
        sessionClosedCount++;
        mark("SESSION", "closed #" + sessionId +
                " closedCount=" + sessionClosedCount);
    }

    public synchronized void recorderPrepared() {
        recorderPrepareCount++;
        recorderState = "PREPARED";
        mark("REC", "prepared count=" + recorderPrepareCount);
    }

    public synchronized void recorderStarted() {
        recorderStartCount++;
        recorderState = "RECORDING";
        mark("REC", "started count=" + recorderStartCount);
    }

    public synchronized void recorderStopped() {
        recorderStopCount++;
        recorderState = "STOPPED";
        mark("REC", "stopped count=" + recorderStopCount);
    }

    public synchronized void previewSurfaceCreated() {
        previewSurfaceCount++;
        mark("SURFACE", "preview created #" + previewSurfaceCount);
    }

    public synchronized void encoderSurfaceCreated() {
        encoderSurfaceCount++;
        mark("SURFACE", "encoder created #" + encoderSurfaceCount);
    }

    public synchronized void note(String category, String event) {
        mark(category, event);
    }

    public synchronized String hudText() {
        long now = SystemClock.elapsedRealtime();
        long appUptimeMs = Math.max(0L, now - appStartMs);
        long cameraUptimeMs = cameraOpenedMs < 0L
                ? 0L
                : Math.max(0L, now - cameraOpenedMs);

        return String.format(
                Locale.US,
                "CameraLab diagnostics" +
                        "\nCamera: %s (%s)" +
                        "\nOpen requests/opens: %d/%d" +
                        "\nDisconnects/errors/closes: %d/%d/%d" +
                        "\nSessions create/config/fail/closed: %d/%d/%d/%d" +
                        "\nCurrent session: #%d" +
                        "\nRecorder: %s  prep/start/stop: %d/%d/%d" +
                        "\nSurfaces preview/encoder: %d/%d" +
                        "\nApp uptime: %s" +
                        "\nCamera uptime: %s" +
                        "\nLIFE last: %s",
                cameraState,
                cameraOpenedMs < 0L ? "-" : formatDuration(cameraUptimeMs),
                cameraOpenRequests,
                cameraOpenCount,
                cameraDisconnectCount,
                cameraErrorCount,
                cameraCloseCount,
                sessionCreateCount,
                sessionConfiguredCount,
                sessionFailedCount,
                sessionClosedCount,
                currentSessionId,
                recorderState,
                recorderPrepareCount,
                recorderStartCount,
                recorderStopCount,
                previewSurfaceCount,
                encoderSurfaceCount,
                formatDuration(appUptimeMs),
                formatDuration(cameraUptimeMs),
                lastEvent
        );
    }

    private void mark(String category, String event) {
        lastEvent = category + " " + event;
        TraceLog.i(lastEvent);
    }

    private static String formatDuration(long durationMs) {
        long totalSeconds = durationMs / 1000L;
        long hours = totalSeconds / 3600L;
        long minutes = (totalSeconds % 3600L) / 60L;
        long seconds = totalSeconds % 60L;
        long millis = durationMs % 1000L;

        return String.format(
                Locale.US,
                "%02d:%02d:%02d.%03d",
                hours,
                minutes,
                seconds,
                millis
        );
    }
}

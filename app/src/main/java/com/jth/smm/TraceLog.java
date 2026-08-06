// ============================================================
// SlowMo Lens
// File: TraceLog.java
// Version: v0.4.5
// Build: Capture Options
// Date: 2026-07-17
// ============================================================

package com.jth.smm;

import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.util.Locale;

public final class TraceLog {
    private static final Object LOCK = new Object();
    private static final long START_MS =
            android.os.SystemClock.elapsedRealtime();

    private static PrintWriter writer;
    private static File traceFile;

    private TraceLog() {
    }

    public static void init(File directory) {
        synchronized (LOCK) {
            close();

            try {
                if (directory != null &&
                        !directory.exists()) {
                    directory.mkdirs();
                }

                traceFile = new File(
                        directory,
                        "smm_trace_" +
                                System.currentTimeMillis() +
                                ".txt"
                );

                writer = new PrintWriter(
                        new OutputStreamWriter(
                                new FileOutputStream(
                                        traceFile,
                                        true
                                ),
                                "UTF-8"
                        ),
                        true
                );

                i("TraceLog init: " +
                        traceFile.getAbsolutePath());

            } catch (Exception e) {
                writer = null;
                Log.e("SlowMo240",
                        "TraceLog init failed", e);
            }
        }
    }

    public static String path() {
        synchronized (LOCK) {
            return traceFile == null
                    ? "(trace unavailable)"
                    : traceFile.getAbsolutePath();
        }
    }

    public static void i(String message) {
        write(message, null);
    }

    public static void e(
            String message,
            Throwable error
    ) {
        write(message, error);
    }

    private static void write(
            String message,
            Throwable error
    ) {
        synchronized (LOCK) {
            long elapsed =
                    android.os.SystemClock
                            .elapsedRealtime() -
                            START_MS;

            String line =
                    String.format(
                            Locale.US,
                            "%07d [%s] %s",
                            elapsed,
                            Thread.currentThread().getName(),
                            message
                    );

            if (error == null) {
                Log.i("SlowMo240", line);
            } else {
                Log.e("SlowMo240", line, error);
            }

            if (writer != null) {
                writer.println(line);
                if (error != null) {
                    error.printStackTrace(writer);
                }
                writer.flush();
            }
        }
    }

    public static void close() {
        synchronized (LOCK) {
            if (writer != null) {
                writer.flush();
                writer.close();
                writer = null;
            }
        }
    }
}

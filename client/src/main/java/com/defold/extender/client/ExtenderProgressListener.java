package com.defold.extender.client;

/**
 * Receives live build-progress updates while ExtenderClient.build(...) is
 * waiting for the server to finish a build.
 *
 * Progress is advisory: it may stop arriving at any time (old server,
 * dropped connection) while the build itself keeps running. Completion is
 * always determined by the build call returning or throwing.
 *
 * Callbacks are invoked on a background thread, never on the thread that
 * called build(...).
 */
public interface ExtenderProgressListener {
    /**
     * @param stage       Current build stage, e.g. "SDK", "DEPENDENCIES",
     *                    "COMPILING", "LINKING", "PACKAGING", "SUCCESS", "ERROR"
     * @param detail      Human-readable detail line, e.g. the extension being compiled. May be null.
     * @param percent     Overall progress estimate 0-100, never decreasing.
     * @param currentFile Files compiled so far for the current extension, or -1 when not compiling.
     * @param totalFiles  Total files to compile for the current extension, or -1 when not compiling.
     */
    void onProgress(String stage, String detail, int percent, int currentFile, int totalFiles);
}

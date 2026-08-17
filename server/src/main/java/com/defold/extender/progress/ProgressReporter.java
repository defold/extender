package com.defold.extender.progress;

/**
 * Sink for build progress. Implementations must tolerate calls from
 * multiple threads: per-file callbacks arrive concurrently from the
 * compile thread pool (see ProcessExecutor.executeCommands).
 *
 * All methods default to no-ops so the build pipeline can be used
 * without progress tracking (NOOP is the default in Extender.Builder).
 */
public interface ProgressReporter {

    ProgressReporter NOOP = new ProgressReporter() {};

    /** The build entered a new stage. */
    default void stage(BuildStage stage, String detail) {}

    /**
     * A batch of compile commands is about to run for the given extension
     * (or pod). Adds totalFiles to the extension's and the job's file totals.
     * May be called more than once per extension (e.g. swift + objc batches).
     */
    default void compileBatchBegin(String extension, int totalFiles) {}

    /** One source file of the given extension finished compiling. Thread-safe. */
    default void fileCompiled(String extension) {}

    /** The build finished. Must be reported only after the result/error file is in place. */
    default void terminal(boolean success, String detail) {}
}

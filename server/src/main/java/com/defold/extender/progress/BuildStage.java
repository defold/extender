package com.defold.extender.progress;

/**
 * Stages of an async build, in the order they normally occur.
 * REMOTE_BUILDING is a coarse stage used by a frontend instance when the
 * remote builder does not expose detailed progress.
 */
public enum BuildStage {
    RECEIVED,
    QUEUED,
    SDK,
    DEPENDENCIES,
    MANIFESTS,
    PLATFORM,
    COMPILING,
    LINKING,
    PACKAGING,
    REMOTE_BUILDING,
    SUCCESS,
    ERROR;

    public boolean isTerminal() {
        return this == SUCCESS || this == ERROR;
    }
}

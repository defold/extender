package com.defold.extender.client;

/**
 * Build stage names passed to ExtenderProgressListener.onProgress(...).
 * They match the names of the server's BuildStage enum.
 */
public final class ExtenderBuildStage {
    public static final String RECEIVED = "RECEIVED";
    public static final String QUEUED = "QUEUED";
    public static final String SDK = "SDK";
    public static final String DEPENDENCIES = "DEPENDENCIES";
    public static final String MANIFESTS = "MANIFESTS";
    public static final String PLATFORM = "PLATFORM";
    public static final String COMPILING = "COMPILING";
    public static final String LINKING = "LINKING";
    public static final String PACKAGING = "PACKAGING";
    public static final String REMOTE_BUILDING = "REMOTE_BUILDING";
    public static final String SUCCESS = "SUCCESS";
    public static final String ERROR = "ERROR";

    private ExtenderBuildStage() {
    }
}

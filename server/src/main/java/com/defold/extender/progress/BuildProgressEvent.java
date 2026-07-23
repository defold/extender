package com.defold.extender.progress;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * A single progress update for an async build job. Serialized to JSON and
 * pushed to subscribers as an SSE "progress" event, with {@link #getSeq()}
 * as the SSE event id (used for Last-Event-ID replay).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class BuildProgressEvent {
    private final String jobId;
    private final long seq;
    private final long ts;
    private final BuildStage stage;
    private final String detail;
    private final int percent;
    private final String extension;
    private final Integer currentFile;
    private final Integer totalFiles;
    private final boolean terminal;

    public BuildProgressEvent(String jobId, long seq, long ts, BuildStage stage, String detail,
                              int percent, String extension, Integer currentFile, Integer totalFiles) {
        this.jobId = jobId;
        this.seq = seq;
        this.ts = ts;
        this.stage = stage;
        this.detail = detail;
        this.percent = percent;
        this.extension = extension;
        this.currentFile = currentFile;
        this.totalFiles = totalFiles;
        this.terminal = stage.isTerminal();
    }

    public String getJobId() { return jobId; }
    public long getSeq() { return seq; }
    public long getTs() { return ts; }
    public BuildStage getStage() { return stage; }
    public String getDetail() { return detail; }
    public int getPercent() { return percent; }
    public String getExtension() { return extension; }
    public Integer getCurrentFile() { return currentFile; }
    public Integer getTotalFiles() { return totalFiles; }
    public boolean isTerminal() { return terminal; }
}

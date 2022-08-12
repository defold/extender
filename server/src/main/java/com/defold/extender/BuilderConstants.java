package com.defold.extender;

public class BuilderConstants {
    public static final String BUILD_RESULT_FILENAME = "build.zip";
    public static final String BUILD_ERROR_FILENAME = "error.txt";
    
    public enum JobStatus {
        NOT_FOUND,
        SUCCESS,
        ERROR
    }
}

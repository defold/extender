package com.defold.extender.remote;

public class RemoteBuildException extends RuntimeException  {

    public RemoteBuildException(String message, Throwable cause) {
        super(message, cause);
    }

    public RemoteBuildException(String message) {
        super(message);
    }
}

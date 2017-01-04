package com.defold.extender.client;

public class ExtenderClientException extends Exception {

    public ExtenderClientException(String message) {
        super(message);
    }

    public ExtenderClientException(String message, Throwable cause) {
        super(message, cause);
    }
}

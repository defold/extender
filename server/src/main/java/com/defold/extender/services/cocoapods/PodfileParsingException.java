package com.defold.extender.services.cocoapods;

import com.defold.extender.BuildFailedException;

public class PodfileParsingException extends BuildFailedException {
    public PodfileParsingException(String reason) {
        super(reason);
    }

    public PodfileParsingException(String reason, Exception cause) {
        super(cause, reason);
    }
}

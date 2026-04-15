package com.defold.extender.services.cocoapods;

import com.defold.extender.ExtenderException;

public class PodfileParsingException extends ExtenderException {
    public PodfileParsingException(String reason) {
        super(reason);
    }

    public PodfileParsingException(String reason, Exception cause) {
        super(cause, reason);
    }
}

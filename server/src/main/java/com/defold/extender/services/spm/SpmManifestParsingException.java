package com.defold.extender.services.spm;

import com.defold.extender.ExtenderException;

public class SpmManifestParsingException extends ExtenderException {
    public SpmManifestParsingException(String reason) {
        super(reason);
    }

    public SpmManifestParsingException(String reason, Exception cause) {
        super(cause, reason);
    }
}

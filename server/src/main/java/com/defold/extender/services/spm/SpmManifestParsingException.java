package com.defold.extender.services.spm;

import com.defold.extender.BuildFailedException;

public class SpmManifestParsingException extends BuildFailedException {
    public SpmManifestParsingException(String reason) {
        super(reason);
    }

    public SpmManifestParsingException(String reason, Exception cause) {
        super(cause, reason);
    }
}

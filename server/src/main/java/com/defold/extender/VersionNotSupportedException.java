package com.defold.extender;

public class VersionNotSupportedException extends ExtenderException {
    private static String ERROR_MESSAGE = "Engine version '%s' is not supported on the current server. Please, use latest stable version. https://github.com/defold/defold/releases/latest";

    public VersionNotSupportedException(String version) {
        super(String.format(ERROR_MESSAGE, version));
    }

    public VersionNotSupportedException(Exception e, String version) {
        super(e, String.format(ERROR_MESSAGE, version));
    }
}

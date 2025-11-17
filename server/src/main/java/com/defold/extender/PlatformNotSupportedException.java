package com.defold.extender;

public class PlatformNotSupportedException extends ExtenderException {
    private static String ERROR_MESSAGE = "Platform '%s' is not supported on the current server. Please, check build server address. If error will persist - create task here https://github.com/defold/extender/issues";

    public PlatformNotSupportedException(String platform) {
        super(String.format(ERROR_MESSAGE, platform));
    }

    public PlatformNotSupportedException(Exception e, String platform) {
        super(e, String.format(ERROR_MESSAGE, platform));
    }
}

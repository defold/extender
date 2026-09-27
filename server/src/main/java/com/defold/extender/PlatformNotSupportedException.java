package com.defold.extender;

public class PlatformNotSupportedException extends ExtenderException {
    private static String ERROR_MESSAGE = "Platform '%s' is not supported on the current server. Please, check build server address. If error will persist - create task here https://github.com/defold/extender/issues";

    public PlatformNotSupportedException(String platform) {
        super(String.format(ERROR_MESSAGE, platform));
    }

    public PlatformNotSupportedException(Exception e, String platform) {
        super(e, String.format(ERROR_MESSAGE, platform));
    }

    public PlatformNotSupportedException(String platform, String sdkVersion) {
        super(String.format("Platform '%s' is not supported on the current server for engine '%s': no configured SDK source contains this platform.", platform, sdkVersion));
    }

    public PlatformNotSupportedException(String platform, String sdkVersion, String builderKey) {
        super(String.format("Platform '%s' is not supported on the current server for engine '%s': remote builder '%s' is not configured or remote building is disabled.", platform, sdkVersion, builderKey));
    }
}

package com.defold.extender.services.data;

import com.defold.extender.ExtenderException;

public record SdkSelection(String sourceId, String sdkName, String sdkVersion) {
    public static final String SOURCE_HEADER = "X-Extender-Sdk-Source";
    public static final String NAME_HEADER = "X-Extender-Sdk-Name";
    public static final String VERSION_HEADER = "X-Extender-Sdk-Version";

    public static SdkSelection fromHeaders(String sourceId, String sdkName, String sdkVersion) throws ExtenderException {
        if (sourceId == null && sdkName == null && sdkVersion == null) {
            return null;
        }
        if (sourceId == null || !sourceId.matches("[a-f0-9]{64}")
                || sdkName == null || !sdkName.matches("[A-Za-z0-9_.-]{1,128}")
                || sdkVersion == null || !sdkVersion.matches("[A-Za-z0-9_.-]{1,128}")) {
            throw new ExtenderException("Invalid or incomplete SDK source selection");
        }
        return new SdkSelection(sourceId, sdkName, sdkVersion);
    }

    public void validate(ResolvedSdk resolved) throws ExtenderException {
        if (!equals(resolved.selection())) {
            throw new ExtenderException("Selected SDK source does not match the SDK name/version required by the frontend");
        }
    }
}

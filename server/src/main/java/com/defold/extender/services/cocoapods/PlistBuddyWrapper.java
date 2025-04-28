package com.defold.extender.services.cocoapods;

import java.io.File;
import java.util.List;

import org.apache.commons.text.StringEscapeUtils;

import com.defold.extender.ExtenderException;
import com.defold.extender.process.ProcessUtils;

public class PlistBuddyWrapper {

    public static class PlistKeys {
        static public String BUNDLE_NAME = "CFBundleName";
        static public String BUNDLE_IDENTIFIER = "CFBundleIdentifier";
        static public String BUNDLE_PACKAGE_TYPE = "CFBundlePackageType";
        static public String BUNDLE_VERSION = "CFBundleVersion";
        static public String BUNDLE_SHORT_VERSION = "CFBundleShortVersionString";
        static public String SUPPORTED_PLATFORMS = "CFBundleSupportedPlatforms";
        static public String BUNDLE_INFO_DICTIONARY_VERSION = "CFBundleInfoDictionaryVersion";
        static public String MINIMUM_OS_VERSION = "MinimumOSVersion";
        static public String DEVICE_FAMILY = "UIDeviceFamily";
    }

    public static class PlistValueConstants {
        static public String TYPE_BUNDLE = "BNDL";
        static public String PLIST_VERSION = "6.0";
    }

    static String PLIST_BUDDY_EXEC = "/usr/libexec/PlistBuddy";

    static public void createEmptyPlist(File targetFile) throws ExtenderException {
        ProcessUtils.execCommand(List.of(
            PLIST_BUDDY_EXEC,
            "-c",
            "Clear",
            targetFile.getAbsolutePath()
        ), null, null);
    }

    static public void addStringProperty(File targetFile, String propertyName, String propertyValue) throws ExtenderException {
        ProcessUtils.execCommand(List.of(
            PLIST_BUDDY_EXEC,
            "-c",
            String.format("Add :%s string %s", StringEscapeUtils.escapeXSI(propertyName), StringEscapeUtils.escapeXSI(propertyValue)),
            targetFile.getAbsolutePath()
        ), null, null);
    }

    static public void addStringArrayProperty(File targetFile, String propertyName, String[] values) throws ExtenderException {
        ProcessUtils.execCommand(List.of(
            PLIST_BUDDY_EXEC,
            "-c",
            String.format("Add :%s array", StringEscapeUtils.escapeXSI(propertyName)),
            targetFile.getAbsolutePath()
        ), null, null);

        for (int idx = 0; idx < values.length; ++idx) {
            ProcessUtils.execCommand(List.of(
                PLIST_BUDDY_EXEC,
                "-c",
                String.format("Add :%s:%d string %s", StringEscapeUtils.escapeXSI(propertyName), idx, StringEscapeUtils.escapeXSI(values[idx])),
                targetFile.getAbsolutePath()
            ), null, null);
        }
    }

    static public void addIntegerArrayProperty(File targetFile, String propertyName, int[] values) throws ExtenderException {
        ProcessUtils.execCommand(List.of(
            PLIST_BUDDY_EXEC,
            "-c",
            String.format("Add :%s array", StringEscapeUtils.escapeXSI(propertyName)),
            targetFile.getAbsolutePath()
        ), null, null);

        for (int idx = 0; idx < values.length; ++idx) {
            ProcessUtils.execCommand(List.of(
                PLIST_BUDDY_EXEC,
                "-c",
                String.format("Add :%s:%d integer %s", StringEscapeUtils.escapeXSI(propertyName), idx, values[idx]),
                targetFile.getAbsolutePath()
            ), null, null);
        }
    }

    public static class CreateBundlePlistArgs {
        public String bundleId;
        public String bundleName;
        public String version;
        public String shortVersion;
        public String minVersion;
        public String[] supportedPlatforms;
    }

    static public void createBundleInfoPlist(File targetFile, CreateBundlePlistArgs args) throws ExtenderException {
        createEmptyPlist(targetFile);
        addStringProperty(targetFile, PlistKeys.BUNDLE_NAME, args.bundleName);
        addStringProperty(targetFile, PlistKeys.BUNDLE_IDENTIFIER, args.bundleId);
        addStringProperty(targetFile, PlistKeys.BUNDLE_PACKAGE_TYPE, PlistValueConstants.TYPE_BUNDLE);
        addStringProperty(targetFile, PlistKeys.BUNDLE_VERSION, args.version);
        addStringProperty(targetFile, PlistKeys.BUNDLE_SHORT_VERSION, args.shortVersion);
        addStringProperty(targetFile, PlistKeys.BUNDLE_INFO_DICTIONARY_VERSION, PlistValueConstants.PLIST_VERSION);
        addStringProperty(targetFile, PlistKeys.MINIMUM_OS_VERSION, args.minVersion);
        addStringArrayProperty(targetFile, PlistKeys.SUPPORTED_PLATFORMS, args.supportedPlatforms);
        addIntegerArrayProperty(targetFile, PlistKeys.DEVICE_FAMILY, new int[] { 1, 2 });  // by default add both device type: tablet and phone
    }


}

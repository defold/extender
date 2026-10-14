package com.defold.extender.services.cocoapods;

import java.io.File;
import java.util.List;

import org.apache.commons.text.StringEscapeUtils;

import com.defold.extender.ExtenderException;
import com.defold.extender.process.ProcessUtils;

/**
 * PlistBuddy runs inside the process sandbox, which needs a working directory that contains the
 * plist being edited: callers pass the job directory.
 */
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

    static public void createEmptyPlist(File targetFile, File cwd) throws ExtenderException {
        ProcessUtils.execCommand(List.of(
            PLIST_BUDDY_EXEC,
            "-c",
            "Clear",
            targetFile.getAbsolutePath()
        ), cwd, null);
    }

    static public void addStringProperty(File targetFile, String propertyName, String propertyValue, File cwd) throws ExtenderException {
        ProcessUtils.execCommand(List.of(
            PLIST_BUDDY_EXEC,
            "-c",
            String.format("Add :%s string %s", StringEscapeUtils.escapeXSI(propertyName), StringEscapeUtils.escapeXSI(propertyValue)),
            targetFile.getAbsolutePath()
        ), cwd, null);
    }

    static public void addStringArrayProperty(File targetFile, String propertyName, String[] values, File cwd) throws ExtenderException {
        ProcessUtils.execCommand(List.of(
            PLIST_BUDDY_EXEC,
            "-c",
            String.format("Add :%s array", StringEscapeUtils.escapeXSI(propertyName)),
            targetFile.getAbsolutePath()
        ), cwd, null);

        for (int idx = 0; idx < values.length; ++idx) {
            ProcessUtils.execCommand(List.of(
                PLIST_BUDDY_EXEC,
                "-c",
                String.format("Add :%s:%d string %s", StringEscapeUtils.escapeXSI(propertyName), idx, StringEscapeUtils.escapeXSI(values[idx])),
                targetFile.getAbsolutePath()
            ), cwd, null);
        }
    }

    static public void addIntegerArrayProperty(File targetFile, String propertyName, int[] values, File cwd) throws ExtenderException {
        ProcessUtils.execCommand(List.of(
            PLIST_BUDDY_EXEC,
            "-c",
            String.format("Add :%s array", StringEscapeUtils.escapeXSI(propertyName)),
            targetFile.getAbsolutePath()
        ), cwd, null);

        for (int idx = 0; idx < values.length; ++idx) {
            ProcessUtils.execCommand(List.of(
                PLIST_BUDDY_EXEC,
                "-c",
                String.format("Add :%s:%d integer %s", StringEscapeUtils.escapeXSI(propertyName), idx, values[idx]),
                targetFile.getAbsolutePath()
            ), cwd, null);
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

    static public void createBundleInfoPlist(File targetFile, CreateBundlePlistArgs args, File cwd) throws ExtenderException {
        createEmptyPlist(targetFile, cwd);
        addStringProperty(targetFile, PlistKeys.BUNDLE_NAME, args.bundleName, cwd);
        addStringProperty(targetFile, PlistKeys.BUNDLE_IDENTIFIER, args.bundleId, cwd);
        addStringProperty(targetFile, PlistKeys.BUNDLE_PACKAGE_TYPE, PlistValueConstants.TYPE_BUNDLE, cwd);
        addStringProperty(targetFile, PlistKeys.BUNDLE_VERSION, args.version, cwd);
        addStringProperty(targetFile, PlistKeys.BUNDLE_SHORT_VERSION, args.shortVersion, cwd);
        addStringProperty(targetFile, PlistKeys.BUNDLE_INFO_DICTIONARY_VERSION, PlistValueConstants.PLIST_VERSION, cwd);
        addStringProperty(targetFile, PlistKeys.MINIMUM_OS_VERSION, args.minVersion, cwd);
        addStringArrayProperty(targetFile, PlistKeys.SUPPORTED_PLATFORMS, args.supportedPlatforms, cwd);
        addIntegerArrayProperty(targetFile, PlistKeys.DEVICE_FAMILY, new int[] { 1, 2 }, cwd);  // by default add both device type: tablet and phone
    }


}

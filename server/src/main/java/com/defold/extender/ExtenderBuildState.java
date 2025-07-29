package com.defold.extender;

import java.io.File;

public class ExtenderBuildState {
    static final String APPMANIFEST_BASE_VARIANT_KEYWORD = "baseVariant";
    static final String APPMANIFEST_WITH_SYMBOLS_KEYWORD = "withSymbols";
    static final String APPMANIFEST_BUILD_ARTIFACTS_KEYWORD = "buildArtifacts";
    static final String APPMANIFEST_JETIFIER_KEYWORD = "jetifier";
    static final String APPMANIFEST_DEBUG_SOURCE_PATH = "debugSourcePath";

    File jobDir;
    File uploadDir;
    File buildDir;
    File sdk;
    String buildConfiguration;  // debug/release/headless
    String fullPlatform;
    String arch;
    String hostPlatform;
    private final String buildArtifacts;
    private final String debugSourcePath;

    private final Boolean withSymbols;
    private final Boolean useJetifier;

    ExtenderBuildState(Extender.Builder builder, AppManifestConfiguration appManifest) throws ExtenderException {
        jobDir = builder.jobDirectory;
        buildDir = builder.buildDirectory;
        uploadDir = builder.uploadDirectory;
        fullPlatform = builder.platform;
        sdk = builder.sdk;
        arch = fullPlatform.split("-")[0];

        String baseVariant = ExtenderUtil.getAppManifestContextString(appManifest, APPMANIFEST_BASE_VARIANT_KEYWORD, null);

        this.useJetifier = ExtenderUtil.getAppManifestBoolean(appManifest, builder.platform, APPMANIFEST_JETIFIER_KEYWORD, true);
        this.withSymbols = ExtenderUtil.getAppManifestContextBoolean(appManifest, APPMANIFEST_WITH_SYMBOLS_KEYWORD, true);
        this.buildArtifacts = ExtenderUtil.getAppManifestContextString(appManifest, APPMANIFEST_BUILD_ARTIFACTS_KEYWORD, "");
        this.debugSourcePath = ExtenderUtil.getAppManifestContextString(appManifest, APPMANIFEST_DEBUG_SOURCE_PATH, null);
        // assign configuration names started with upper letter because it used for cocoapods
        if (baseVariant != null && (baseVariant.equals("release") || baseVariant.equals("headless"))) {
            this.buildConfiguration = "Release";
        } else {
            this.buildConfiguration = "Debug";
        }

        String os = System.getProperty("os.name");
        String arch = System.getProperty("os.arch");

        // These host names are using the Defold SDK names
        if (os.contains("Mac")) {
            if (arch.contains("aarch64")) {
                this.hostPlatform = "arm64-macos";
            } else {
                this.hostPlatform = "x86_64-macos";
            }
        } else if (os.contains("Windows")) {
            this.hostPlatform = "x86_64-win32";
        } else {
            if (arch.contains("aarch64")) {
                this.hostPlatform = "arm64-linux";
            } else {
                this.hostPlatform = "x86_64-linux";
            }
        }
    }

    public File getJobDir() {
        return jobDir;
    }

    public File getBuildDir() {
        return buildDir;
    }

    public File getUploadDir() {
        return uploadDir;
    }

    public String getBuildPlatform() {
        return fullPlatform;
    }

    public String getBuildArch() {
        return arch;
    }

    public String getBuildConfiguration() {
        return buildConfiguration;
    }

    public String getHostPlatform() {
        return hostPlatform;
    }

    public String getBuildArtifacts() {
        return buildArtifacts;
    }

    public String getDebugSourcePath() {
        return debugSourcePath;
    }

    public Boolean isNeedSymbols() {
        return withSymbols;
    }

    public Boolean isUsedJetifier() {
        return useJetifier;
    }
}

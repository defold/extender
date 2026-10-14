package com.defold.extender.services.spm;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import com.defold.extender.ExtenderBuildState;
import com.defold.extender.process.JobFiles;
import com.defold.extender.services.cocoapods.PodUtils;

/**
 * Per-job working directory layout and platform mapping for the SPM wrapper build:
 *
 * <jobDir>/SwiftPackageManagerService/
 *   Package/{Package.swift, Sources/SpmDeps/Empty.swift}   # generated aggregator package
 *   Wrapper/{project.yml, Sources/Dummy.swift}             # XcodeGen spec; xcodegen emits SpmWrapper.xcodeproj here
 *   DerivedData/                                           # per-job xcodebuild -derivedDataPath
 *   ModuleCache/                                           # per-job CLANG_MODULE_CACHE_PATH
 *   build.log                                              # captured xcodebuild output
 */
public class SpmServiceBuildState {

    public static final String WRAPPER_NAME = "SpmWrapper";
    public static final String AGGREGATOR_NAME = "SpmDeps";
    static final String BUILD_CONFIGURATION = "Release";

    File workingDir;
    File packageDir;
    File wrapperDir;
    File derivedDataDir;
    File moduleCacheDir;
    File clonedSourcePackagesDir;
    File buildLogFile;
    PodUtils.Platform selectedPlatform;
    String buildArch;

    SpmServiceBuildState() { }

    SpmServiceBuildState(ExtenderBuildState extenderBuildState) throws IOException {
        File jobDir = extenderBuildState.getJobDir();
        this.workingDir = new File(jobDir, "SwiftPackageManagerService");
        this.packageDir = new File(workingDir, "Package");
        this.wrapperDir = new File(workingDir, "Wrapper");
        this.derivedDataDir = new File(workingDir, "DerivedData");
        this.moduleCacheDir = new File(workingDir, "ModuleCache");
        this.clonedSourcePackagesDir = new File(workingDir, "clonedSourcePackages");
        this.buildLogFile = new File(workingDir, "build.log");
        // an earlier step of this job may have left a link somewhere on the way to workingDir;
        // plain mkdirs() would create the missing tail through it instead of refusing
        JobFiles.createDirectories(jobDir, new File(packageDir, "Sources/" + AGGREGATOR_NAME));
        JobFiles.createDirectories(jobDir, new File(wrapperDir, "Sources"));
        JobFiles.createDirectories(jobDir, this.derivedDataDir);
        JobFiles.createDirectories(jobDir, this.moduleCacheDir);
        JobFiles.createDirectories(jobDir, this.clonedSourcePackagesDir);

        String platform = extenderBuildState.getBuildPlatform();
        this.buildArch = PodUtils.archFromPlatform(platform);
        this.selectedPlatform = PodUtils.Platform.fromExtenderPlatform(platform);
    }

    /** The job directory the working dir lives in; sandboxed helper tools run there. */
    public File getJobDir() {
        return workingDir.getParentFile();
    }

    public File getWorkingDir() {
        return workingDir;
    }

    public File getPackageDir() {
        return packageDir;
    }

    public File getWrapperDir() {
        return wrapperDir;
    }

    public File getDerivedDataDir() {
        return derivedDataDir;
    }

    public File getModuleCacheDir() {
        return moduleCacheDir;
    }

    public File getClonedSourcePackagesDir() {
        return clonedSourcePackagesDir;
    }

    public File getBuildLogFile() {
        return buildLogFile;
    }

    public PodUtils.Platform getSelectedPlatform() {
        return selectedPlatform;
    }

    public File getXcodeProjDir() {
        return new File(wrapperDir, WRAPPER_NAME + ".xcodeproj");
    }

    /** Xcode's expected location of the wrapper project's Package.resolved lockfile. */
    public File getLockFile() {
        return new File(getXcodeProjDir(), "project.xcworkspace/xcshareddata/swiftpm/Package.resolved");
    }

    public String getDestination() {
        switch (selectedPlatform) {
            case IPHONEOS: return "generic/platform=iOS";
            case IPHONESIMULATOR: return "generic/platform=iOS Simulator";
            case MACOSX: return "generic/platform=macOS";
            default: throw new IllegalStateException("Unsupported platform " + selectedPlatform);
        }
    }

    /**
     * Explicit build settings pinning the build to the single architecture of this job.
     * A generic iOS device build is arm64-only by default; simulator and macOS builds
     * would otherwise emit every architecture of the host toolchain.
     */
    public List<String> getExtraBuildSettings() {
        List<String> settings = new ArrayList<>();
        if (selectedPlatform != PodUtils.Platform.IPHONEOS) {
            settings.add("ARCHS=" + buildArch);
            settings.add("ONLY_ACTIVE_ARCH=NO");
        }
        return settings;
    }

    String getProductsDirName() {
        switch (selectedPlatform) {
            case IPHONEOS: return BUILD_CONFIGURATION + "-iphoneos";
            case IPHONESIMULATOR: return BUILD_CONFIGURATION + "-iphonesimulator";
            case MACOSX: return BUILD_CONFIGURATION;
            default: throw new IllegalStateException("Unsupported platform " + selectedPlatform);
        }
    }

    public File getProductsDir() {
        return new File(derivedDataDir, "Build/Products/" + getProductsDirName());
    }

    /** The directory holding the generated <Module>.modulemap and <Module>-Swift.h files. */
    public File getGeneratedModuleMapsDir() {
        File intermediates = new File(derivedDataDir, "Build/Intermediates.noindex");
        File withSuffix = new File(intermediates, "GeneratedModuleMaps-" + selectedPlatform.toString().toLowerCase());
        if (withSuffix.isDirectory()) {
            return withSuffix;
        }
        return new File(intermediates, "GeneratedModuleMaps");
    }
}

package com.defold.extender.services.cocoapods;

import java.io.File;
import java.nio.file.Path;

import com.defold.extender.ExtenderBuildState;
import com.defold.extender.ExtenderUtil;

public class CocoaPodsServiceBuildState {
    File workingDir;
    File podsDir;
    File unpackedFrameworksDir;
    File targetSupportFilesDir;
    PodSpecParser.Platform selectedPlatform;

    CocoaPodsServiceBuildState(ExtenderBuildState extenderBuildState) {
        this.workingDir = new File(extenderBuildState.getJobDir(), "CocoaPodsService");
        this.unpackedFrameworksDir = Path.of(extenderBuildState.getBuildDir().toString(), "Debugiphoneos", "XCFrameworkIntermediates").toFile();
        this.podsDir = new File(workingDir, "Pods");
        this.workingDir.mkdirs();
        this.unpackedFrameworksDir.mkdirs();
        this.podsDir.mkdirs();

        this.selectedPlatform = PodSpecParser.Platform.UNKNOWN;
        String platform = extenderBuildState.getBuildPlatform();
        if (ExtenderUtil.isIOSTarget(platform)) {
            this.selectedPlatform = extenderBuildState.getBuildArch().equals("arm64") ? PodSpecParser.Platform.IPHONEOS : PodSpecParser.Platform.IPHONESIMULATOR;
        } else if (ExtenderUtil.isMacOSTarget(platform)) {
            this.selectedPlatform = PodSpecParser.Platform.MACOSX;
        }

        this.targetSupportFilesDir = new File(this.podsDir, "Target Support Files");
    }

    public File getWorkingDir() {
        return workingDir;
    }

    public File getPodsDir() {
        return podsDir;
    }

    public File getUnpackedFrameworksDir() {
        return unpackedFrameworksDir;
    }

    public PodSpecParser.Platform getSelectedPlatform() {
        return selectedPlatform;
    }

    public File getTargetSupportFilesDir() {
        return targetSupportFilesDir;
    }
}

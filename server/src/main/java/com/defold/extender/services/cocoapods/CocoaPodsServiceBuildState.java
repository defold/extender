package com.defold.extender.services.cocoapods;

import java.io.File;
import java.nio.file.Path;

import com.defold.extender.ExtenderBuildState;

public class CocoaPodsServiceBuildState {
    File workingDir;
    File podsDir;
    File unpackedFrameworksDir;
    File targetSupportFilesDir;
    PodUtils.Platform selectedPlatform;

    CocoaPodsServiceBuildState() { }

    CocoaPodsServiceBuildState(ExtenderBuildState extenderBuildState) {
        this.workingDir = new File(extenderBuildState.getJobDir(), "CocoaPodsService");
        this.workingDir.mkdirs();
        this.podsDir = new File(workingDir, "Pods");
        this.podsDir.mkdirs();

        this.selectedPlatform = PodUtils.Platform.fromExtenderPlatform(extenderBuildState.getBuildPlatform());
        this.unpackedFrameworksDir = Path.of(
            extenderBuildState.getBuildDir().toString(),
             String.format("%s%s", extenderBuildState.getBuildConfiguration(), this.selectedPlatform.toString().toLowerCase()),
            "XCFrameworkIntermediates"
        ).toFile();
        this.unpackedFrameworksDir.mkdirs();

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

    public PodUtils.Platform getSelectedPlatform() {
        return selectedPlatform;
    }

    public File getTargetSupportFilesDir() {
        return targetSupportFilesDir;
    }
}

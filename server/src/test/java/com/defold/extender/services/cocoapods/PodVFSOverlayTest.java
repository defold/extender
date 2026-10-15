package com.defold.extender.services.cocoapods;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.defold.extender.utils.PodBuildUtil;

public class PodVFSOverlayTest {

    private static PodBuildSpec spec(Path jobDir, String name, boolean withSources) throws IOException {
        PodBuildSpec spec = new PodBuildSpec();
        spec.name = name;
        Path intermediate = Files.createDirectories(jobDir.resolve(name).resolve("intermediate"));
        spec.vfsOverlay = intermediate.resolve("all_files.yaml").toFile();
        if (withSources) {
            spec.swiftSourceFiles.add(jobDir.resolve(name).resolve("Placeholder.swift").toFile());
        }
        return spec;
    }

    @Test
    public void generateVFSOverlaySkipsBinaryOnlyDependency(@TempDir Path jobDir) throws IOException {
        // Google-Mobile-Ads-SDK has a placeholder Swift source and depends on the binary-only GoogleUserMessagingPlatform
        PodBuildSpec binaryDep = spec(jobDir, "GoogleUserMessagingPlatform", false);
        PodBuildSpec main = spec(jobDir, "Google-Mobile-Ads-SDK", true);
        main.dependantSpecs.add(binaryDep);

        File header = jobDir.resolve("GADMobileAds.h").toFile();
        File overlay = PodBuildUtil.generateVFSOverlay(jobDir.toFile(), main, Map.of("Headers", List.of(header)));

        assertTrue(overlay.isFile());
        assertFalse(binaryDep.vfsOverlay.exists());
        assertTrue(Files.readString(overlay.toPath()).contains("GADMobileAds.h"));
    }

    @Test
    public void generateVFSOverlayMergesDependencyWithSources(@TempDir Path jobDir) throws IOException {
        PodBuildSpec sourceDep = spec(jobDir, "Dep", true);
        PodBuildUtil.generateVFSOverlay(jobDir.toFile(), sourceDep, Map.of("DepHeaders", List.of(jobDir.resolve("dep.h").toFile())));
        PodBuildSpec main = spec(jobDir, "Main", true);
        main.dependantSpecs.add(sourceDep);

        File overlay = PodBuildUtil.generateVFSOverlay(jobDir.toFile(), main, Map.of("MainHeaders", List.of(jobDir.resolve("main.h").toFile())));

        String content = Files.readString(overlay.toPath());
        assertTrue(content.contains("MainHeaders"), content);
        assertTrue(content.contains("DepHeaders"), content);
    }
}

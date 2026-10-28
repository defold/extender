package com.defold.extender.services.spm;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.defold.extender.ExtenderBuildState;

public class SpmServiceBuildStateTest {

    private static ExtenderBuildState buildStateFor(Path jobDir) {
        ExtenderBuildState extenderBuildState = mock(ExtenderBuildState.class);
        when(extenderBuildState.getJobDir()).thenReturn(jobDir.toFile());
        when(extenderBuildState.getBuildPlatform()).thenReturn("arm64-ios");
        return extenderBuildState;
    }

    @Test
    public void constructorCreatesTheWorkingTreeInsideTheJobDirectory(@TempDir Path jobDir) throws IOException {
        SpmServiceBuildState state = new SpmServiceBuildState(buildStateFor(jobDir));

        assertTrue(Files.isDirectory(state.getPackageDir().toPath().resolve("Sources/SpmDeps")));
        assertTrue(Files.isDirectory(state.getWrapperDir().toPath().resolve("Sources")));
        assertTrue(Files.isDirectory(state.getDerivedDataDir().toPath()));
        assertTrue(Files.isDirectory(state.getModuleCacheDir().toPath()));
        assertTrue(Files.isDirectory(state.getClonedSourcePackagesDir().toPath()));
    }

    @Test
    public void constructorRefusesAWorkingDirectoryLinkedOutOfTheJob(@TempDir Path root) throws IOException {
        Path jobDir = Files.createDirectory(root.resolve("job"));
        Path outside = Files.createDirectory(root.resolve("outside"));
        // an earlier step of the same job (e.g. a resolved CocoaPods dependency's own tooling,
        // which can run with write access to the whole job directory) left a link here
        Files.createSymbolicLink(jobDir.resolve("SwiftPackageManagerService"), outside);

        assertThrows(IOException.class, () -> new SpmServiceBuildState(buildStateFor(jobDir)));
        assertFalse(Files.exists(outside.resolve("Package")));
    }
}

package com.defold.extender.builders;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.defold.extender.process.SandboxPolicy;

/**
 * The split that keeps one C# build from leaving a binary the next one runs or links: the cache a
 * build restores into is its own, and the cache it shares with other builds is read-only.
 */
public class CSharpNuGetCacheTest {

    @Test
    public void perJobCacheIsWritableAndExecutable(@TempDir Path root) {
        File perJob = root.resolve("job/.nuget").toFile();
        SandboxPolicy policy = CSharpBuilder.dotnetPolicy(perJob, null, root.resolve("job/.dotnet").toFile());

        // ilc is restored into this cache and then executed out of it
        assertEquals(1, policy.readWriteExecPaths().size(), policy.toString());
        assertTrue(policy.readWriteExecPaths().contains(perJob.getAbsolutePath()), policy.toString());
        assertEquals(perJob.getAbsolutePath(), policy.env().get("NUGET_PACKAGES"));
        // the restore needs the network
        assertEquals(SandboxPolicy.Network.ALL, policy.network());
    }

    @Test
    public void sharedCacheIsOnlyEverReadable(@TempDir Path root) {
        File perJob = root.resolve("job/.nuget").toFile();
        File shared = root.resolve("shared").toFile();
        SandboxPolicy policy = CSharpBuilder.dotnetPolicy(perJob, shared, root.resolve("job/.dotnet").toFile());

        assertTrue(policy.readOnlyPaths().contains(shared.getAbsolutePath()), policy.toString());
        assertFalse(policy.readWritePaths().contains(shared.getAbsolutePath()), policy.toString());
        assertFalse(policy.readWriteExecPaths().contains(shared.getAbsolutePath()), policy.toString());
        // NuGet reads packages from a fallback folder and never writes to one
        assertEquals(shared.getAbsolutePath(), policy.env().get("NUGET_FALLBACK_PACKAGES"));
    }

    @Test
    public void withoutASharedCacheNothingIsGrantedOrAnnounced(@TempDir Path root) {
        File perJob = root.resolve("job/.nuget").toFile();
        SandboxPolicy policy = CSharpBuilder.dotnetPolicy(perJob, null, root.resolve("job/.dotnet").toFile());

        assertTrue(policy.readOnlyPaths().isEmpty(), policy.toString());
        assertNull(policy.env().get("NUGET_FALLBACK_PACKAGES"));
    }
}

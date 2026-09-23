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

    /**
     * libRuntime.WorkstationGC.a references do_vxsort_avx2, which lives in its own archive that the
     * runtime pack ships for the x64 runtime identifiers only. Adding it for Windows alone left
     * osx-x64 and linux-x64 with an undefined symbol at link time.
     */
    @Test
    public void vxsortIsLinkedForX64TargetsOnly(@TempDir Path root) throws Exception {
        assertTrue(linkFlagsFor("x86_64-osx", root).contains("libRuntime.VxsortEnabled.a"));
        assertTrue(linkFlagsFor("x86_64-linux", root).contains("libRuntime.VxsortEnabled.a"));
        assertTrue(linkFlagsFor("x86_64-win32", root).contains("Runtime.VxsortEnabled.lib"));
        assertFalse(linkFlagsFor("arm64-osx", root).contains("VxsortEnabled"));
        assertFalse(linkFlagsFor("arm64-ios", root).contains("VxsortEnabled"));
        assertFalse(linkFlagsFor("arm64-android", root).contains("VxsortEnabled"));
    }

    private static String linkFlagsFor(String platform, Path buildDir) throws Exception {
        java.util.Map<String, Object> context = new java.util.HashMap<>();
        CSharpBuilder.updateContext(platform, buildDir.toFile(), context);
        return String.join(" ", (java.util.List<String>) context.get("linkFlags"));
    }
}

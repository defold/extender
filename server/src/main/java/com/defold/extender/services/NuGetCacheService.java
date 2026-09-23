package com.defold.extender.services;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;

import com.defold.extender.process.ProcessExecutor;
import com.defold.extender.process.SandboxPolicy;

/**
 * Keeps the NuGet package cache out of the space builds share with each other.
 *
 * <p>A C# build restores its packages into a cache of its own inside the job directory, which it
 * may write and execute and which dies with the job. That alone would cost a ~350 MB restore per
 * build, so a second cache is kept for the whole instance and handed to the build read-only
 * through {@code NUGET_FALLBACK_PACKAGES}: NuGet resolves packages from a fallback folder and
 * never writes to one, so nothing a build runs can leave anything there for the next build.
 *
 * <p>This service is the only thing that ever writes that shared cache, and it does so by
 * publishing a generated stub project - no uploaded source, no user-controlled package reference.
 * Warming is strictly an optimisation: a runtime identifier nobody warmed simply restores into
 * the job's own cache, more slowly.
 */
@Service
public class NuGetCacheService {
    private static final Logger LOGGER = LoggerFactory.getLogger(NuGetCacheService.class);

    private static final String DOTNET_ROOT = System.getenv("DOTNET_ROOT");
    private static final String NUGET_PACKAGES = System.getenv("NUGET_PACKAGES");

    private static volatile NuGetCacheService current;

    /**
     * Where the .NET runtime keeps the cross-process synchronisation objects behind a named
     * Mutex: POSIX shared memory under {@code shm/} and session directories under
     * {@code lockfiles/}. NuGet takes the "NuGet-Migrations" mutex on every restore, so a
     * {@code dotnet} command that cannot write here fails before it resolves anything. The path
     * is fixed in the runtime's PAL and follows neither TMPDIR nor DOTNET_CLI_HOME (verified),
     * so it has to be granted. It holds no packages and nothing executable - the exposure is
     * that builds can see each other's lock objects, not each other's code.
     */
    private static final String DOTNET_RUNTIME_STATE_DIR = "/tmp/.dotnet";

    /**
     * macOS TLS: .NET validates a server certificate through the Security framework, which needs
     * securityd. Without it every https source fails with "bad certificate format" and no package
     * is ever fetched (verified: this one service is enough; trustd alone is not). The Landlock
     * backend ignores Mach services.
     */
    public static final List<String> DOTNET_MACH_SERVICES = List.of("com.apple.SecurityServer");

    /** Granted to every sandboxed {@code dotnet}; created because a missing path is not granted. */
    public static File dotnetRuntimeStateDir() {
        File dir = new File(DOTNET_RUNTIME_STATE_DIR);
        dir.mkdirs();
        return dir;
    }

    /**
     * The package-resolution properties of {@code template.csproj}. The stub has to pull exactly
     * what a real build pulls, or the build downloads the difference itself, so the two must be
     * kept in step; the stub carries no project reference and no sources of its own.
     */
    private static final String STUB_PROJECT = """
            <Project Sdk="Microsoft.NET.Sdk">
              <PropertyGroup>
                <TargetFramework>net9.0</TargetFramework>
                <PublishAot>true</PublishAot>
                <NativeLib>static</NativeLib>
                <OutputType>Library</OutputType>
                <PublishTrimmed>true</PublishTrimmed>
                <PublishAotUsingRuntimePack>true</PublishAotUsingRuntimePack>
                <HybridGlobalization>false</HybridGlobalization>
                <IlcUseEnvironmentalTools>True</IlcUseEnvironmentalTools>
                <DisableUnsupportedError>True</DisableUnsupportedError>
              </PropertyGroup>
            </Project>
            """;

    @Value("${extender.csharp.warm-runtime-identifiers:}")
    String warmRuntimeIdentifiers;

    @Value("${extender.csharp.nuget-fallback-dir:}")
    String configuredFallbackDir;

    @Value("${extender.csharp.warm-wait-timeout:600000}")
    long warmWaitTimeoutMillis;

    // one at a time: concurrent restores into one folder contend for NuGet's own locks
    private final ExecutorService warmer = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "nuget-cache-warmer");
        t.setDaemon(true);
        return t;
    });
    private final Map<String, CompletableFuture<Void>> warming = new LinkedHashMap<>();

    public static NuGetCacheService current() {
        return current;
    }

    /**
     * The shared cache, or null when there is none to offer: a build then restores everything
     * into its own. Never returned while empty, because an empty fallback folder only adds a
     * path to the sandbox policy for no benefit.
     */
    public File fallbackDir() {
        String configured = configuredFallbackDir != null && !configuredFallbackDir.isBlank()
                ? configuredFallbackDir.strip()
                : NUGET_PACKAGES;
        if (configured == null || configured.isBlank()) {
            return null;
        }
        File dir = new File(configured);
        String[] entries = dir.list();
        return entries != null && entries.length > 0 ? dir : null;
    }

    /**
     * Blocks until the shared cache holds this runtime identifier, when a warm for it is still
     * running. Returns either way: the restore that follows is correct with or without it.
     */
    public void awaitWarm(String runtimeIdentifier) {
        CompletableFuture<Void> future;
        synchronized (warming) {
            future = warming.get(runtimeIdentifier);
        }
        if (future == null || future.isDone()) {
            return;
        }
        LOGGER.info("Waiting for the NuGet cache warm of {} to finish before restoring", runtimeIdentifier);
        try {
            future.get(warmWaitTimeoutMillis, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            // the build restores into its own cache instead, which is slower and still correct
            LOGGER.warn("Not waiting for the NuGet cache warm of {} any longer: {}", runtimeIdentifier, e.toString());
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    public void warmOnStartup() {
        List<String> identifiers = runtimeIdentifiers();
        if (identifiers.isEmpty()) {
            return;
        }
        if (DOTNET_ROOT == null || DOTNET_ROOT.isBlank()) {
            LOGGER.warn("extender.csharp.warm-runtime-identifiers is set but DOTNET_ROOT is not; not warming");
            return;
        }
        File target = targetDir();
        if (target == null) {
            LOGGER.warn("No NuGet cache directory to warm (set extender.csharp.nuget-fallback-dir or NUGET_PACKAGES)");
            return;
        }
        // in the background: a Windows builder warms three runtime identifiers, over a gigabyte,
        // and must not hold up readiness for it
        synchronized (warming) {
            for (String identifier : identifiers) {
                warming.put(identifier, CompletableFuture.runAsync(() -> warm(identifier, target), warmer));
            }
        }
        LOGGER.info("Warming the shared NuGet cache {} for {} in the background", target, identifiers);
    }

    private void warm(String runtimeIdentifier, File target) {
        long start = System.currentTimeMillis();
        Path stub = null;
        try {
            target.mkdirs();
            stub = Files.createTempDirectory("extender-nuget-warm");
            Files.writeString(stub.resolve("warm.csproj"), STUB_PROJECT, StandardCharsets.UTF_8);
            Files.writeString(stub.resolve("warm.cs"), "internal class Warm {}\n", StandardCharsets.UTF_8);

            ProcessExecutor executor = new ProcessExecutor();
            executor.setCwd(stub.toFile());
            executor.putEnv("NUGET_PACKAGES", target.getAbsolutePath());
            executor.putEnv("DOTNET_CLI_HOME", stub.resolve(".dotnet").toString());
            try {
                // publish, not restore: the NativeAOT runtime pack
                // (microsoft.netcore.app.runtime.nativeaot.<rid>, ~90 MB and the one the link line
                // is built from) is resolved at publish time only, so a restore-warmed cache still
                // leaves every build downloading it.
                executor.execute(List.of(DOTNET_ROOT + "/dotnet", "publish", "-c", "Release", "-r", runtimeIdentifier),
                        SandboxPolicy.dependencyResolver(
                                List.of(target.getAbsolutePath(), dotnetRuntimeStateDir().getAbsolutePath()))
                                .withMachServices(DOTNET_MACH_SERVICES));
            } catch (IOException e) {
                // The publish runs to the platform linker, which an image with no C toolchain does
                // not have, and a stub with no real code has nothing worth linking anyway. Every
                // package is restored well before that point, which is all this is for.
                LOGGER.debug("dotnet publish for {} stopped early: {}", runtimeIdentifier, e.getMessage());
            }
            String[] warmed = target.list();
            if (warmed == null || warmed.length == 0) {
                LOGGER.warn("Nothing was warmed into the NuGet cache for {}; builds will restore their own",
                        runtimeIdentifier);
            } else {
                LOGGER.info("Warmed the NuGet cache for {} in {} ms", runtimeIdentifier,
                        System.currentTimeMillis() - start);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            // builds still work, they just restore everything themselves
            LOGGER.warn("Could not warm the NuGet cache for {}: {}", runtimeIdentifier, e.toString());
        } finally {
            if (stub != null) {
                FileUtils.deleteQuietly(stub.toFile());
            }
        }
    }

    private File targetDir() {
        String configured = configuredFallbackDir != null && !configuredFallbackDir.isBlank()
                ? configuredFallbackDir.strip()
                : NUGET_PACKAGES;
        return configured == null || configured.isBlank() ? null : new File(configured);
    }

    private List<String> runtimeIdentifiers() {
        List<String> identifiers = new ArrayList<>();
        if (warmRuntimeIdentifiers == null) {
            return identifiers;
        }
        for (String identifier : warmRuntimeIdentifiers.split(",")) {
            if (!identifier.isBlank()) {
                identifiers.add(identifier.strip());
            }
        }
        return identifiers;
    }

    /**
     * {@link com.defold.extender.builders.CSharpBuilder} is constructed per build rather than
     * injected, and reads the cache from two static methods, so the instance is published here
     * as {@link com.defold.extender.process.ProcessSandbox} does. {@link #current()} is null
     * until the context comes up and in unit tests, which callers read as "no shared cache".
     */
    @PostConstruct
    void install() {
        current = this;
    }
}

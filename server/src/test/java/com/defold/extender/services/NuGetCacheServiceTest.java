package com.defold.extender.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class NuGetCacheServiceTest {

    /**
     * An empty shared cache is worth nothing and would only add a path to every policy, so the
     * service reports it as absent until something has actually been warmed into it.
     */
    @Test
    public void anEmptyOrMissingSharedCacheIsReportedAsAbsent(@TempDir Path root) throws IOException {
        NuGetCacheService service = new NuGetCacheService();

        service.configuredFallbackDir = root.resolve("does-not-exist").toString();
        assertNull(service.fallbackDir());

        Path empty = Files.createDirectory(root.resolve("empty"));
        service.configuredFallbackDir = empty.toString();
        assertNull(service.fallbackDir());

        Files.createDirectory(empty.resolve("microsoft.dotnet.ilcompiler"));
        assertEquals(empty.toFile(), service.fallbackDir());
    }

    /** Nothing may block on a warm that was never started - there is no warming in unit tests. */
    @Test
    public void awaitingAnUnwarmedRuntimeIdentifierReturns() {
        new NuGetCacheService().awaitWarm("linux-x64");
    }
}

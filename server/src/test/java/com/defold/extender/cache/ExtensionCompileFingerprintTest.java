package com.defold.extender.cache;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ExtensionCompileFingerprintTest {
    private static final String SDK = "0123456789012345678901234567890123456789";
    private static final String ARCHIVE = "llvm-ar rcs {{tgt}} {{#objs}}{{.}} {{/objs}}";

    @TempDir
    Path directory;

    @Test
    public void identicalJobsIgnoreObjectNamesAndUploadMetadata() throws IOException {
        Path first = job("first");
        Path second = job("second");
        Files.writeString(first.resolve("upload/ne-cache-info.json"), "first cached upload manifest");
        Files.writeString(second.resolve("upload/__source_code__.zip"), "second wire archive");

        ExtensionCompileFingerprint.Keys firstKeys = keys(first, 0, "-DVALUE=1");
        ExtensionCompileFingerprint.Keys secondKeys = keys(second, 9, "-DVALUE=1");

        assertNotNull(firstKeys);
        assertEquals(firstKeys.family(), secondKeys.family());
        assertEquals(firstKeys.direct(), secondKeys.direct());
        assertFalse(firstKeys.probes().getFirst().contains("-c"));
        assertFalse(firstKeys.probes().getFirst().stream().anyMatch(argument -> argument.startsWith("-o")));
        assertEquals("-E", firstKeys.probes().getFirst().getLast());
    }

    @Test
    public void changedPeerSourcesRequireProbeButCanReuseEffectiveResult() throws IOException {
        Path first = job("first");
        Path second = job("second");
        Files.createDirectories(first.resolve("upload/B/src"));
        Files.createDirectories(second.resolve("upload/C/src"));
        Files.writeString(first.resolve("upload/B/src/peer.cpp"), "int b() { return 1; }");
        Files.writeString(second.resolve("upload/C/src/peer.cpp"), "int c() { return 2; }");
        ExtensionCompileFingerprint firstFingerprint = fingerprint(first);
        ExtensionCompileFingerprint secondFingerprint = fingerprint(second);
        ExtensionCompileFingerprint.Keys firstKeys = keys(firstFingerprint, first, 0, "-DVALUE=1");
        ExtensionCompileFingerprint.Keys secondKeys = keys(secondFingerprint, second, 3, "-DVALUE=1");
        assertEquals(firstKeys.family(), secondKeys.family());
        assertNotEquals(firstKeys.direct(), secondKeys.direct());

        Path firstPreprocessed = Files.writeString(first.resolve("build/main.ii"),
            "# 1 \"upload/A/src/main.cpp\"\nint a() { return 1; }\n");
        Path secondPreprocessed = Files.writeString(second.resolve("build/main.ii"),
            "# 1 \"upload/A/src/main.cpp\"\nint a() { return 1; }\n");
        assertEquals(firstFingerprint.effective(firstKeys, List.of(firstPreprocessed)),
            secondFingerprint.effective(secondKeys, List.of(secondPreprocessed)));
        Files.writeString(secondPreprocessed, "int a() { return 2; }\n");
        assertNotEquals(firstFingerprint.effective(firstKeys, List.of(firstPreprocessed)),
            secondFingerprint.effective(secondKeys, List.of(secondPreprocessed)));
    }

    @Test
    public void sourceFlagsAndVisibleHeaderChangesInvalidateCache() throws IOException {
        Path job = job("job");
        ExtensionCompileFingerprint.Keys original = keys(job, 0, "-DVALUE=1");
        assertNotEquals(original.family(), keys(job, 0, "-DVALUE=2").family());

        Files.writeString(job.resolve("upload/A/src/main.cpp"), "int a() { return 3; }");
        assertNotEquals(original.family(), keys(job, 0, "-DVALUE=1").family());
        Files.writeString(job.resolve("upload/A/src/main.cpp"), "int a() { return VALUE; }");
        Files.writeString(job.resolve("upload/A/include/value.h"), "#define SOMETHING 7\n");
        ExtensionCompileFingerprint.Keys changedHeader = keys(job, 0, "-DVALUE=1");
        assertEquals(original.family(), changedHeader.family());
        assertNotEquals(original.direct(), changedHeader.direct());
    }

    @Test
    public void unsupportedAndTimeDependentInputsBypassCache() throws IOException {
        Path job = job("job");
        assertNull(keys(job, 0, "-fmodules"));
        assertNull(keys(job, 0, "@compile.rsp"));
        Files.writeString(job.resolve("upload/A/src/main.cpp"), "const char *time = __TIME__;\n");
        assertNull(keys(job, 0, "-DVALUE=1"));
    }

    @Test
    public void preservesBackslashesInDefinesAndPreprocessedLiterals() throws IOException {
        Path job = job("job");
        assertNotEquals(keys(job, 0, "-DVALUE=C:\\\\literal").family(),
            keys(job, 0, "-DVALUE=C:/literal").family());
        ExtensionCompileFingerprint fingerprint = fingerprint(job);
        ExtensionCompileFingerprint.Keys keys = keys(fingerprint, job, 0, "-DVALUE=1");
        Path output = Files.writeString(job.resolve("build/main.ii"), "const char *p = \"C:\\\\literal\";\n");
        String backslashes = fingerprint.effective(keys, List.of(output));
        Files.writeString(output, "const char *p = \"C:/literal\";\n");
        assertNotEquals(backslashes, fingerprint.effective(keys, List.of(output)));
    }

    @Test
    public void supportsWebCompilerSettingsAndProducesPreprocessorCommands() throws IOException {
        Path job = job("web");
        ExtensionCompileFingerprint fingerprint = webFingerprint(job, "wasm-web", Map.of());
        ExtensionCompileFingerprint.Keys separated = webKeys(fingerprint, job,
            "-s DISABLE_EXCEPTION_CATCHING=1 -std=c++17 -ffp-contract=off -fno-fast-math -msimd128");
        ExtensionCompileFingerprint.Keys attached = webKeys(fingerprint, job,
            "-sDISABLE_EXCEPTION_CATCHING=1 -std=c++17 -ffp-contract=off -fno-fast-math -msimd128");
        assertNotNull(separated);
        assertEquals(separated.family(), attached.family());
        assertEquals(separated.direct(), attached.direct());
        assertTrue(separated.probes().getFirst().contains("DISABLE_EXCEPTION_CATCHING=1"));
        assertEquals("-E", separated.probes().getFirst().getLast());
        assertFalse(separated.probes().getFirst().contains("-c"));
        assertFalse(separated.probes().getFirst().stream().anyMatch(argument -> argument.startsWith("-o")));
        assertNotNull(webKeys(fingerprint, job, "-s DISABLE_EXCEPTION_CATCHING=1", "emcc"));
    }

    @Test
    public void webPlatformCompilerSettingsAndEnvironmentSeparateArtifacts() throws IOException {
        Path job = job("web");
        ExtensionCompileFingerprint.Keys original = webKeys(webFingerprint(job, "wasm-web", Map.of()), job,
            "-s DISABLE_EXCEPTION_CATCHING=1");
        assertNotNull(original);
        assertNotEquals(original.family(), webKeys(webFingerprint(job, "wasm_pthread-web", Map.of()), job,
            "-s DISABLE_EXCEPTION_CATCHING=1").family());
        assertNotEquals(original.family(), webKeys(webFingerprint(job, "wasm-web", Map.of()), job,
            "-s DISABLE_EXCEPTION_CATCHING=0").family());
        assertNotEquals(original.family(), webKeys(webFingerprint(job, "wasm-web", Map.of()), job,
            "-s DISABLE_EXCEPTION_CATCHING=1 -pthread").family());
        assertNotEquals(original.family(), webKeys(webFingerprint(job, "wasm-web", Map.of()), job,
            "-s DISABLE_EXCEPTION_CATCHING=1 -ffp-contract=off -fno-fast-math").family());
        assertNotEquals(original.family(), webKeys(webFingerprint(job, "wasm-web", Map.of("EMSCRIPTEN_BIN", "/another-toolchain")), job,
            "-s DISABLE_EXCEPTION_CATCHING=1").family());
    }

    @Test
    public void webConfigurationContentsInvalidateArtifacts() throws IOException {
        Path job = job("web");
        Path configuration = Files.writeString(directory.resolve("emscripten.config"), "LLVM_ROOT = '/tools/llvm'\n");
        Map<String, String> environment = Map.of("EM_CONFIG", configuration.toString());
        ExtensionCompileFingerprint.Keys first = webKeys(webFingerprint(job, "wasm-web", environment), job,
            "-s DISABLE_EXCEPTION_CATCHING=1");
        Files.writeString(configuration, "LLVM_ROOT = '/tools/another-llvm'\n");
        assertNotEquals(first.family(), webKeys(webFingerprint(job, "wasm-web", environment), job,
            "-s DISABLE_EXCEPTION_CATCHING=1").family());
    }

    @Test
    public void webPortsCustomSettingsAndCompilerOverridesBypassCache() throws IOException {
        Path job = job("web");
        ExtensionCompileFingerprint fingerprint = webFingerprint(job, "wasm-web", Map.of());
        assertNull(webKeys(fingerprint, job, "-s DISABLE_EXCEPTION_CATCHING=1 --use-port=custom.py"));
        assertNull(webKeys(fingerprint, job, "-s EXPORTED_FUNCTIONS=@functions.json"));
        assertNull(webKeys(fingerprint, job, "-s UNKNOWN_SETTING=1"));
        assertNull(webKeys(webFingerprint(job, "wasm-web", Map.of("EMCC_CFLAGS", "-include extra.h")), job,
            "-s DISABLE_EXCEPTION_CATCHING=1"));
        assertNull(webKeys(webFingerprint(job, "wasm-web", Map.of("EM_COMPILER_WRAPPER", "custom-wrapper")), job,
            "-s DISABLE_EXCEPTION_CATCHING=1"));
        assertTrue(ExtensionCompileFingerprint.isWebPlatform("wasm-web"));
        assertTrue(ExtensionCompileFingerprint.isWebPlatform("wasm_pthread-web"));
        assertFalse(ExtensionCompileFingerprint.isWebPlatform("arm64-android"));
    }

    private Path job(String name) throws IOException {
        Path job = directory.resolve(name);
        Files.createDirectories(job.resolve("upload/A/src"));
        Files.createDirectories(job.resolve("upload/A/include"));
        Files.createDirectories(job.resolve("build"));
        Files.writeString(job.resolve("upload/A/src/main.cpp"), "int a() { return VALUE; }");
        Files.writeString(job.resolve("upload/A/include/value.h"), "#define SOMETHING 1\n");
        return job;
    }

    private ExtensionCompileFingerprint fingerprint(Path job) throws IOException {
        Path sdk = Files.createDirectories(directory.resolve("sdk"));
        return new ExtensionCompileFingerprint(job, job.resolve("upload"), sdk, SDK,
            "ndk-test-v1", Map.of("DYNAMO_HOME", sdk.toString(), "HOSTNAME", job.getFileName().toString()), "arm64-android");
    }

    private ExtensionCompileFingerprint webFingerprint(Path job, String platform, Map<String, String> environment) throws IOException {
        Path sdk = Files.createDirectories(directory.resolve("sdk"));
        return new ExtensionCompileFingerprint(job, job.resolve("upload"), sdk, SDK, "emscripten-test-v1", environment, platform);
    }

    private ExtensionCompileFingerprint.Keys webKeys(ExtensionCompileFingerprint fingerprint, Path job, String flags) throws IOException {
        return webKeys(fingerprint, job, flags, "em++");
    }

    private ExtensionCompileFingerprint.Keys webKeys(ExtensionCompileFingerprint fingerprint, Path job, String flags,
            String compilerName) throws IOException {
        Path compiler = directory.resolve("toolchain").resolve(compilerName);
        Files.createDirectories(compiler.getParent());
        Files.writeString(compiler, "test Emscripten compiler");
        String output = "build/main.cpp_0.o";
        String command = "\"" + compiler.toString().replace('\\', '/') + "\" -c -O3 -g -DVALUE=1"
            + " -fno-exceptions -fno-rtti -fPIC -Wno-nontrivial-memcall " + flags
            + " -ffile-prefix-map=" + job.toString().replace('\\', '/') + "=."
            + " -Iupload/A/include -Iupload upload/A/src/main.cpp -o " + output;
        return fingerprint.keys("A", List.of(command), List.of(output),
            List.of(job.resolve("upload/A/src/main.cpp")), "emar rcs {{tgt}} {{#objs}}{{.}} {{/objs}}");
    }

    private ExtensionCompileFingerprint.Keys keys(Path job, int counter, String flags) throws IOException {
        return keys(fingerprint(job), job, counter, flags);
    }

    private ExtensionCompileFingerprint.Keys keys(ExtensionCompileFingerprint fingerprint, Path job,
            int counter, String flags) throws IOException {
        Path compiler = directory.resolve("toolchain/clang++");
        Files.createDirectories(compiler.getParent());
        Files.writeString(compiler, "test compiler identity");
        String output = "build/main.cpp_" + counter + ".o";
        String command = "\"" + compiler.toString().replace('\\', '/') + "\" -c -O2 -g " + flags
            + " -ffile-prefix-map=" + job.toString().replace('\\', '/') + "=."
            + " -Iupload/A/include -Iupload upload/A/src/main.cpp -o" + output;
        return fingerprint.keys("A", List.of(command), List.of(output),
            List.of(job.resolve("upload/A/src/main.cpp")), ARCHIVE);
    }
}

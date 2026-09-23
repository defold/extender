package com.defold.extender.builders;

import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;

import java.io.PrintWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;

import com.defold.extender.ExtenderException;
import com.defold.extender.ExtenderUtil;
import com.defold.extender.TemplateExecutor;
import com.defold.extender.process.ProcessExecutor;
import com.defold.extender.process.SandboxPolicy;
import com.defold.extender.services.NuGetCacheService;

public class CSharpBuilder {
    private static final Logger LOGGER = LoggerFactory.getLogger(CSharpBuilder.class);

    private static final String DOTNET_ROOT = System.getenv("DOTNET_ROOT");
    private static final String DOTNET_VERSION_FILE = System.getenv("DOTNET_VERSION_FILE");

    private List<String>        engineLibs;
    private File                sourceDir;
    private File                csProject;
    private File                outputDir;
    private String              outputName;
    private File                outputFile;
    private String              platform;
    private String              template;
    private ProcessExecutor     processExecutor;
    private TemplateExecutor    templateExecutor;
    private Map<String, Object> context;

    private static String readFile(String filePath) throws IOException {
        if (filePath == null) {
            return "";
        }

        return new String( Files.readAllBytes( Paths.get(filePath) ) );
    }

    public CSharpBuilder(ProcessExecutor processExecutor, TemplateExecutor templateExecutor,
                        Map<String, Object> context) throws IOException {
        this.processExecutor = processExecutor;
        this.templateExecutor = templateExecutor;
        Resource csProjectResource = new ClassPathResource("template.csproj");
        this.template = ExtenderUtil.readContentFromResource(csProjectResource);
        this.context = context;

        LOGGER.info(String.format("DOTNET_ROOT: %s", DOTNET_ROOT));
        LOGGER.info(String.format("shared NuGet cache: %s", sharedNuGetCache()));
    }

    public void setSourceDirectory(File sourceDir) {
        this.sourceDir = sourceDir;
    }

    public void setEngineLibraries(List<String> engineLibs) {
        this.engineLibs = engineLibs;
    }

    public void setOutputDirectory(File outputDir) {
        this.outputDir = outputDir;
    }

    public void setOutputName(String outputName) {
        this.outputName = outputName;
    }

    public void setOutputFile(File outputFile) {
        this.outputFile = outputFile;
    }

    public void setPlatform(String platform) {
        this.platform = platform;
    }

    public void setSdkProject(File csProject) {
        this.csProject = csProject;
    }

    private File writeProject() throws IOException {
        // The cross compilation (win32) doesn't like absolute unix paths, so we use the path relative to cwd
        Path relativeOutputDir = sourceDir.toPath().relativize(outputDir.toPath());

        context.put("PINVOKE", engineLibs);
        context.put("BUILDDIR_CS", relativeOutputDir);
        context.put("DMSDK_CSPROJ", csProject.getAbsolutePath());

        String projectText = templateExecutor.execute(this.template, context);

        File f = new File(this.sourceDir, String.format("%s.csproj", outputName));

        FileUtils.writeStringToFile(f, projectText, Charset.defaultCharset(), true);

        if (!f.exists())
            throw new IOException(String.format("Failed to write to %s", f.getAbsolutePath()));

        LOGGER.info(String.format("Wrote %s file\n", f.getAbsolutePath()));

        return f;
    }

    // https://learn.microsoft.com/en-us/dotnet/core/deploying/native-aot/?tabs=net8plus%2Cwindows#platformarchitecture-restrictions
    private static String convertPlatform(String platform) {
        if (platform.equals("arm64-osx"))       return "osx-arm64";
        if (platform.equals("x86_64-osx"))      return "osx-x64";
        if (platform.equals("arm64-android"))   return "linux-bionic-arm64";
        if (platform.equals("armv7-android"))   return "linux-bionic-arm32";
        if (platform.equals("x86_64-android"))  return "linux-bionic-x64";
        if (platform.equals("arm64-win32"))     return "win-arm64";
        if (platform.equals("x86_64-win32"))    return "win-x64";
        if (platform.equals("x86-win32"))       return "win-x86";
        if (platform.equals("x86_64-linux"))    return "linux-x64";
        if (platform.equals("arm64-linux"))     return "linux-arm64";
        if (platform.equals("x86_64-ios"))      return "ios-x64";
        if (platform.equals("arm64-ios"))       return "ios-arm64";
        if (platform.equals("arm64_sim-ios"))   return "iossimulator-arm64";
        return "unknown_platform";
    }

    private static String getLibName(String platform, String name) {
        String prefix = "lib";
        String suffix = ".a";
        if (ExtenderUtil.isWindowsTarget(platform))
        {
            prefix = "";
            suffix = ".lib";
        }
        return String.format("%s%s%s", prefix, name, suffix);
    }

    private static String getObjName(String platform, String name) {
        String prefix = "lib";
        String suffix = ".o";
        if (ExtenderUtil.isWindowsTarget(platform))
        {
            prefix = "";
            suffix = ".obj";
        }
        return String.format("%s%s%s", prefix, name, suffix);
    }

    private File runDotnet(File project, String platform) throws IOException, InterruptedException, ExtenderException {

        if (DOTNET_ROOT == null) {
            throw new ExtenderException("DOTNET_ROOT is not setup correctly! Cannot build C#.");
        }

        String csplatform = convertPlatform(this.platform);
        String cmd = String.format("%s/dotnet publish --nologo -c Release -r %s ", DOTNET_ROOT, csplatform);
        cmd += project.getAbsolutePath();

        List<String> commands = new ArrayList<>();
        commands.add(cmd);

        // NuGet restore needs the network, and its package cache must be executable because a
        // NativeAOT publish runs ilc out of the ilcompiler package it restores. That cache is
        // therefore per job: writable and executable, and gone with the job. The instance-wide
        // cache is handed over read-only as a NuGet fallback folder, which NuGet reads packages
        // from and never writes to, so the restore costs nothing when it is warm.
        File perJobCache = perJobNuGetCache(buildDir());
        // ProcessSandbox skips a grant whose path does not exist, and the restore has to
        // be able to write here
        perJobCache.mkdirs();
        File sharedCache = sharedNuGetCache();
        if (sharedCache != null) {
            // the warm writes the shared cache; reading it half-restored just costs a download
            NuGetCacheService.current().awaitWarm(csplatform);
        }
        SandboxPolicy policy = dotnetPolicy(perJobCache, sharedCache, new File(this.outputDir, ".dotnet"));
        ProcessExecutor.executeCommands(processExecutor, commands, null, policy); // in parallel

        String name = outputName;
        if (name.startsWith("lib"))
            name = name.substring(3);

        String libName = getLibName(platform, name);
        File csOutput = new File(this.outputDir, csplatform);
        File csPublish = new File(csOutput, "publish");
        File publishLibrary = new File(csPublish, libName);

        FileUtils.moveFile(publishLibrary, outputFile);
        return outputFile;
    }

    // Build and return the library file(s)
    public List<File> build() throws IOException, InterruptedException, ExtenderException {

        File project = writeProject();

        File library = runDotnet(project, this.platform);

        List<File> out = new ArrayList<>();
        out.add(library);
        return out;
    }

    /**
     * The policy for {@code dotnet publish}: network for the restore, the job's own package cache
     * writable and executable (ilc runs out of it), and the instance-wide cache read-only as a
     * NuGet fallback folder, which NuGet resolves packages from and never writes to.
     */
    static SandboxPolicy dotnetPolicy(File perJobCache, File sharedCache, File cliHome) {
        Map<String, String> env = new HashMap<>();
        // the CLI's own state (first-run sentinel, telemetry) goes here, not to the shared install
        env.put("DOTNET_CLI_HOME", cliHome.getAbsolutePath());
        env.put("NUGET_PACKAGES", perJobCache.getAbsolutePath());
        if (sharedCache != null) {
            env.put("NUGET_FALLBACK_PACKAGES", sharedCache.getAbsolutePath());
        }
        return SandboxPolicy
                // the .NET runtime's named-mutex state, which NuGet takes on every restore
                .dependencyResolver(List.of(NuGetCacheService.dotnetRuntimeStateDir().getAbsolutePath()))
                .withReadWriteExecPaths(List.of(perJobCache.getAbsolutePath()))
                .withReadOnlyPaths(sharedCache != null ? List.of(sharedCache.getAbsolutePath()) : List.of())
                .withMachServices(NuGetCacheService.DOTNET_MACH_SERVICES)
                .withEnv(env);
    }

    /** The job's own package cache; {@code buildDir} is shared by the extensions of one job. */
    private static File perJobNuGetCache(File buildDir) {
        return new File(buildDir, ".nuget");
    }

    /** The instance-wide read-only cache, or null when there is none to read from. */
    private static File sharedNuGetCache() {
        NuGetCacheService service = NuGetCacheService.current();
        return service != null ? service.fallbackDir() : null;
    }

    /** The build directory the job shares, i.e. the parent of this extension's output directory. */
    private File buildDir() {
        return this.outputDir.getParentFile();
    }

    /**
     * Where the NativeAOT runtime libraries are looked for. A package restored for this job is in
     * the job's own cache; one that came from the warm shared cache is in that. Returns the
     * per-job location when neither exists, which is what the caller puts on the link line.
     */
    public static Path getNativePath(String platform, File buildDir) throws IOException {
        String csplatform = convertPlatform(platform);
        String dotnetVersion = readFile(DOTNET_VERSION_FILE).trim();
        String relative = String.format("microsoft.netcore.app.runtime.nativeaot.%s/%s/runtimes/%s/native",
                csplatform, dotnetVersion, csplatform);
        File shared = sharedNuGetCache();
        if (shared != null && new File(shared, relative).isDirectory()) {
            return shared.toPath().resolve(relative);
        }
        return perJobNuGetCache(buildDir).toPath().resolve(relative);
    }

    private static ArrayList<String> makePathsAbsolute(String basePath, ArrayList<String> files) {
        ArrayList<String> out = new ArrayList<>();
        for (String name : files) {
            out.add(Paths.get(basePath, name).toString());
        }
        return out;
    }

    private static void getExportsFlags(String platform, File buildDir, List<String> linkFlags) throws IOException {
        // We need to add the exported symbols map
        File exportsFile = new File(String.format("%s/defold_cs.exports", buildDir));

        String exportsPattern = "-Wl,--version-script=%s";
        if (ExtenderUtil.isMacOSTarget(platform) || ExtenderUtil.isIOSTarget(platform))
        {
            exportsPattern = "-exported_symbols_list %s";
        }

        // HACK: In anticipation of the fix for https://github.com/dotnet/runtime/issues/109341
        // we have to make sure not all symbols are public, and at the same time respect the engine symbols
        String contents = "";
        contents += "V1.0 { \n";
        contents += "    global: \n";
        contents += "        DotNetRuntimeDebugHeader; \n";
        if (ExtenderUtil.isAndroidTarget(platform))
        {
            contents += "        ANativeActivity*;\n";
            contents += "        Java_com_*;\n";
        }
        contents += "    local: *; \n";
        contents += "};\n";

        File parent = exportsFile.getParentFile();
        if (!parent.exists())
            parent.mkdirs();
        FileOutputStream fos = new FileOutputStream(exportsFile, false);
        PrintWriter writer = new PrintWriter(fos);
        writer.write(contents);
        writer.close();

        linkFlags.add(String.format(exportsPattern, exportsFile.getAbsolutePath()));
    }

    private static void getLinkFlags(String platform, File buildDir, List<String> linkFlags) throws IOException {
        Path aotBase = getNativePath(platform, buildDir);

        ArrayList<String> paths = new ArrayList<>();

        paths.add(getObjName(platform, "bootstrapperdll"));

        // TODO: Do we need a way to toggle these behaviors on/off?
        paths.add(getLibName(platform, "Runtime.WorkstationGC"));
        paths.add(getLibName(platform, "eventpipe-disabled"));
        paths.add(getLibName(platform, "standalonegc-disabled"));


        String aotSuffix = "";
        if (ExtenderUtil.isWindowsTarget(platform))
            aotSuffix = ".Aot";
        paths.add(getLibName(platform, "System.IO.Compression.Native" + aotSuffix));
        paths.add(getLibName(platform, "System.Globalization.Native" + aotSuffix));

        if (ExtenderUtil.isMacOSTarget(platform))
        {
            paths.add(getLibName(platform, "System.Native"));
        }
        else if (ExtenderUtil.isIOSTarget(platform))
        {
            paths.add(getLibName(platform, "System.Native"));
            paths.add(getLibName(platform, "stdc++compat"));
            paths.add(getLibName(platform, "System.Net.Security.Native"));
            paths.add(getLibName(platform, "System.Security.Cryptography.Native.Apple"));
            linkFlags.add("-licucore");
        }
        else if (ExtenderUtil.isWindowsTarget(platform))
        {
            paths.add(getLibName(platform, "Runtime.VxsortEnabled"));
            linkFlags.add("-lbcrypt");
            linkFlags.add("-lole32");
            linkFlags.add("-ladvapi32");
        }
        else if (ExtenderUtil.isAndroidTarget(platform))
        {
            paths.add(getLibName(platform, "System.Native"));

            CSharpBuilder.getExportsFlags(platform, buildDir, linkFlags);
        }

        // Note: These libraries are specified with full paths, or the linker will link against the dynamic libraries (macOS)
        // We want to avoid that hassle for now. Let's do that in a step two.
        linkFlags.addAll(makePathsAbsolute(aotBase.toString(), paths));
    }

    public static void updateContext(String platform, File buildDir, Map<String, Object> context) throws IOException {
        Path aotBase = getNativePath(platform, buildDir);

        List<String> libPaths = (List<String>)context.getOrDefault("libPaths", new ArrayList<String>());
        libPaths.add(aotBase.toString()); // -L/path/to/aot
        context.put("libPaths", libPaths);

        List<String> linkFlags = (List<String>)context.getOrDefault("linkFlags", new ArrayList<String>());
        CSharpBuilder.getLinkFlags(platform, buildDir, linkFlags);
        context.put("linkFlags", linkFlags);
    }

}

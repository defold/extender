package com.defold.extender.builders;

import com.google.common.collect.ImmutableMap;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.filefilter.DirectoryFileFilter;
import org.apache.commons.io.filefilter.RegexFileFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.error.YAMLException;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.File;
import java.io.FileFilter;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;

import com.defold.extender.ExtenderException;
import com.defold.extender.ProcessExecutor;
import com.defold.extender.TemplateExecutor;

public class CSharpBuilder {
    private static final Logger LOGGER = LoggerFactory.getLogger(CSharpBuilder.class);

    private static final String DOTNET_VERSION_FILE = System.getenv("DOTNET_VERSION_FILE");
    private static final String NUGET_PACKAGES = System.getenv("NUGET_PACKAGES");
    private static final String CSPROJ_TEMPLATE_PATH = System.getenv("EXTENSION_CSPROJ_TEMPLATE");

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
        this.template = readFile(CSPROJ_TEMPLATE_PATH);
        this.context = context;

        LOGGER.info(String.format("NUGET_PACKAGES: %s", NUGET_PACKAGES));
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

        context.put("PINVOKE", engineLibs);
        context.put("BUILDDIR_CS", outputDir);
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
        if (platform.equals("arm64-android"))   return "android-arm64";
        if (platform.equals("armv7-android"))   return "android-arm32";
        if (platform.equals("arm64-win32"))     return "win-arm64";
        if (platform.equals("x86_64-win32"))    return "win-x64";
        if (platform.equals("x86-win32"))       return "win-x86";
        if (platform.equals("x86_64-linux"))    return "linux-x64";
        if (platform.equals("x86_64-ios"))      return "ios-x64";
        if (platform.equals("arm64-ios"))       return "ios-arm64";
        return "unknown_platform";
    }

    private boolean isWindows(String platform) {
        if (platform.endsWith("win32"))
            return true;
        return false;
    }

    private File runDotnet(File project, String platform) throws IOException, InterruptedException, ExtenderException {

        String csplatform = convertPlatform(this.platform);
        String cmd = "dotnet publish --nologo -c Release " + String.format("-r %s ", csplatform);
        cmd += project.getAbsolutePath();

        List<String> commands = new ArrayList<>();
        commands.add(cmd);
        ProcessExecutor.executeCommands(processExecutor, commands); // in parallel

        String libName;
        if (isWindows(platform))
            libName = String.format("%s.lib", outputName);
        else
            libName = String.format("lib%s.a", outputName);

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

    public static List<File> getStaticDependencies(String platform) throws IOException {
        String csplatform = convertPlatform(platform);
        String dotnetVersion = readFile(DOTNET_VERSION_FILE).trim();

        Path aotBase = Paths.get(NUGET_PACKAGES, String.format("microsoft.netcore.app.runtime.nativeaot.%s/%s/runtimes/%s/native", csplatform, dotnetVersion, csplatform));

        List<File> out = new ArrayList<>();
        if (platform.equals("arm64-osx") || platform.equals("x86_64-osx"))
        {
            out.add(Paths.get(aotBase.toString(), "libbootstrapperdll.o").toFile());
            out.add(Paths.get(aotBase.toString(), "libRuntime.WorkstationGC.a").toFile());
            out.add(Paths.get(aotBase.toString(), "libeventpipe-enabled.a").toFile());
            out.add(Paths.get(aotBase.toString(), "libSystem.Native.a").toFile());
            out.add(Paths.get(aotBase.toString(), "libSystem.IO.Compression.Native.a").toFile());
            out.add(Paths.get(aotBase.toString(), "libSystem.Globalization.Native.a").toFile());
        }
        else if (platform.equals("arm64-ios") || platform.equals("x86_64-ios"))
        {
            // microsoft.netcore.app.runtime.nativeaot.ios-arm64/8.0.6/runtimes/ios-arm64/native
            out.add(Paths.get(aotBase.toString(), "libbootstrapperdll.o").toFile());
            out.add(Paths.get(aotBase.toString(), "libRuntime.WorkstationGC.a").toFile());
            out.add(Paths.get(aotBase.toString(), "libeventpipe-enabled.a").toFile());
            out.add(Paths.get(aotBase.toString(), "libSystem.Native.a").toFile());
            out.add(Paths.get(aotBase.toString(), "libSystem.IO.Compression.Native.a").toFile());
            out.add(Paths.get(aotBase.toString(), "libSystem.Globalization.Native.a").toFile());
            out.add(Paths.get(aotBase.toString(), "libicui18n.a").toFile());
            out.add(Paths.get(aotBase.toString(), "libicudata.a").toFile());
            out.add(Paths.get(aotBase.toString(), "libicuuc.a").toFile());
        }

        return out;
    }
}

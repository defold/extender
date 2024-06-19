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

    private String readFile(String filePath) throws IOException {
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

    private static String convertPlatform(String platform) {
        if (platform.equals("arm64-osx"))   return "osx-arm64";
        if (platform.equals("x86_64-osx"))  return "osx-x64";
        return "";
    }

    private boolean isWindows(String platform) {
        if (platform.endsWith("win32"))
            return true;
        return false;
    }

    private File runDotnet(File project, String platform) throws IOException, InterruptedException, ExtenderException {

        String csplatform = convertPlatform(this.platform);
        String cmd = "dotnet publish -c Release " + String.format("-r %s ", csplatform);
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

    public static List<File> getStaticDependencies(String platform) {
        String csplatform = convertPlatform(platform);

        Path aotBase = Paths.get(System.getProperty("user.home"), ".nuget", "packages", String.format("runtime.%s.microsoft.dotnet.ilcompiler", csplatform), "8.0.0");
        // File aotBase = aotBasePath.toFile();

        // String aotBase = System.getProperty("user.home") + "/.nuget/packages/" + String.format("runtime.%s.microsoft.dotnet.ilcompiler", csplatform) + "/8.0.0";
        // NUGET_DIR=~/.nuget/packages
        // AOTBASE=${NUGET_DIR}/runtime.osx-${ARCH}.microsoft.dotnet.ilcompiler/8.0.0

        List<File> out = new ArrayList<>();
        out.add(Paths.get(aotBase.toString(), "sdk/libbootstrapperdll.o").toFile());
        out.add(Paths.get(aotBase.toString(), "sdk/libRuntime.WorkstationGC.a").toFile());
        out.add(Paths.get(aotBase.toString(), "sdk/libeventpipe-enabled.a").toFile());
        out.add(Paths.get(aotBase.toString(), "framework/libSystem.Native.a").toFile());
        out.add(Paths.get(aotBase.toString(), "framework/libSystem.IO.Compression.Native.a").toFile());
        out.add(Paths.get(aotBase.toString(), "framework/libSystem.Globalization.Native.a").toFile());
        return out;
    }
}

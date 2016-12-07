package com.defold.extender;

import com.google.common.collect.ImmutableMap;
import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

class Extender {
    private static final Logger LOGGER = LoggerFactory.getLogger(Extender.class);
    private final Configuration config;
    private final String platform;
    private final File sdk;
    private final File extensionSource;
    private final File build;
    private final PlatformConfig platformConfig;
    private final TemplateExecutor templateExecutor = new TemplateExecutor();
    private final ProcessExecutor processExecutor = new ProcessExecutor();

    Extender(String platform, File extensionSource, File sdk) throws IOException {
        // Read config from SDK
        InputStream configFileInputStream = Files.newInputStream(new File(sdk.getPath() + "/extender/build.yml").toPath());
        this.config = new Yaml().loadAs(configFileInputStream, Configuration.class);

        this.platform = platform;
        this.sdk = sdk;
        this.platformConfig = config.platforms.get(platform);
        this.extensionSource = extensionSource;
        this.build = Files.createTempDirectory("build").toFile();

        if (this.platformConfig == null) {
            throw new IllegalArgumentException(String.format("Unsupported platform %s", platform));
        }
    }

    private List<String> collectLibraries(File libDir, String re) {
        Pattern p = Pattern.compile(re);
        List<String> libs = new ArrayList<>();
        if (libDir.exists()) {
            File[] files = libDir.listFiles();
            for (File f : files) {
                Matcher m = p.matcher(f.getName());
                if (m.matches()) {
                    libs.add(m.group(1));
                }

            }
        }
        return libs;
    }

    private File linkEngine(List<File> extDirs, List<String> symbols) throws IOException, InterruptedException {
        File maincpp = new File(build, "main.cpp");
        File exe = new File(build, String.format("dmengine%s", platformConfig.exeExt));

        List<String> extSymbols = new ArrayList<>();
        extSymbols.addAll(symbols);

        Map<String, Object> mainContext = context();
        mainContext.put("ext", ImmutableMap.of("symbols", extSymbols));

        String main = templateExecutor.execute(config.main, mainContext);
        FileUtils.writeStringToFile(maincpp, main);

        List<String> extLibs = new ArrayList<>();
        List<String> extLibPaths = new ArrayList<>(Arrays.asList(build.toString()));

        for (File ed : extDirs) {
            File libDir = new File(ed, "lib" + File.separator + this.platform);
            extLibs.addAll(collectLibraries(libDir, platformConfig.shlibRe));
            extLibs.addAll(collectLibraries(libDir, platformConfig.stlibRe));

            extLibPaths.add(new File(ed, "lib" + File.separator + this.platform).toString());
        }
        extLibs.addAll(collectLibraries(build, platformConfig.stlibRe));

        Map<String, Object> context = context();
        context.put("src", Arrays.asList(maincpp.getAbsolutePath()));
        context.put("tgt", exe.getAbsolutePath());
        context.put("ext", ImmutableMap.of("libs", extLibs, "libPaths", extLibPaths));

        String command = templateExecutor.execute(platformConfig.linkCmd, context);
        processExecutor.execute(command);

        return exe;
    }

    private File compileFile(int i, File extDir, File f) throws IOException, InterruptedException {
        List<String> includes = new ArrayList<>();
        includes.add(extDir.getAbsolutePath() + File.separator + "include");
        File o = new File(build, String.format("%s_%d.o", f.getName(), i));

        Map<String, Object> context = context();
        context.put("src", f);
        context.put("tgt", o);
        context.put("ext", ImmutableMap.of("includes", includes));
        String command = templateExecutor.execute(platformConfig.compileCmd, context);
        processExecutor.execute(command);
        return o;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> context() {
        Map<String, Object> context = new HashMap<>(config.context);
        context.put("dynamo_home", sdk.getAbsolutePath());
        context.put("platform", this.platform);
        context.putAll(platformConfig.context);

        Set<String> keys = context.keySet();
        for (String k : keys) {
            Object v = context.get(k);
            if (v instanceof String) {
                v = templateExecutor.execute((String) v, context);
            } else if (v instanceof List) {
                v = templateExecutor.execute((List<String>) v, context);
            }
            context.put(k, v);
        }

        return context;
    }

    private File buildExtension(File manifest) throws IOException, InterruptedException {
        File extDir = manifest.getParentFile();
        File src = new File(extDir, "src");
        Collection<File> srcFiles = new ArrayList<>();
        if (src.isDirectory()) {
            srcFiles = FileUtils.listFiles(src, null, true);
        }
        List<String> objs = new ArrayList<>();

        int i = 0;
        for (File f : srcFiles) {
            File o = compileFile(i, extDir, f);
            objs.add(o.getAbsolutePath());
            i++;
        }

        File lib = File.createTempFile("lib", ".a", build);
        lib.delete();

        Map<String, Object> context = context();
        context.put("tgt", lib);
        context.put("objs", objs);
        String command = templateExecutor.execute(platformConfig.libCmd, context);
        processExecutor.execute(command);
        return lib;
    }

    File buildEngine() throws ExtenderException {
        LOGGER.info("Building engine for platform {} with extension source {}", platform, extensionSource);

        try {
            Collection<File> allFiles = FileUtils.listFiles(extensionSource, null, true);
            List<File> manifests = allFiles.stream().filter(f -> f.getName().equals("ext.manifest")).collect(Collectors.toList());
            List<File> extDirs = manifests.stream().map(File::getParentFile).collect(Collectors.toList());

            List<String> symbols = new ArrayList<>();

            for (File f : manifests) {
                @SuppressWarnings("unchecked")
                Map<String, Object> m = (Map<String, Object>) new Yaml().load(FileUtils.readFileToString(f));
                symbols.add((String) m.get("name"));
            }

            for (File manifest : manifests) {
                buildExtension(manifest);
            }

            return linkEngine(extDirs, symbols);
        } catch (IOException | InterruptedException e) {
            throw new ExtenderException(e, processExecutor.getOutput());
        }
    }

    void dispose() throws IOException {
        FileUtils.deleteDirectory(build);
    }
}

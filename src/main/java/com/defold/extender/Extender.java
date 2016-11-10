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
    private final File root;
    private final File build;
    private final PlatformConfig platformConfig;
    private final TemplateExecutor templateExecutor = new TemplateExecutor();

    Extender(Configuration config, String platform, File root) throws IOException {
        this.config = config;
        this.platform = platform;
        this.platformConfig = config.platforms.get(platform);
        this.root = root;
        this.build = Files.createTempDirectory("engine").toFile();

        if (this.platformConfig == null) {
            throw new IllegalArgumentException(String.format("Unsupported platform %s", platform));
        }
    }

    private static String exec(String command) throws IOException, InterruptedException {
        LOGGER.info(command);

        String[] args = command.split(" ");

        ProcessBuilder pb = new ProcessBuilder(args);
        pb.redirectErrorStream(true);
        Process p = pb.start();

        byte[] buf = new byte[16 * 1024];
        InputStream is = p.getInputStream();

        StringBuilder sb = new StringBuilder();

        int n;
        do {
            n = is.read(buf);
            if (n > 0) {
                sb.append(new String(buf, 0, n));
            }
        }
        while (n > 0);

        int ret = p.waitFor();
        if (ret > 0) {
            throw new IOException(sb.toString());
        }

        return sb.toString();
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
        exec(command);

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
        exec(command);
        return o;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> context() {
        Map<String, Object> c = new HashMap<>(config.context);
        c.put("platform", this.platform);
        c.putAll(platformConfig.context);

        Set<String> keys = c.keySet();
        for (String k : keys) {
            Object v = c.get(k);
            if (v instanceof String) {
                v = templateExecutor.execute((String) v, c);
            } else if (v instanceof List) {
                v = templateExecutor.execute((List<String>) v, c);
            }
            c.put(k, v);
        }

        return c;
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
        exec(command);
        return lib;
    }

    File buildEngine() throws IOException, InterruptedException {
        Collection<File> allFiles = FileUtils.listFiles(root, null, true);
        List<File> manifests = allFiles.stream().filter(f -> f.getName().equals("ext.manifest")).collect(Collectors.toList());
        List<File> extDirs = manifests.stream().map(File::getParentFile).collect(Collectors.toList());

        List<String> symbols = new ArrayList<>();

        for (File f : manifests) {
            @SuppressWarnings("unchecked")
            Map<String, Object> m = (Map<String, Object>) new Yaml().load(FileUtils.readFileToString(f));
            symbols.add((String) m.get("name"));
        }

        // TODO: Why are we having libs here?
        List<File> libs = new ArrayList<>();
        for (File manifest : manifests) {
            File lib = buildExtension(manifest);
            libs.add(lib);
        }

        return linkEngine(extDirs, symbols);
    }

    void dispose() throws IOException {
        FileUtils.deleteDirectory(build);
    }
}

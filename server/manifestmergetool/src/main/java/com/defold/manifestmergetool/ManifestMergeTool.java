/**

Run standalone with:
java -cp ./manifestmergetool.jar com.defold.manifestmergetool.ManifestMergeTool --platform html5 --main main/engine_template.html --lib extension//engine_template.html --out index.html

*/

// https://android.googlesource.com/platform/sdk/+/a35f8af/manifmerger/src/com/android/manifmerger/ManifestMerger.java

package com.defold.manifestmergetool;

import java.io.*;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.ArrayList;
import java.util.List;

import java.nio.file.Files;
import java.nio.charset.StandardCharsets;

import com.android.manifmerger.ManifestMerger2;
import com.android.manifmerger.MergingReport;
import com.android.manifmerger.XmlDocument;
import com.android.utils.ILogger;

public class ManifestMergeTool {

    public enum Platform {
        ANDROID, IOS, OSX, WEB, UNKNOWN
    }

    private static class ILoggerWrapper implements ILogger {
        private Logger logger;
        public ILoggerWrapper(Logger logger) {
            this.logger = logger;
        }
        public void error(Throwable t, String msgFormat, Object... args) {
            ManifestMergeTool.logger.log(Level.SEVERE, msgFormat, args);
        }
        public void warning(String msgFormat, Object... args) {
            ManifestMergeTool.logger.log(Level.WARNING, msgFormat, args);
        }
        public void info(String msgFormat, Object... args) {
            ManifestMergeTool.logger.log(Level.INFO, msgFormat, args);
        }
        public void verbose(String msgFormat, Object... args) {
            ManifestMergeTool.logger.log(Level.FINE, msgFormat, args);
        }
    }

    private static Logger logger = Logger.getLogger(ManifestMergeTool.class.getName());
    private static ILoggerWrapper androidLogger = new ILoggerWrapper(logger);

    public void usage() {
        logger.log(Level.INFO, "Usage:");
        logger.log(Level.INFO, " --main <arg>\t\tThe main manifest file");
        logger.log(Level.INFO, " --lib <arg>\t\tA library manifest file. Can be used multiple times.");
        logger.log(Level.INFO, " --out <arg>\t\tThe merged output manifest file");
    }

    private static void mergeAndroid(File main, File[] libraries, File out) throws RuntimeException {
        // Good explanation of merge rules: https://android.googlesource.com/platform/tools/base/+/jb-mr2-dev/manifest-merger/src/main/java/com/android/manifmerger/ManifestMerger.java
        // https://github.com/pixnet/android-platform-tools-base/blob/master/build-system/manifest-merger/src/main/java/com/android/manifmerger/ManifestMerger2.java
        // https://android.googlesource.com/platform/tools/base/+/master/build-system/manifest-merger/src/main/java/com/android/manifmerger/Merger.java
        logger.log(Level.FINE, "Merging manifests for Android");

        ManifestMerger2.Invoker invoker = ManifestMerger2.newMerger(main, ManifestMergeTool.androidLogger, ManifestMerger2.MergeType.APPLICATION);
        invoker.addLibraryManifests(libraries);

        try {
            MergingReport report = invoker.merge();
            if (report.getResult().isSuccess()) {
                String xml = report.getMergedDocument(MergingReport.MergedManifestKind.MERGED);
                if (out != null) {
                    try {
                        try (OutputStreamWriter writer = new OutputStreamWriter(new FileOutputStream(out), StandardCharsets.UTF_8)) {
                            writer.write(xml);
                        }
                    } catch (IOException e) {
                        throw new RuntimeException("Failed to write xml to " + out.getAbsolutePath(), e);
                    }
                } else {
                    System.out.println(xml);
                }
            } else {
                report.log(ManifestMergeTool.androidLogger);
                throw new RuntimeException("Failed to merge manifests: " + report.toString());
            }
        } catch (ManifestMerger2.MergeFailureException e) {
            throw new RuntimeException("Exception while merging manifests: " + e.toString());
        }
    }

    private static void mergePlist(File main, File[] libraries, File out) throws RuntimeException {
        InfoPlistMerger merger = new InfoPlistMerger(logger);
        merger.merge(main, libraries, out);
    }

    private static void mergeHtml(File main, File[] libraries, File out) throws RuntimeException, IOException {
        HtmlMerger merger = new HtmlMerger(logger);
        merger.merge(main, libraries, out);
    }

    public static void merge(Platform platform, File main, File output, List<File> libraries) throws RuntimeException, IOException {
        if (main == null) {
            logger.log(Level.SEVERE, "You must specify a main manifest file!");
            System.exit(1);
        }
        if (!main.exists()) {
            logger.log(Level.SEVERE, "You must specify a valid main manifest file: %s", main.getAbsolutePath());
            System.exit(1);
        }
        if (output == null) {
            logger.log(Level.SEVERE, "You must specify an output file");
            System.exit(1);
        }
        if (libraries.isEmpty()) {
            logger.log(Level.SEVERE, "You must specify at least one library file");
            System.exit(1);
        }
        for (File file : libraries) {
            if (!file.exists()) {
                logger.log(Level.SEVERE, "Manifest file does not exist: %s", file.getAbsolutePath());
            }
        }

        switch (platform) {
            case ANDROID:  mergeAndroid(main, libraries.toArray(new File[0]), output); break;
            case IOS:
            case OSX:      mergePlist(main, libraries.toArray(new File[0]), output); break;
            case WEB:    mergeHtml(main, libraries.toArray(new File[0]), output); break;
            default:
                throw new RuntimeException(String.format("Unsupported platform: %s", platform.toString()));
        };

        logger.log(Level.FINE, "Merging done");
    }

    /**
     * Merges a main manifest with several stubs
     */
    public static void main(String[] args) throws Exception {

        File main = null;
        File output = null;
        List<File> libraries = new ArrayList<>();

        Platform platform = Platform.UNKNOWN;

        int index = 0;
        for (int i = 0; i < args.length; ++i) {
            if (args[i].equals("--main") && (index+1) < args.length) {
                main = new File(args[++i]);
            }
            else if (args[i].equals("--out") && (index+1) < args.length) {
                output = new File(args[++i]);
            }
            else if (args[i].equals("--lib") && (index+1) < args.length) {
                libraries.add(new File(args[++i]));
            }

            if (args[i].equals("--platform") && (index+1) < args.length) {
                ++i;
                if (args[i].equals("android")) {
                    platform = Platform.ANDROID;
                } else if (args[i].equals("ios")) {
                    platform = Platform.IOS;
                } else if (args[i].equals("osx")) {
                    platform = Platform.OSX;
                } else if (args[i].equals("web")) {
                    platform = Platform.WEB;
                } else {
                    ManifestMergeTool.logger.log(Level.SEVERE, String.format("Unsupported platform: %s", args[i]));
                    System.exit(1);
                }
            }
        }

        try {
            merge(platform, main, output, libraries);
        } catch(Exception e) {
            ManifestMergeTool.logger.log(Level.SEVERE, e.toString());
            System.exit(1);
        }
    }




}

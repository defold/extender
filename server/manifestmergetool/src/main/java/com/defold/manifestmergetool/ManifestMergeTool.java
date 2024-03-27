/**

Run standalone with:
java -cp ./manifestmergetool.jar com.defold.manifestmergetool.ManifestMergeTool --platform html5 --main main/engine_template.html --lib extension//engine_template.html --out index.html

*/

// https://android.googlesource.com/platform/sdk/+/a35f8af/manifmerger/src/com/android/manifmerger/ManifestMerger.java

package com.defold.manifestmergetool;

import java.io.File;
import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.ArrayList;
import java.util.List;

public class ManifestMergeTool {

    public enum Platform {
        ANDROID, IOS, OSX, WEB, UNKNOWN
    }

    private static Logger logger = Logger.getLogger(ManifestMergeTool.class.getName());

    public void usage() {
        logger.log(Level.INFO, "Usage:");
        logger.log(Level.INFO, " --main <arg>\t\tThe main manifest file");
        logger.log(Level.INFO, " --lib <arg>\t\tA library manifest file. Can be used multiple times.");
        logger.log(Level.INFO, " --out <arg>\t\tThe merged output manifest file");
    }

    private static void mergeAndroid(File main, File[] libraries, File out) throws RuntimeException {
        AndroidManifestMerger merger = new AndroidManifestMerger(logger);
        merger.merge(main, libraries, out);
    }

    private static void mergePlist(File main, File[] libraries, File out) throws RuntimeException {
        InfoPlistMerger merger = new InfoPlistMerger(logger);
        merger.merge(main, libraries, out);
    }

    private static void mergeApple(File main, File[] libraries, File out) throws RuntimeException {
        if (main.getName().endsWith(".xcprivacy")) {
            PrivacyManifestMerger merger = new PrivacyManifestMerger(logger);
            merger.merge(main, libraries, out);
        }
        else {
            InfoPlistMerger merger = new InfoPlistMerger(logger);
            merger.merge(main, libraries, out);
        }
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
            logger.log(Level.SEVERE, String.format("You must specify a valid main manifest file: %s", main.getAbsolutePath()));
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
                logger.log(Level.SEVERE, String.format("Manifest file does not exist: %s", file.getAbsolutePath()));
            }
        }

        switch (platform) {
            case ANDROID:  mergeAndroid(main, libraries.toArray(new File[0]), output); break;
            case IOS:
            case OSX:      mergeApple(main, libraries.toArray(new File[0]), output); break;
            case WEB:      mergeHtml(main, libraries.toArray(new File[0]), output); break;
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

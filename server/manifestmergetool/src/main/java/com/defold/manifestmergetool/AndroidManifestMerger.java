package com.defold.manifestmergetool;

import java.io.File;
import java.io.OutputStreamWriter;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;

import java.nio.charset.StandardCharsets;

import com.android.manifmerger.ManifestMerger2;
import com.android.manifmerger.MergingReport;
import com.android.manifmerger.XmlDocument;
import com.android.utils.ILogger;

public class AndroidManifestMerger {

    private static Logger logger;
    private static ILoggerWrapper androidLogger;

    private static class ILoggerWrapper implements ILogger {
        private Logger logger;
        public ILoggerWrapper(Logger logger) {
            this.logger = logger;
        }
        public void error(Throwable t, String msgFormat, Object... args) {
            AndroidManifestMerger.logger.log(Level.SEVERE, msgFormat, args);
        }
        public void warning(String msgFormat, Object... args) {
            AndroidManifestMerger.logger.log(Level.WARNING, msgFormat, args);
        }
        public void info(String msgFormat, Object... args) {
            AndroidManifestMerger.logger.log(Level.INFO, msgFormat, args);
        }
        public void verbose(String msgFormat, Object... args) {
            AndroidManifestMerger.logger.log(Level.FINE, msgFormat, args);
        }
    }

    public AndroidManifestMerger(Logger logger) {
        this.logger = logger;
        this.androidLogger = new ILoggerWrapper(logger);

    }

    public void merge(File main, File[] libraries, File out) throws RuntimeException {
        // Good explanation of merge rules: https://android.googlesource.com/platform/tools/base/+/jb-mr2-dev/manifest-merger/src/main/java/com/android/manifmerger/ManifestMerger.java
        // https://github.com/pixnet/android-platform-tools-base/blob/master/build-system/manifest-merger/src/main/java/com/android/manifmerger/ManifestMerger2.java
        // https://android.googlesource.com/platform/tools/base/+/master/build-system/manifest-merger/src/main/java/com/android/manifmerger/Merger.java
        logger.log(Level.FINE, "Merging manifests for Android");

        ManifestMerger2.Invoker invoker = ManifestMerger2.newMerger(main, AndroidManifestMerger.androidLogger, ManifestMerger2.MergeType.APPLICATION);
        invoker.addLibraryManifests(libraries);
        invoker.withFeatures(ManifestMerger2.Invoker.Feature.REMOVE_TOOLS_DECLARATIONS);

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
                report.log(AndroidManifestMerger.androidLogger);
                throw new RuntimeException("Failed to merge manifests: " + report.toString());
            }
        } catch (ManifestMerger2.MergeFailureException e) {
            throw new RuntimeException("Exception while merging manifests: " + e.toString());
        }
    }
}

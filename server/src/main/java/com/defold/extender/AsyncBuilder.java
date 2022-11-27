package com.defold.extender;

import java.io.File;
import java.io.IOException;
import java.io.FileOutputStream;
import java.io.FileInputStream;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.List;

import com.defold.extender.metrics.MetricsWriter;
import com.defold.extender.services.DataCacheService;
import com.defold.extender.services.DefoldSdkService;
import com.defold.extender.services.GradleService;

import org.apache.commons.io.FileUtils;
import org.eclipse.jetty.io.EofException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.FileSystemUtils;

@Service
public class AsyncBuilder {
    private static final Logger LOGGER = LoggerFactory.getLogger(AsyncBuilder.class);

    private DefoldSdkService defoldSdkService;
    private DataCacheService dataCacheService;
    private GradleService gradleService;
    private File jobResultLocation;
    private long resultLifetime;
    private boolean keepJobDirectory = false;

    public AsyncBuilder(DefoldSdkService defoldSdkService,
                        DataCacheService dataCacheService,
                        GradleService gradleService,
                        @Value("${extender.job-result.location}") String jobResultLocation,
                        @Value("${extender.job-result.lifetime:1200000}") long jobResultLifetime) {
        this.defoldSdkService = defoldSdkService;
        this.dataCacheService = dataCacheService;
        this.gradleService = gradleService;
        this.jobResultLocation = new File(jobResultLocation);
        this.keepJobDirectory = System.getenv("DM_DEBUG_KEEP_JOB_FOLDER") != null && System.getenv("DM_DEBUG_JOB_FOLDER") == null;
        this.resultLifetime = jobResultLifetime;
    }

    private void writeExtenderLogsToFile(Extender extender, File file) {
        if (extender == null) return;
        try {
            FileOutputStream fos = new FileOutputStream(file, true);
            File log = extender.writeLog();
            if (log.exists()) {
                FileInputStream fis = new FileInputStream(log);
                fis.transferTo(fos);
                fis.close();
            }
            fos.close();
        }
        catch(Exception e) {
            LOGGER.error("Could not write extender logs to error file", e);
        }
    }

    private void writeExceptionToFile(Exception exception, File file) {
        try {
            FileOutputStream fos = new FileOutputStream(file, true);
            PrintWriter writer = new PrintWriter(fos);
            exception.printStackTrace(writer);
            writer.close();
            fos.close();
        }
        catch(Exception e) {
            LOGGER.error("Could not write exception to error file", e);
        }
    }

    @Async
    public void asyncBuildEngine(MetricsWriter metricsWriter, String platform, String sdkVersion,
            File jobDirectory, File uploadDirectory, File buildDirectory) throws IOException {
        String jobName = jobDirectory.getName();
        Thread.currentThread().setName(String.format("async-build-%s", jobName));
        File resultDir = new File(jobResultLocation.getAbsolutePath(), jobName);
        resultDir.mkdir();
        Extender extender = null;
        try {
            LOGGER.info("Building engine locally");

            // Get SDK
            final File sdk = defoldSdkService.getSdk(sdkVersion);
            metricsWriter.measureSdkDownload(sdkVersion);

            extender = new Extender(platform, sdk, jobDirectory, uploadDirectory, buildDirectory);

            // Resolve Gradle dependencies
            if (platform.contains("android")) {
                List<File> gradlePackages = extender.resolve(gradleService);
                metricsWriter.measureGradleDownload(gradlePackages, gradleService.getCacheSize());
            }

            // Build engine
            List<File> outputFiles = extender.build();
            metricsWriter.measureEngineBuild(platform);

            // Zip files
            String zipFilename = jobDirectory.getAbsolutePath() + File.separator + BuilderConstants.BUILD_RESULT_FILENAME;
            File zipFile = ZipUtils.zip(outputFiles, buildDirectory, zipFilename);
            metricsWriter.measureZipFiles(zipFile);

            // Write zip file to result directory
            File tmpResult = new File(resultDir, BuilderConstants.BUILD_RESULT_FILENAME + ".tmp");
            File targetResult = new File(resultDir, BuilderConstants.BUILD_RESULT_FILENAME);
            FileUtils.copyFile(zipFile, tmpResult);
            Files.move(tmpResult.toPath(), targetResult.toPath(), StandardCopyOption.ATOMIC_MOVE);
        } catch(EofException e) {
            File errorFile = new File(resultDir, BuilderConstants.BUILD_ERROR_FILENAME);
            writeExtenderLogsToFile(extender, errorFile);
            writeExceptionToFile(e, errorFile);
            LOGGER.error("Client closed connection prematurely, build aborted", e);
        } catch(Exception e) {
            File errorFile = new File(resultDir, BuilderConstants.BUILD_ERROR_FILENAME);
            writeExtenderLogsToFile(extender, errorFile);
            writeExceptionToFile(e, errorFile);
            LOGGER.error(String.format("Exception while building or sending response - SDK: %s , metrics: %s", sdkVersion, metricsWriter), e);
        } finally {
            // Regardless of success/fail status, we want to cache the uploaded files
            long totalUploadSize = dataCacheService.cacheFiles(uploadDirectory);
            metricsWriter.measureCacheUpload(totalUploadSize);

            // Delete temporary upload directory
            if (!keepJobDirectory) {
                FileUtils.deleteDirectory(jobDirectory);
            }
            else {
                LOGGER.info("Keeping job folder due to debug flags");
            }

            LOGGER.info("Job done");
        }
    }

    @Scheduled(fixedDelayString="${extender.job-result.cleanup-period:10000}")
    public void cleanUnusedResults() {
        LOGGER.debug("Clean result folder started.");
        try {
            if (jobResultLocation.exists())
            {
                Files.walk(jobResultLocation.toPath())
                        .filter(Files::isDirectory)
                        .filter(path -> ! jobResultLocation.toPath().equals(path))
                        .forEach(path -> {
                            try {
                                if (System.currentTimeMillis() - Files.getLastModifiedTime(path).toMillis() > resultLifetime) {
                                    FileSystemUtils.deleteRecursively(path);
                                    LOGGER.info("Cleaned up folder " + path.toString());
                                }
                            } catch (IOException e) {
                                LOGGER.error("Could not clear build results  " + path.toString(), e);
                            }
                        });
            }
        } catch (IOException ex) {
            LOGGER.error("Error during cleanup", ex);
        }
        LOGGER.debug("Clean result folder finished.");
    }
}

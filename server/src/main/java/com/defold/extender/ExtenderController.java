package com.defold.extender;

import com.defold.extender.remote.RemoteEngineBuilder;
import com.defold.extender.remote.RemoteHostConfiguration;
import com.defold.extender.remote.RemoteInstanceConfig;
import com.defold.extender.log.Markers;
import com.defold.extender.metrics.MetricsWriter;
import com.defold.extender.services.DefoldSdkService;
import com.defold.extender.services.DataCacheService;
import com.defold.extender.services.HealthReporterService;
import com.defold.extender.services.UserUpdateService;

import org.apache.commons.io.FilenameUtils;
import org.apache.commons.fileupload2.core.FileUploadException;
import org.apache.commons.fileupload2.jakarta.JakartaServletFileUpload;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.eclipse.jetty.io.EofException;
import org.json.simple.JSONObject;
import org.json.simple.parser.ParseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MultipartHttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@RestController
public class ExtenderController {
    public enum InstanceType {
        MIXED,
        FRONTEND_ONLY,
        BUILDER_ONLY
    };

    private static final Logger LOGGER = LoggerFactory.getLogger(ExtenderController.class);

    private static final String LATEST = "latest";

    // Used to verify the uploaded filenames
    private static final Pattern FILENAME_RE = Pattern.compile("^([\\w ](?:[\\w+\\-\\/ @]|(?:\\.[\\w+\\-\\/ ]*))+)$");

    private final DefoldSdkService defoldSdkService;
    private final DataCacheService dataCacheService;
    private final MeterRegistry meterRegistry;
    private final UserUpdateService userUpdateService;
    private final AsyncBuilder asyncBuilder;
    private final HealthReporterService healthReporter;

    private final RemoteEngineBuilder remoteEngineBuilder;
    private Map<String, RemoteInstanceConfig> remoteBuilderPlatformMappings;
    @Value("${extender.instance-type:MIXED}")
    private InstanceType instanceType;
    private final boolean remoteBuilderEnabled;

    private static long maxPackageSize = 1024*1024*1024;
    private File jobResultLocation;

    private static final String DM_DEBUG_JOB_FOLDER = System.getenv("DM_DEBUG_JOB_FOLDER");
    private static final String DM_DEBUG_KEEP_JOB_FOLDER = System.getenv("DM_DEBUG_KEEP_JOB_FOLDER");
    private static final String DM_DEBUG_JOB_UPLOAD = System.getenv("DM_DEBUG_JOB_UPLOAD");

    static private long parseSizeFromString(String size) {
        size = size.toLowerCase();
        int multiplier = 1;
        if (size.endsWith("mb")) {
            multiplier = 1024*1024;
            size = size.substring(0, size.indexOf("mb"));
        }
        else if (size.endsWith("gb")) {
            multiplier = 1024*1024*1024;
            size = size.substring(0, size.indexOf("gb"));
        } else {
            size = "1024";
            multiplier = 1024*1024;
        }

        return Long.parseLong(size) * multiplier;
    }

    public ExtenderController(DefoldSdkService defoldSdkService,
                              DataCacheService dataCacheService,
                              UserUpdateService userUpdateService,
                              MeterRegistry meterRegistry,
                              AsyncBuilder asyncBuilder,
                              RemoteEngineBuilder remoteEngineBuilder,
                              RemoteHostConfiguration remoteHostConfiguration,
                              HealthReporterService healthReporter,
                              @Value("${extender.remote-builder.enabled}") boolean remoteBuilderEnabled,
                              @Value("${spring.servlet.multipart.max-request-size}") String maxPackageSize,
                              @Value("${extender.job-result.location}") String jobResultLocation) {
        this.defoldSdkService = defoldSdkService;
        this.dataCacheService = dataCacheService;
        this.meterRegistry = meterRegistry;
        this.userUpdateService = userUpdateService;
        this.healthReporter = healthReporter;

        this.remoteEngineBuilder = remoteEngineBuilder;
        this.remoteBuilderEnabled = remoteBuilderEnabled;
        this.remoteBuilderPlatformMappings = remoteHostConfiguration.getPlatforms();
        ExtenderController.maxPackageSize = parseSizeFromString(maxPackageSize);
        this.jobResultLocation = new File(jobResultLocation);
        this.jobResultLocation.mkdirs();

        this.asyncBuilder = asyncBuilder;

        Supplier<Number> staticValue = () -> 1;
        Gauge.builder("extender.versionInfo", staticValue).tags("version", Version.appVersion, "commit_sha", Version.gitVersion).register(meterRegistry);
    }

    @ExceptionHandler({ExtenderException.class})
    public ResponseEntity<String> handleExtenderException(ExtenderException ex) {
        LOGGER.error(Markers.COMPILATION_ERROR, "Failed to build extension: " + ex.getOutput());
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.TEXT_PLAIN);
        return new ResponseEntity<>(ex.getOutput(), headers, HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @ExceptionHandler({PlatformNotSupportedException.class, VersionNotSupportedException.class})
    public ResponseEntity<String> handleUsupportedExceptions(Exception exc) {
        LOGGER.error(Markers.SERVER_ERROR, exc.getMessage(), exc);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.TEXT_PLAIN);
        return new ResponseEntity<>(exc.getMessage(), headers, HttpStatus.NOT_IMPLEMENTED);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<String> handleException(Exception ex) {
        LOGGER.error(Markers.SERVER_ERROR, HttpStatus.INTERNAL_SERVER_ERROR.getReasonPhrase(), ex);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.TEXT_PLAIN);
        return new ResponseEntity<>(HttpStatus.INTERNAL_SERVER_ERROR.getReasonPhrase(), headers, HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @GetMapping("/")
    public String index() {
        return String.format("Extender<br>%s<br>%s", Version.gitVersion, Version.buildTime);
    }

    @PostMapping(value = "/build_async/{platform}/{sdkVersion}")
    public void buildEngineAsync(HttpServletRequest _request,
                            HttpServletResponse response,
                            @PathVariable("platform") String platform,
                            @PathVariable("sdkVersion") String sdkVersionString)
            throws ExtenderException, IOException, ParseException, VersionNotSupportedException, PlatformNotSupportedException {

        boolean isMultipart = JakartaServletFileUpload.isMultipartContent(_request);
        if (!isMultipart) {
            throw new ExtenderException("The request must be a multi part request");
        }

        MultipartHttpServletRequest request = (MultipartHttpServletRequest)_request;

        this.userUpdateService.update();

        File jobDirectory;
        if (DM_DEBUG_JOB_FOLDER != null) {
            jobDirectory = new File(DM_DEBUG_JOB_FOLDER);
            if (!jobDirectory.isDirectory()) {
                throw new ExtenderException("The DM_DEBUG_JOB_FOLDER must be a directory: " + DM_DEBUG_JOB_FOLDER);
            }
            LOGGER.info("Setting DM_DEBUG_JOB_FOLDER to {}", DM_DEBUG_JOB_FOLDER);
        } else {
            jobDirectory = Files.createTempDirectory("job").toFile();
        }

        Thread.currentThread().setName(jobDirectory.getName());


        LOGGER.info("Starting build: sdk={}, platform={} job={}", sdkVersionString, platform, jobDirectory.getName());

        File uploadDirectory = new File(jobDirectory, "upload");
        uploadDirectory.mkdir();
        File buildDirectory = new File(jobDirectory, "build");
        buildDirectory.mkdir();

        final MetricsWriter metricsWriter = new MetricsWriter(meterRegistry);
        final String sdkVersion = defoldSdkService.getSdkVersion(sdkVersionString);
        boolean isBuildStarted = false;

        try {
            // Get files uploaded by the client
            receiveUpload(request, uploadDirectory);
            metricsWriter.measureReceivedRequest(request);

            // Get cached files from the cache service
            DataCacheService.DataCacheServiceInfo totalCacheDownloadInfo = dataCacheService.getCachedFiles(uploadDirectory);
            metricsWriter.measureCacheDownload(totalCacheDownloadInfo.cachedFileSize.longValue(), totalCacheDownloadInfo.cachedFileCount.intValue());

            // Cache upload before build because upload operation can be long-running and
            // build can be finished before cache upload completes
            // Regardless of success/fail status, we want to cache the uploaded files
            DataCacheService.DataCacheServiceInfo uploadResultInfo = dataCacheService.cacheFiles(uploadDirectory);
            metricsWriter.measureCacheUpload(uploadResultInfo.cachedFileSize.longValue(), uploadResultInfo.cachedFileCount.intValue());

            if (instanceType.equals(InstanceType.BUILDER_ONLY)) {
                asyncBuilder.asyncBuildEngine(metricsWriter, platform, sdkVersion, jobDirectory, uploadDirectory, buildDirectory);
            } else {
                String[] buildEnvDescription = null;
                try {
                    JSONObject mappings = defoldSdkService.getPlatformSdkMappings(sdkVersion);
                    buildEnvDescription = ExtenderUtil.getSdksForPlatform(platform, mappings);
                } catch(ExtenderException exc) {
                    if (instanceType.equals(InstanceType.FRONTEND_ONLY)) {
                        LOGGER.error("Unsupported engine version '{}'", sdkVersion);
                        throw new VersionNotSupportedException(sdkVersion);
                    }
                } catch (NullPointerException exc) {
                    LOGGER.error("Unsupported build platform '{}'", platform);
                    throw new PlatformNotSupportedException(platform);
                }
                // Build engine locally or on remote builder
                if (remoteBuilderEnabled && buildEnvDescription != null && isRemotePlatform(buildEnvDescription[0], buildEnvDescription[1])) {
                    LOGGER.info("Building engine on remote builder");
                    RemoteInstanceConfig remoteInstanceConfig = getRemoteBuilderConfig(buildEnvDescription[0], buildEnvDescription[1]);
                    this.remoteEngineBuilder.buildAsync(remoteInstanceConfig, uploadDirectory, platform, sdkVersion, jobDirectory, buildDirectory, metricsWriter);
                } else if (instanceType.equals(InstanceType.MIXED)) {
                    asyncBuilder.asyncBuildEngine(metricsWriter, platform, sdkVersion, jobDirectory, uploadDirectory, buildDirectory);
                } else {
                    // no remote builder was found and current instance can't build
                    LOGGER.error("Unsupported build platform '{}'", platform);
                    throw new PlatformNotSupportedException(platform);
                }
            }
            response.getWriter().write(jobDirectory.getName());
            response.getWriter().flush();
            response.getWriter().close();
            metricsWriter.measureSentResponse();
            isBuildStarted = true;
        } catch(EofException e) {
            throw new ExtenderException(e, "Client closed connection prematurely, build aborted");
        } catch(FileUploadException e) {
            throw new ExtenderException(e, "Bad request: " + e.getMessage());
        } catch(VersionNotSupportedException|PlatformNotSupportedException exc) {
            throw exc;
        } catch(Exception e) {
            LOGGER.error(String.format("Exception while building or sending response - SDK: %s", sdkVersion));
            throw e;
        } finally {
            boolean deleteDirectory = true;
            if (DM_DEBUG_KEEP_JOB_FOLDER != null) {
                deleteDirectory = false;
            }
            if (DM_DEBUG_JOB_FOLDER != null) {
                deleteDirectory = false;
            }
            // Delete temporary upload directory
            if (deleteDirectory && !isBuildStarted) {
                if (!FileUtils.deleteQuietly(jobDirectory)) {
                    LOGGER.warn("Failed to delete job directory");
                }
            }
            else {
                if (!deleteDirectory)
                {
                    LOGGER.info("Keeping job folder due to debug flags");
                }
            }
        }
    }

    @PostMapping(value = "/query")
    public void queryFiles(HttpServletRequest request, HttpServletResponse response) throws ExtenderException {
        InputStream input;
        OutputStream output;
        try {
            input = request.getInputStream();
        } catch (IOException e) {
            LOGGER.error(Markers.SERVER_ERROR, "Failed to get input stream: " + e.getMessage());
            throw new ExtenderException(e, "Failed to get input stream: " + e.getMessage());
        }

        try {
            output = response.getOutputStream();
        } catch (IOException e) {
            LOGGER.error(Markers.SERVER_ERROR, "Failed to get output stream: " + e.getMessage());
            throw new ExtenderException(e, "Failed to get output stream: " + e.getMessage());
        }

        response.setContentType("application/json");

        dataCacheService.queryCache(input, output);
    }

    @GetMapping("/job_status")
    @ResponseBody
    public Integer getBuildStatus(@RequestParam(name = "jobId") String jobId) throws IOException {
        File jobResultDir = new File(jobResultLocation.getAbsolutePath() + "/" + jobId);
        if (jobResultDir.exists()) {
            File jobResult = new File(jobResultDir, BuilderConstants.BUILD_RESULT_FILENAME);
            File errorResult = new File(jobResultDir, BuilderConstants.BUILD_ERROR_FILENAME);
            if (jobResult.exists()) {
                return BuilderConstants.JobStatus.SUCCESS.ordinal();
            } else if (errorResult.exists()) {
                return BuilderConstants.JobStatus.ERROR.ordinal();
            }
        }
        return BuilderConstants.JobStatus.NOT_FOUND.ordinal();
    }

    @GetMapping("/job_result")
    public @ResponseBody byte[] getBuildResult(@RequestParam(name = "jobId") String jobId) throws IOException {
        File jobResultDir = new File(jobResultLocation.getAbsolutePath() + "/" + jobId);
        if (jobResultDir.exists()) {
            File jobResult = new File(jobResultDir, BuilderConstants.BUILD_RESULT_FILENAME);
            File errorResult = new File(jobResultDir, BuilderConstants.BUILD_ERROR_FILENAME);
            if (jobResult.exists()) {
                InputStream in = new FileInputStream(jobResult);
                return IOUtils.toByteArray(in);
            } else if (errorResult.exists()) {
                InputStream in = new FileInputStream(errorResult);
                return IOUtils.toByteArray(in);
            }
        }
        return null;
    }

    @GetMapping(path= "/health_report", produces="application/json")
    @ResponseBody
    @CrossOrigin
    public String getHealthReport() {
        return healthReporter.collectHealthReport(remoteBuilderEnabled, remoteBuilderPlatformMappings);
    }

    static private boolean isRelativePath(File parent, File file) throws IOException {
        String parentPath = parent.getCanonicalPath();
        String filePath = file.getCanonicalPath();
        return filePath.startsWith(parentPath);
    }

    static boolean ignoreFilename(String path) throws ExtenderException {
        String name = FilenameUtils.getName(path);

        boolean ignore = false;
        ignore = ignore || name.equals(".DS_Store");
        if (ignore) {
            LOGGER.debug(String.format("ignoreFilename: %s", path));
        }
        return ignore;
    }

    static void validateFilename(String path) throws ExtenderException {
        Matcher m = ExtenderController.FILENAME_RE.matcher(path);
        if (!m.matches()) {
            throw new ExtenderException(String.format("Filename '%s' is invalid or contains invalid characters", path));
        }
    }

    static void receiveUpload(MultipartHttpServletRequest request, File uploadDirectory) throws IOException, FileUploadException, ExtenderException {
        if (request.getContentLengthLong() > ExtenderController.maxPackageSize ) {
            String msg = String.format("Build request is too large: %d bytes. Max allowed size is %d bytes.", request.getContentLengthLong(), ExtenderController.maxPackageSize);
            LOGGER.error(msg);
            throw new ExtenderException(msg);
        }

        if (DM_DEBUG_JOB_UPLOAD != null) {
            LOGGER.info("receiveUpload");
        }

        Map<String, MultipartFile> files = request.getFileMap();

        if (files.size() == 0) {
            throw new ExtenderException("The build request contained no files!");
        }

        // Parse the request
        for (String key : files.keySet())
        {
            String name = key.replace('\\', File.separatorChar);

            MultipartFile multipartfile = files.get(key);
            if (ignoreFilename(name)) {
                continue;
            }

            validateFilename(name);

            File file = new File(uploadDirectory, name);

            if (!isRelativePath(uploadDirectory, file)) { // in case the name contains "../"
                throw new ExtenderException(String.format("Files must be relative to the upload package: '%s'", key));
            }
            if (file.exists()) {
                String msg = String.format("Duplicate file in received zip file: '%s'", name);
                if (DM_DEBUG_JOB_FOLDER == null) {
                    LOGGER.info(msg);
                } else {
                    throw new ExtenderException(msg);
                }
            }

            Files.createDirectories(file.getParentFile().toPath());

            try (InputStream inputStream = multipartfile.getInputStream()) {
                Files.copy(inputStream, file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }

            if (DM_DEBUG_JOB_UPLOAD != null) {
                System.out.printf("    %s\n", file.toPath());
            }
        }

        // check if source code archive presented
        File sourceCodeArchive = new File(uploadDirectory, ExtenderConst.SOURCE_CODE_ARCHIVE_MAGIC_NAME);
        if (sourceCodeArchive.exists()) {
            LOGGER.debug("Source code archive found. Unarchiving...");
            ZipUtils.unzip(new FileInputStream(sourceCodeArchive), uploadDirectory.toPath());
        }
    }

    private boolean isRemotePlatform(final String platform, String platformVersion) {
        return this.remoteBuilderPlatformMappings.containsKey(String.format("%s-%s", platform, platformVersion)) 
            || this.remoteBuilderPlatformMappings.containsKey(String.format("%s-%s", platform, ExtenderController.LATEST));
    }

    private RemoteInstanceConfig getRemoteBuilderConfig(String platform, String platformVersion) throws ExtenderException{
        String fullKey = String.format("%s-%s", platform, platformVersion);
        String fallbackKey = String.format("%s-%s", platform, ExtenderController.LATEST);
        if (this.remoteBuilderPlatformMappings.containsKey(fullKey)) {
            return this.remoteBuilderPlatformMappings.get(fullKey);
        } else if (this.remoteBuilderPlatformMappings.containsKey(fallbackKey)) {
            return this.remoteBuilderPlatformMappings.get(fallbackKey);
        }
        throw new ExtenderException(String.format("No suitable remote builder found for %", fullKey));
    }
}

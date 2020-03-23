package com.defold.extender;

import com.defold.extender.remote.RemoteEngineBuilder;
import com.defold.extender.metrics.MetricsWriter;
import com.defold.extender.services.DefoldSdkService;
import com.defold.extender.services.DataCacheService;
import com.defold.extender.services.GradleService;
import org.apache.commons.fileupload.FileItemIterator;
import org.apache.commons.fileupload.FileItemStream;
import org.apache.commons.fileupload.FileUploadException;
import org.apache.commons.fileupload.servlet.ServletFileUpload;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.eclipse.jetty.io.EofException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.metrics.GaugeService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@RestController
public class ExtenderController {
    private static final Logger LOGGER = LoggerFactory.getLogger(ExtenderController.class);

    // Used to verify the uploaded filenames
    private static final Pattern FILENAME_RE = Pattern.compile("^([\\w ](?:[\\w+\\-\\/ @]|(?:\\.[\\w+\\-\\/ ]*))+)$");

    private final DefoldSdkService defoldSdkService;
    private final DataCacheService dataCacheService;
    private final GradleService gradleService;
    private final GaugeService gaugeService;

    private final RemoteEngineBuilder remoteEngineBuilder;
    private final boolean remoteBuilderEnabled;
    private final String[] remoteBuilderPlatforms;
    private static int maxPackageSize;

    private static final String DM_DEBUG_JOB_FOLDER = System.getenv("DM_DEBUG_JOB_FOLDER");
    private static final String DM_DEBUG_JOB_UPLOAD = System.getenv("DM_DEBUG_JOB_UPLOAD");

    static private int parseSizeFromString(String size) {
        size = size.toLowerCase();
        int multiplier = 1;
        if (size.endsWith("mb")) {
            multiplier = 1024*1024;
            size = size.substring(0, size.indexOf("mb"));
        }
        else if (size.endsWith("gb")) {
            multiplier = 1024*1024*1024;
            size = size.substring(0, size.indexOf("gb"));
        }

        return Integer.parseInt(size) * multiplier;
    }

    @Autowired
    public ExtenderController(DefoldSdkService defoldSdkService,
                              DataCacheService dataCacheService,
                              GradleService gradleService,
                              @Qualifier("gaugeService") GaugeService gaugeService,
                              RemoteEngineBuilder remoteEngineBuilder,
                              @Value("${extender.remote-builder.enabled}") boolean remoteBuilderEnabled,
                              @Value("${extender.remote-builder.platforms}") String[] remoteBuilderPlatforms,
                              @Value("${spring.http.multipart.max-request-size}") String maxPackageSize) {
        this.defoldSdkService = defoldSdkService;
        this.dataCacheService = dataCacheService;
        this.gradleService = gradleService;
        this.gaugeService = gaugeService;

        this.remoteEngineBuilder = remoteEngineBuilder;
        this.remoteBuilderEnabled = remoteBuilderEnabled;
        this.remoteBuilderPlatforms = remoteBuilderPlatforms;
        this.maxPackageSize = parseSizeFromString(maxPackageSize);
    }

    @ExceptionHandler({ExtenderException.class})
    public ResponseEntity<String> handleExtenderException(ExtenderException ex) {
        LOGGER.error("Failed to build extension: " + ex.getOutput());
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.TEXT_PLAIN);
        return new ResponseEntity<>(ex.getOutput(), headers, HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<String> handleException(Exception ex) {
        LOGGER.error(HttpStatus.INTERNAL_SERVER_ERROR.getReasonPhrase(), ex);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.TEXT_PLAIN);
        return new ResponseEntity<>(HttpStatus.INTERNAL_SERVER_ERROR.getReasonPhrase(), headers, HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @RequestMapping("/")
    public String index() {
        return String.format("Extender<br>%s<br>%s", Version.gitVersion, Version.buildTime);
    }

    @RequestMapping(method = RequestMethod.POST, value = "/build/{platform}")
    public void buildEngineLocal(HttpServletRequest request, HttpServletResponse response,
                                 @PathVariable("platform") String platform)
            throws URISyntaxException, IOException, ExtenderException {

        if (defoldSdkService.isLocalSdkSupported()) {
            buildEngine(request, response, platform, null);
            return;
        }

        throw new ExtenderException("No SDK version specified.");
    }

    @RequestMapping(method = RequestMethod.POST, value = "/build/{platform}/{sdkVersion}")
    public void buildEngine(HttpServletRequest request,
                            HttpServletResponse response,
                            @PathVariable("platform") String platform,
                            @PathVariable("sdkVersion") String sdkVersionString)
            throws ExtenderException, IOException, URISyntaxException {

        boolean isMultipart = ServletFileUpload.isMultipartContent(request);
        if (!isMultipart) {
            throw new ExtenderException("The request must be a multi part request");
        }

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

        final MetricsWriter metricsWriter = new MetricsWriter(gaugeService);
        final String sdkVersion = defoldSdkService.getSdkVersion(sdkVersionString);

        try {
            // Get files uploaded by the client
            receiveUpload(request, uploadDirectory);
            metricsWriter.measureReceivedRequest(request);

            // Get cached files from the cache service
            long totalCacheDownloadSize = dataCacheService.getCachedFiles(uploadDirectory);
            metricsWriter.measureCacheDownload(totalCacheDownloadSize);

            // Build engine locally or on remote builder
            if (remoteBuilderEnabled && isRemotePlatform(platform)) {
                LOGGER.info("Building engine on remote builder");

                final byte[] bytes = remoteEngineBuilder.build(uploadDirectory, platform, sdkVersion);
                metricsWriter.measureRemoteEngineBuild(platform);

                IOUtils.copyLarge(new ByteArrayInputStream(bytes), response.getOutputStream());
            } else {
                LOGGER.info("Building engine locally");

                // Get SDK
                final File sdk = defoldSdkService.getSdk(sdkVersion);
                metricsWriter.measureSdkDownload(sdkVersion);

                List<File> gradlePackages = null;
                if (platform.contains("android")) {
                    gradlePackages = gradleService.resolveDependencies(jobDirectory);
                    metricsWriter.measureGradleDownload(gradlePackages, gradleService.getCacheSize());
                }

                // Build engine
                Extender extender = new Extender(platform, sdk, jobDirectory, uploadDirectory, buildDirectory, gradlePackages);
                List<File> outputFiles = extender.build();
                metricsWriter.measureEngineBuild(platform);

                // Zip files
                String zipFilename = jobDirectory.getAbsolutePath() + "/build.zip";
                File zipFile = ZipUtils.zip(outputFiles, buildDirectory, zipFilename);
                metricsWriter.measureZipFiles(zipFile);

                // Write zip file to response
                FileUtils.copyFile(zipFile, response.getOutputStream());
            }

            response.flushBuffer();
            response.getOutputStream().close(); // No need for the user to wait for our upload to the cache
            metricsWriter.measureSentResponse();

        } catch(EofException e) {
            throw new ExtenderException(e, "Client closed connection prematurely, build aborted");
        } catch(FileUploadException e) {
            throw new ExtenderException(e, "Bad request: " + e.getMessage());
        } catch(Exception e) {
            LOGGER.error("Exception while building or sending response - SDK: " + sdkVersion + " , metrics: " + metricsWriter);
            throw e;
        } finally {
            // Regardless of success/fail status, we want to cache the uploaded files
            long totalUploadSize = dataCacheService.cacheFiles(uploadDirectory);
            metricsWriter.measureCacheUpload(totalUploadSize);

            // Delete temporary upload directory
            if (DM_DEBUG_JOB_FOLDER == null) {
                FileUtils.deleteDirectory(jobDirectory);
            }

            LOGGER.info("Job done");
        }
    }

    @RequestMapping(method = RequestMethod.POST, value = "/query")
    public void queryFiles(HttpServletRequest request, HttpServletResponse response) throws ExtenderException {
        InputStream input;
        OutputStream output;
        try {
            input = request.getInputStream();
        } catch (IOException e) {
            LOGGER.error("Failed to get input stream: " + e.getMessage());
            throw new ExtenderException(e, "Failed to get input stream: " + e.getMessage());
        }

        try {
            output = response.getOutputStream();
        } catch (IOException e) {
            LOGGER.error("Failed to get output stream: " + e.getMessage());
            throw new ExtenderException(e, "Failed to get output stream: " + e.getMessage());
        }

        response.setContentType("application/json");

        dataCacheService.queryCache(input, output);
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
            LOGGER.debug("ignoreFilename: %s", path);
        }
        return ignore;
    }

    static void validateFilename(String path) throws ExtenderException {
        Matcher m = ExtenderController.FILENAME_RE.matcher(path);
        if (!m.matches()) {
            throw new ExtenderException(String.format("Filename '%s' is invalid or contains invalid characters", path));
        }
    }

    static void receiveUpload(HttpServletRequest request, File uploadDirectory) throws IOException, FileUploadException, ExtenderException {
        if (request.getContentLength() > ExtenderController.maxPackageSize ) {
            String msg = String.format("Build request is too large: %d bytes. Max allowed size is %d bytes.", request.getContentLength(), ExtenderController.maxPackageSize);
            LOGGER.error(msg);
            throw new ExtenderException(msg);
        }

        if (DM_DEBUG_JOB_UPLOAD != null) {
            LOGGER.info("receiveUpload");
        }

        // Create a new file upload handler
        ServletFileUpload upload = new ServletFileUpload();

        // Parse the request
        FileItemIterator iter = upload.getItemIterator(request);
        int count = 0;
        while (iter.hasNext()) {
            FileItemStream item = iter.next();
            String name = item.getName().replace('\\', File.separatorChar);
            if (!item.isFormField()) {

                if (ignoreFilename(name)) {
                    continue;
                }

                validateFilename(name);

                File file = new File(uploadDirectory, name);

                if (!isRelativePath(uploadDirectory, file)) { // in case the name contains "../"
                    throw new ExtenderException(String.format("Files must be relative to the upload package: '%s'", item.getName()));
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

                try (InputStream inputStream = item.openStream()) {
                    Files.copy(inputStream, file.toPath(), StandardCopyOption.REPLACE_EXISTING);
                }

                if (DM_DEBUG_JOB_UPLOAD != null) {
                    System.out.printf("    %s\n", file.toPath());
                }

                count++;
            }
        }

        if (count == 0) {
            throw new ExtenderException("The build request contained no files!");
        }
    }

    private boolean isRemotePlatform(final String platform) {
        for (final String candidate : remoteBuilderPlatforms) {
            if (platform.endsWith(candidate)) {
                return true;
            }
        }
        return false;
    }
}

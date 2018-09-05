package com.defold.extender;

import com.defold.extender.metrics.MetricsWriter;
import com.defold.extender.services.DefoldSdkService;
import org.apache.commons.fileupload.FileItemIterator;
import org.apache.commons.fileupload.FileItemStream;
import org.apache.commons.fileupload.FileUploadException;
import org.apache.commons.fileupload.servlet.ServletFileUpload;
import org.apache.commons.io.FileUtils;
import org.eclipse.jetty.io.EofException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.actuate.metrics.GaugeService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@RestController
public class ExtenderController {
    private static final Logger LOGGER = LoggerFactory.getLogger(ExtenderController.class);

    // Used to verify the uploaded filenames
    private static final Pattern FILENAME_RE = Pattern.compile("^([\\w ](?:[\\w+\\-\\/ @]|(?:\\.[\\w+\\-\\/ ]*))+)$");

    private final DefoldSdkService defoldSdkService;
    private final GaugeService gaugeService;

    private static final int MAX_PACKAGE_SIZE = 512 * 1024*1024; // The max size of any upload package

    @Autowired
    public ExtenderController(DefoldSdkService defoldSdkService, @Qualifier("gaugeService") GaugeService gaugeService) {
        this.defoldSdkService = defoldSdkService;
        this.gaugeService = gaugeService;
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
        return "Extender";
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
                            @PathVariable("sdkVersion") String sdkVersion)
            throws ExtenderException, IOException, URISyntaxException {

        boolean isMultipart = ServletFileUpload.isMultipartContent(request);
        if (!isMultipart) {
            throw new ExtenderException("The request must be a multi part request");
        }

        File jobDirectory = Files.createTempDirectory("job").toFile();
        File uploadDirectory = new File(jobDirectory, "upload");
        uploadDirectory.mkdir();
        File buildDirectory = new File(jobDirectory, "build");
        buildDirectory.mkdir();

        MetricsWriter metricsWriter = new MetricsWriter(gaugeService);

        try {
            receiveUpload(request, uploadDirectory);
            metricsWriter.measureReceivedRequest(request);

            // Get SDK
            File sdk;
            if (sdkVersion == null || System.getenv("DYNAMO_HOME") != null) {
                sdk = defoldSdkService.getLocalSdk();
            } else {
                sdk = defoldSdkService.getSdk(sdkVersion);
            }
            metricsWriter.measureSdkDownload();

            Extender extender = new Extender(platform, sdk, jobDirectory, uploadDirectory, buildDirectory);

            // Build engine
            List<File> outputFiles = extender.build();
            metricsWriter.measureEngineBuild(platform);

            // Zip files
            String zipFilename = jobDirectory.getAbsolutePath() + "/build.zip";
            File zipFile = ZipUtils.zip(outputFiles, zipFilename);
            metricsWriter.measureZipFiles(zipFile);

            // Write zip file to response
            FileUtils.copyFile(zipFile, response.getOutputStream());
            response.flushBuffer();
            metricsWriter.measureSentResponse();

        } catch(EofException e) {
            throw new ExtenderException("Client closed connection prematurely, build aborted");
        } catch(FileUploadException e) {
            throw new ExtenderException("Bad request: " + e.getMessage());
        } catch(Exception e) {
            LOGGER.error("Exception while building or sending response - SDK: " + sdkVersion + " , metrics: " + metricsWriter);
            throw e;
        } finally {
            // Delete temporary upload directory
            FileUtils.deleteDirectory(jobDirectory);
        }
    }

    static private boolean isRelativePath(File parent, File file) throws IOException {
        String parentPath = parent.getCanonicalPath();
        String filePath = file.getCanonicalPath();
        return filePath.startsWith(parentPath);
    }

    static void validateFilename(String path) throws ExtenderException {
        Matcher m = ExtenderController.FILENAME_RE.matcher(path);
        if (!m.matches()) {
            throw new ExtenderException(String.format("Filename '%s' is invalid or contains invalid characters", path));
        }
    }

    static void receiveUpload(HttpServletRequest request, File uploadDirectory) throws IOException, FileUploadException, ExtenderException {
        if (request.getContentLength() > MAX_PACKAGE_SIZE ) {
            throw new ExtenderException(String.format("Build request is too large: %d bytes. Max allowed size is %d bytes.", request.getContentLength(), MAX_PACKAGE_SIZE));
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

                validateFilename(name);

                File file = new File(uploadDirectory, name);

                if (!isRelativePath(uploadDirectory, file)) { // in case the name contains "../"
                    throw new ExtenderException(String.format("Files must be relative to the upload package: '%s'", item.getName()));
                }

                Files.createDirectories(file.getParentFile().toPath());

                try (InputStream inputStream = item.openStream()) {
                    Files.copy(inputStream, file.toPath());
                }

                count++;
            }
        }

        if (count == 0) {
            throw new ExtenderException("The build request contained no files!");
        }
    }
}

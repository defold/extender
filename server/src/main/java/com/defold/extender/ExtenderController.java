package com.defold.extender;

import com.defold.extender.services.DefoldSdkService;
import org.apache.commons.io.FileUtils;
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
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MultipartHttpServletRequest;

import javax.servlet.http.HttpServletResponse;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@RestController
public class ExtenderController {
    private static final Logger LOGGER = LoggerFactory.getLogger(ExtenderController.class);

    // Used to verify the uploaded filenames
    private static final Pattern FILENAME_RE = Pattern.compile("^([A-Za-z0-9_ ](?:[A-Za-z0-9_+\\-/ ]|(?:\\.[A-Za-z0-9_+\\-/ ]))+)$");

    private final DefoldSdkService defoldSdkService;
    private final GaugeService gaugeService;

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
    public void buildEngineLocal(MultipartHttpServletRequest request, HttpServletResponse response,
                                 @PathVariable("platform") String platform)
            throws URISyntaxException, IOException, ExtenderException {

        if (defoldSdkService.isLocalSdkSupported()) {
            buildEngine(request, response, platform, null);
            return;
        }

        throw new ExtenderException("No SDK version specified.");
    }

    @RequestMapping(method = RequestMethod.POST, value = "/build/{platform}/{sdkVersion}")
    public void buildEngine(MultipartHttpServletRequest request,
                            HttpServletResponse response,
                            @PathVariable("platform") String platform,
                            @PathVariable("sdkVersion") String sdkVersion)
            throws ExtenderException, IOException, URISyntaxException {

        File jobDirectory = Files.createTempDirectory("job").toFile();
        File uploadDirectory = new File(jobDirectory, "upload");
        uploadDirectory.mkdir();
        File buildDirectory = new File(jobDirectory, "build");
        buildDirectory.mkdir();

        try {
            Timer timer = new Timer();
            timer.start();

            validateFilenames(request);
            receiveUpload(request, uploadDirectory);

            gaugeService.submit("job.receive",  timer.start());

            // Get SDK
            File sdk;
            if (sdkVersion == null || System.getenv("DYNAMO_HOME") != null) {
                sdk = defoldSdkService.getLocalSdk();
            } else {
                sdk = defoldSdkService.getSdk(sdkVersion);
            }

            gaugeService.submit("job.sdkDownload",  timer.start());

            Extender extender = new Extender(platform, sdk, jobDirectory, uploadDirectory, buildDirectory);

            // Build and write output files to output stream
            List<File> outputFiles = extender.build();
            gaugeService.submit("job.build." + platform,  timer.start());

            ZipUtils.zip(response.getOutputStream(), outputFiles);
            gaugeService.submit("job.write",  timer.start());
        } finally {
            // Run top and log result
            ProcessExecutor processExecutor = new ProcessExecutor();
            try {
                processExecutor.execute("top -b -n 1 -o %MEM");
                LOGGER.info(processExecutor.getOutput());
            } catch (InterruptedException e) {
                LOGGER.warn("Failed to run top after build.");
            }

            // Delete temporary upload directory
            FileUtils.deleteDirectory(jobDirectory);
        }
    }

    static void validateFilenames(MultipartHttpServletRequest request) throws ExtenderException {
        Set<String> keys = request.getMultiFileMap().keySet();

        for (String key : keys) {
            MultipartFile multipartFile = request.getMultiFileMap().getFirst(key);
            Matcher m = ExtenderController.FILENAME_RE.matcher(multipartFile.getName());
            if (!m.matches()) {
                throw new ExtenderException(String.format("Filename '%s' is invalid or contains invalid characters", multipartFile.getName()));
            }
        }
    }

    static private boolean isRelativePath(File parent, File file) throws IOException {
        String parentPath = parent.getCanonicalPath();
        String filePath = file.getCanonicalPath();
        return filePath.startsWith(parentPath);
    }

    static void receiveUpload(MultipartHttpServletRequest request, File uploadDirectory) throws IOException, ExtenderException {
        Set<String> keys = request.getMultiFileMap().keySet();

        for (String key : keys) {
            MultipartFile multipartFile = request.getMultiFileMap().getFirst(key);

            // translate it into a valid filename
            String name = multipartFile.getName().replace('\\', File.separatorChar);
            File file = new File(uploadDirectory, name);

            if (!isRelativePath(uploadDirectory, file)) {
                throw new ExtenderException(String.format("Files must be relative to the upload package: '%s'", multipartFile.getName()));
            }

            Files.createDirectories(file.getParentFile().toPath());
            try (InputStream inputStream = multipartFile.getInputStream()) {
                Files.copy(inputStream, file.toPath());
            }
        }
    }
}

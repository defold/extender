package com.defold.extender;

import com.defold.extender.services.DefoldSdkService;
import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
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
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@RestController
public class ExtenderController {
    private static final Logger LOGGER = LoggerFactory.getLogger(ExtenderController.class);

    // Used to verify the uploaded filenames
    private static final Pattern FILENAME_RE = Pattern.compile("^([A-Za-z0-9_ ](?:[A-Za-z0-9_\\-\\/ ]|(?:\\.[A-Za-z0-9_\\-\\/ ]))+)$");

    private final DefoldSdkService defoldSdkService;

    @Value("${extender.build-location}") String buildDirectory;

    @Autowired
    public ExtenderController(DefoldSdkService defoldSdkService) {
        this.defoldSdkService = defoldSdkService;
    }

    @RequestMapping("/")
    public String index() {
        return "Extender";
    }

    @RequestMapping(method = RequestMethod.POST, value = "/build/{platform}")
    public void buildEngineLocal(MultipartHttpServletRequest req, HttpServletResponse resp,
                                 @PathVariable("platform") String platform)
            throws URISyntaxException, IOException, ExtenderException {

        buildEngine(req, resp, platform, null);
    }

    @ExceptionHandler({ ExtenderException.class })
    public ResponseEntity<String> handleIllegalArgumentException(ExtenderException ex) {
        LOGGER.error("Failed to build extension: " + ex.getOutput());
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.TEXT_PLAIN);
        return new ResponseEntity<>(ex.getOutput(), headers, HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @RequestMapping(method = RequestMethod.POST, value = "/build/{platform}/{sdkVersion}")
    public void buildEngine(MultipartHttpServletRequest request,
                            HttpServletResponse response,
                            @PathVariable("platform") String platform,
                            @PathVariable("sdkVersion") String sdkVersion)
            throws ExtenderException, IOException, URISyntaxException {

        // TODO: Make upload directory configurable
        File uploadDirectory = Files.createTempDirectory("upload").toFile();

        try {
            validateFilenames(request);
            receiveUpload(request, uploadDirectory);

            // Get SDK
            File sdk;
            if (sdkVersion == null) {
                sdk = defoldSdkService.getLocalSdk();
            } else {
                sdk = defoldSdkService.getSdk(sdkVersion);
            }

            Extender extender = new Extender(platform, uploadDirectory, sdk, buildDirectory);
            File exe = extender.buildEngine();

            // Write executable to output stream
            ZipUtils.zip(response.getOutputStream(), exe);

            extender.dispose();
        } finally {
            // Delete temporary upload directory
            FileUtils.deleteDirectory(uploadDirectory);
        }
    }

    static public void validateFilenames(MultipartHttpServletRequest request) throws ExtenderException {
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

    static public void receiveUpload(MultipartHttpServletRequest request, File uploadDirectory) throws IOException, ExtenderException {
        Set<String> keys = request.getMultiFileMap().keySet();

        for (String key : keys) {
            MultipartFile multipartFile = request.getMultiFileMap().getFirst(key);
            File file = new File(uploadDirectory, multipartFile.getName());

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

package com.defold.extender;

import com.defold.extender.services.DefoldSdkService;
import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MultipartHttpServletRequest;

import javax.servlet.http.HttpServletResponse;
import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.util.Set;

@RestController
public class ExtenderController {
    private static final Logger LOGGER = LoggerFactory.getLogger(ExtenderController.class);

    private final DefoldSdkService defoldSdkService;

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
            throws IOException, InterruptedException, URISyntaxException {

        buildEngine(req, resp, platform, null);
    }

    @RequestMapping(method = RequestMethod.POST, value = "/build/{platform}/{sdkVersion}")
    public void buildEngine(MultipartHttpServletRequest request,
                            HttpServletResponse response,
                            @PathVariable("platform") String platform,
                            @PathVariable("sdkVersion") String sdkVersion)
            throws IOException, InterruptedException, URISyntaxException {

        // TODO: Make upload directory configurable
        File uploadDirectory = Files.createTempDirectory("upload").toFile();

        try {
            receiveUpload(request, uploadDirectory);

            // Get SDK
            File sdk;
            if (sdkVersion == null) {
                sdk = defoldSdkService.getLocalSdk();
            } else {
                sdk = defoldSdkService.getSdk(sdkVersion);
            }

            Extender extender = new Extender(platform, uploadDirectory, sdk);

            File exe;
            try {
                exe = extender.buildEngine();
            } catch (IOException | InterruptedException e) {
                LOGGER.error("Failed to build extension.", e);
                throw e;
            }

            // Write executable to output stream
            FileUtils.copyFile(exe, response.getOutputStream());

            extender.dispose();
        } finally {
            // Delete temporary upload directory
            FileUtils.deleteDirectory(uploadDirectory);
        }
    }

    private void receiveUpload(MultipartHttpServletRequest request, File uploadDirectory) throws IOException {
        Set<String> keys = request.getMultiFileMap().keySet();

        for (String key : keys) {
            MultipartFile multipartFile = request.getMultiFileMap().getFirst(key);
            File file = new File(uploadDirectory, multipartFile.getName());
            Files.createDirectories(file.getParentFile().toPath());
            Files.copy(multipartFile.getInputStream(), file.toPath());
        }
    }
}
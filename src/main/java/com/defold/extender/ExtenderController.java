package com.defold.extender;

import org.apache.commons.io.FileUtils;
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

    private final DefoldSdkService defoldSdkService;

    @Autowired
    public ExtenderController(DefoldSdkService defoldSdkService) {
        this.defoldSdkService = defoldSdkService;
    }

    @RequestMapping("/")
    public String index() {
        return "Extender";
    }

    @RequestMapping(method = RequestMethod.POST, value = "/build/{platform}/{sdkVersion}")
    public void buildEngine(MultipartHttpServletRequest req, HttpServletResponse resp,
                            @PathVariable("platform") String platform,
                            @PathVariable("sdkVersion") String sdkVersion)
            throws IOException, InterruptedException, URISyntaxException {

        Set<String> keys = req.getMultiFileMap().keySet();
        File src = Files.createTempDirectory("engine").toFile();

        try {
            // Store uploaded source files on disk
            for (String k : keys) {
                MultipartFile multipartFile = req.getMultiFileMap().getFirst(k);
                File f = new File(src, multipartFile.getName());
                f.getParentFile().mkdirs();
                Files.copy(multipartFile.getInputStream(), f.toPath());
            }

            // Get SDK
            File sdk = defoldSdkService.getSdk(sdkVersion);

            Extender extender = new Extender(platform, src, sdk);
            File exe = extender.buildEngine();
            FileUtils.copyFile(exe, resp.getOutputStream());
            extender.dispose();

        } finally {
            FileUtils.deleteDirectory(src);
        }
    }

    @RequestMapping(method = RequestMethod.POST, value = "/build/{platform}")
    public void buildEngineLocal(MultipartHttpServletRequest req, HttpServletResponse resp,
                            @PathVariable("platform") String platform)
            throws IOException, InterruptedException, URISyntaxException {

        buildEngine(req, resp, platform, "78c69d18904b19926eefb4647dda0a2f72892d5d");
    }


}


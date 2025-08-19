package com.defold.extender.utils;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import org.apache.commons.text.StringSubstitutor;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;

import com.defold.extender.ExtenderException;
import com.defold.extender.process.ProcessUtils;
import com.defold.extender.services.cocoapods.PodBuildSpec;

public class PodBuildUtil {
    public static void putFileNameIntoVFS(Map<String, Collection<File>> vfsMap, String section, File file) {
        if (!vfsMap.containsKey(section)) {
            vfsMap.put(section, new HashSet<>());
        }
        vfsMap.get(section).add(file);
    }

    public static File generateHeaderMap(PodBuildSpec spec) throws IOException, ExtenderException {
        JSONObject root = new JSONObject();
        String moduleName = spec.moduleName;
        for (File header : spec.privateHeaders) {
            String filename = header.getName();
            String directory = header.getParent();
            Map<String, String> data = Map.of("suffix", filename, "prefix", String.format("%s/", directory));
            root.putAll(Map.of(filename, data));
            root.putAll(Map.of(String.format("%s/%s", moduleName, filename), data));
        }
        for (File header : spec.publicHeaders) {
            String filename = header.getName();
            Map<String, String> data = Map.of("suffix", filename, "prefix", String.format("%s/", moduleName));
            root.putAll(Map.of(filename, data));
        }
        String serialized = root.toJSONString();
        File jsonHeaderMap = new File(spec.headerMapFile.getParentFile(), String.format("%s.json", spec.name));
        Files.writeString(jsonHeaderMap.toPath(), serialized, StandardCharsets.UTF_8);
        ProcessUtils.execCommand(List.of(
            "hmap",
            "convert",
            jsonHeaderMap.toString(),
            spec.headerMapFile.toString()
        ), null, Map.of());
        return spec.headerMapFile;
    }

    public static File generateVFSOverlay(PodBuildSpec spec, Map<String, Collection<File>> vfsInfo) throws IOException {
        JSONArray rootArray = new JSONArray();
        
        for (Map.Entry<String, Collection<File>> entry : vfsInfo.entrySet()) {
            JSONArray content = new JSONArray();
            for (File contentPath : entry.getValue()) {
                content.add(Map.of(
                    "external-contents", contentPath.toString(),
                    "name", contentPath.getName(),
                    "type", "file"
                ));
            }
            rootArray.add(Map.of(
                "contents", content,
                "name", entry.getKey(),
                "type", "directory"
            ));
        }
        JSONObject resultDocument = new JSONObject();
        resultDocument.put("roots", rootArray);
        resultDocument.put("case-sensitive", "false"); // false as string, not boolean
        resultDocument.put("version", 0);

        Files.writeString(spec.vfsOverlay.toPath(), resultDocument.toJSONString(), StandardCharsets.UTF_8);

        // 2. Get all dependencies from spec
        for (PodBuildSpec depSpec : spec.dependantSpecs) {
            mergeVFSOverlays(spec.vfsOverlay, depSpec.vfsOverlay);
        }

        return spec.vfsOverlay;
    }

    public static File mergeVFSOverlays(File overlayA, File overlayB) {
        JSONParser parser = new JSONParser();
            try {
                JSONObject parsedOverlayA = (JSONObject)parser.parse(new FileReader(overlayA));
                JSONObject parsedOverlayB = (JSONObject)parser.parse(new FileReader(overlayB));

                JSONArray roots = (JSONArray)parsedOverlayA.get("roots");
                roots.addAll((JSONArray)parsedOverlayB.get("roots"));

                Files.writeString(overlayA.toPath(), parsedOverlayA.toJSONString(), StandardCharsets.UTF_8);
            } catch (IOException | ParseException e) {

            }
        return overlayA;
    }

    public static void generatedInfoPlistFromTemplate(File sourceTemplate, Map<String, String> data, File targetFile) throws IOException {
        StringSubstitutor substitutor = new StringSubstitutor(data);
        String template = Files.readString(sourceTemplate.toPath(), StandardCharsets.UTF_8);
        Files.writeString(targetFile.toPath(), substitutor.replace(template), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
    }
}

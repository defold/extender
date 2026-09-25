package com.defold.extender.utils;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import org.apache.commons.text.StringSubstitutor;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;

import com.defold.extender.process.JobFiles;
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

    /**
     * @param cwd the job directory; the sandboxed {@code hmap} runs there
     */
    public static File generateHeaderMap(PodBuildSpec spec, File cwd) throws IOException, ExtenderException {
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
        JobFiles.writeString(cwd, jsonHeaderMap, serialized, StandardCharsets.UTF_8);
        ProcessUtils.execCommand(List.of(
            "hmap",
            "convert",
            jsonHeaderMap.toString(),
            spec.headerMapFile.toString()
        ), cwd, Map.of());
        return spec.headerMapFile;
    }

    public static File generateVFSOverlay(File jobDir, PodBuildSpec spec, Map<String, Collection<File>> vfsInfo) throws IOException {
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

        JobFiles.writeString(jobDir, spec.vfsOverlay, resultDocument.toJSONString(), StandardCharsets.UTF_8);

        // 2. Get all dependencies from spec
        for (PodBuildSpec depSpec : spec.dependantSpecs) {
            mergeVFSOverlays(jobDir, spec.vfsOverlay, depSpec.vfsOverlay);
        }

        return spec.vfsOverlay;
    }

    public static File mergeVFSOverlays(File jobDir, File overlayA, File overlayB) throws IOException {
        JSONParser parser = new JSONParser();
        try (Reader readerA = new FileReader(overlayA); Reader readerB = new FileReader(overlayB)) {
            JSONObject parsedOverlayA = (JSONObject) parser.parse(readerA);
            JSONObject parsedOverlayB = (JSONObject) parser.parse(readerB);

            JSONArray roots = (JSONArray) parsedOverlayA.get("roots");
            roots.addAll((JSONArray) parsedOverlayB.get("roots"));

            JobFiles.writeString(jobDir, overlayA, parsedOverlayA.toJSONString(), StandardCharsets.UTF_8);
        } catch (ParseException e) {
            throw new IOException("Failed to merge VFS overlays " + overlayA + " and " + overlayB + ": " + e.getMessage(), e);
        }
        return overlayA;
    }

    public static void generatedInfoPlistFromTemplate(File jobDir, File sourceTemplate, Map<String, String> data, File targetFile) throws IOException {
        StringSubstitutor substitutor = new StringSubstitutor(data);
        String template = Files.readString(sourceTemplate.toPath(), StandardCharsets.UTF_8);
        JobFiles.writeString(jobDir, targetFile, substitutor.replace(template), StandardCharsets.UTF_8);
    }
}

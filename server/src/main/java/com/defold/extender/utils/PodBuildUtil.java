package com.defold.extender.utils;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.Reader;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.defold.extender.ExtenderException;
import com.defold.extender.process.ProcessUtils;
import com.defold.extender.services.cocoapods.PodBuildSpec;

public class PodBuildUtil {
    private static final Logger LOGGER = LoggerFactory.getLogger(PodBuildUtil.class);

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
            String directory = header.getAbsoluteFile().getParent();
            Map<String, String> data = Map.of("suffix", filename, "prefix", String.format("%s/", directory));
            root.putAll(Map.of(filename, data));
            root.putAll(Map.of(String.format("%s/%s", moduleName, filename), data));
        }
        for (File header : spec.publicHeaders) {
            String filename = header.getName();
            // Point directly at the header's real absolute directory rather than at
            // "<moduleName>/<filename>", which only resolves if a directory literally named
            // after the module exists relative to the compiler's working directory (true for
            // CocoaPods' own Headers/Public symlink layout, but not for headers that live
            // inside a vendored .xcframework - see collectSourceFilesFromSpec()). A relative
            // prefix here silently short-circuits clang's header map lookup: once the hmap
            // claims to know where the header is, clang uses that answer instead of falling
            // through to the correct -I directory later in the search path, so a broken
            // relative entry causes a "file not found" instead of a fallback search.
            String directory = header.getAbsoluteFile().getParent();
            Map<String, String> data = Map.of("suffix", filename, "prefix", String.format("%s/", directory));
            root.putAll(Map.of(filename, data));
        }
        LOGGER.debug("Header map for pod {} ({} private, {} public headers):\n{}",
            spec.name, spec.privateHeaders.size(), spec.publicHeaders.size(), root.toJSONString());
        validateHeaderMapEntries(spec.name, root);
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

    /**
     * Log a warning for every header map entry whose prefix+suffix doesn't resolve to a real
     * file. A header map entry that clang matches by filename but can't actually open is worse
     * than no entry at all: unlike a plain -I directory, a header map is a direct lookup table,
     * so once it claims to know where a header is, clang stops searching later -I directories
     * for a better answer and the compile fails with a confusing "file not found" even though
     * the real header exists elsewhere on the search path (see generateHeaderMap()'s public
     * header prefix comment for the incident this caught).
     * @param podName Name of the pod the header map belongs to, for the log message
     * @param headerMapJson The header map contents about to be written to disk
     */
    private static void validateHeaderMapEntries(String podName, JSONObject headerMapJson) {
        for (Object value : headerMapJson.values()) {
            @SuppressWarnings("unchecked")
            Map<String, String> entry = (Map<String, String>) value;
            String resolved = entry.get("prefix") + entry.get("suffix");
            if (!new File(resolved).isFile()) {
                LOGGER.warn("Header map for pod {} maps '{}' to '{}', which does not exist on disk",
                    podName, entry.get("suffix"), resolved);
            }
        }
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
            try(Reader readerA = new FileReader(overlayA); Reader readerB = new FileReader(overlayB)) {
                JSONObject parsedOverlayA = (JSONObject)parser.parse(readerA);
                JSONObject parsedOverlayB = (JSONObject)parser.parse(readerB);

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

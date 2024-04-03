package com.defold.manifestmergetool;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.FileNotFoundException;
import java.util.ArrayList;
import java.util.logging.Level;
import java.util.logging.Logger;

// https://cayenne.apache.org/docs/2.0/api/org/apache/cayenne/conf/FileConfiguration.html
import org.apache.commons.configuration2.ex.ConfigurationException;
import org.apache.commons.configuration2.io.FileLocator.FileLocatorBuilder;
import org.apache.commons.configuration2.io.FileLocatorUtils;
import org.apache.commons.configuration2.io.FileLocator;
import org.apache.commons.configuration2.plist.XMLPropertyListConfiguration;

public class PrivacyManifestMerger {
    private static Logger logger;

    PrivacyManifestMerger(Logger logger) {
        PrivacyManifestMerger.logger = logger;
    }

    private ArrayList<XMLPropertyListConfiguration> getArrayList(XMLPropertyListConfiguration config, String key) {
        if (!config.containsKey(key)) {
            config.addProperty(key, new ArrayList<XMLPropertyListConfiguration>());
        }
        @SuppressWarnings("unchecked")
        ArrayList<XMLPropertyListConfiguration> a = (ArrayList<XMLPropertyListConfiguration>)config.getProperty(key);
        return a;
    }

    private ArrayList<String> getStringArrayList(XMLPropertyListConfiguration config, String key) {
        if (!config.containsKey(key)) {
            config.addProperty(key, new ArrayList<String>());
        }
        @SuppressWarnings("unchecked")
        ArrayList<String> a = (ArrayList<String>)config.getProperty(key);
        return a;
    }

    /**
     * Search a list of 'dict' objects (XMLPropertyListConfiguration) for a dict
     * with a specific NSPrivacyAccessedAPIType.
     * @param list List of dicts
     * @param apiTypeToFind The api type to find
     * @return The dict or null
     */
    private XMLPropertyListConfiguration findAccessedAPI(ArrayList<XMLPropertyListConfiguration> list, String apiTypeToFind) {
        for (XMLPropertyListConfiguration api : list) {
            String apiType = api.getString("NSPrivacyAccessedAPIType");
            if (apiTypeToFind.equals(apiType)) {
                return api;
            }
        }
        return null;
    }

    /**
     * Merge NSPrivacyAccessedAPITypes. This function will do a deep merge
     * and ignore duplicates of the same access reason per api.
     * @param target Config to merge to
     * @param source Config to merge from
     */
    private void mergeAccessedAPITypes(XMLPropertyListConfiguration target, XMLPropertyListConfiguration source) {
        ArrayList<XMLPropertyListConfiguration> targetAccessedAPITypes = getArrayList(target, "NSPrivacyAccessedAPITypes");
        ArrayList<XMLPropertyListConfiguration> sourceAccessedAPITypes = getArrayList(source, "NSPrivacyAccessedAPITypes");

        for (XMLPropertyListConfiguration sourceApi : sourceAccessedAPITypes) {
            String sourceType = sourceApi.getString("NSPrivacyAccessedAPIType");

            // find if this api is already declared as accessed
            XMLPropertyListConfiguration targetApi = findAccessedAPI(targetAccessedAPITypes, sourceType);
            // if the api is not declared as accessed then add the api as-is
            if (targetApi == null) {
                targetAccessedAPITypes.add(sourceApi);
                continue;
            }

            // the api is declared as accessed already
            // only add reasons not already declared
            ArrayList<String> targetReasons = getStringArrayList(targetApi, "NSPrivacyAccessedAPITypeReasons");
            ArrayList<String> sourceReasons = getStringArrayList(sourceApi, "NSPrivacyAccessedAPITypeReasons");
            for (String reason : sourceReasons) {
                if (!targetReasons.contains(reason)) {
                    targetReasons.add(reason);
                }
            }
        }
    }

    private void mergeConfigs(XMLPropertyListConfiguration base, XMLPropertyListConfiguration lib) throws PlistMergeException {
        Object baseTracking = base.getProperty("NSPrivacyTracking");
        Object libTracking = lib.getProperty("NSPrivacyTracking");

        // merge privacy tracking domains
        ArrayList<String> baseTrackingDomains = getStringArrayList(base, "NSPrivacyTrackingDomains");
        ArrayList<String> libTrackingDomains = getStringArrayList(lib, "NSPrivacyTrackingDomains");
        baseTrackingDomains.addAll(libTrackingDomains);

        // merge collected data types
        ArrayList<XMLPropertyListConfiguration> baseCollectedDataTypes = getArrayList(base, "NSPrivacyCollectedDataTypes");
        ArrayList<XMLPropertyListConfiguration> libCollectedDataTypes = getArrayList(lib, "NSPrivacyCollectedDataTypes");
        baseCollectedDataTypes.addAll(libCollectedDataTypes);

        // merge accessed apis
        mergeAccessedAPITypes(base, lib);
    }

    private XMLPropertyListConfiguration loadConfig(File file) {
        try {
            XMLPropertyListConfiguration plist = new XMLPropertyListConfiguration();
            plist.read(new FileReader(file));
            return plist;
        } catch (ConfigurationException e) {
            throw new RuntimeException(String.format("Failed to parse plist '%s': %s", file.getAbsolutePath(), e.toString()));
        } catch (FileNotFoundException e) {
            throw new RuntimeException(String.format("File not found: %s", file.getAbsolutePath()));
        }
    }

    public void merge(File main, File[] libraries, File out) throws RuntimeException {

        XMLPropertyListConfiguration basePlist = loadConfig(main);
        // For error reporting/troubleshooting
        String paths = "\n" + main.getAbsolutePath();

        for (File library : libraries) {
            paths += "\n" + library.getAbsolutePath();
            XMLPropertyListConfiguration libraryPlist = loadConfig(library);
            try {
                mergeConfigs(basePlist, libraryPlist);
            } catch (PlistMergeException e) {
                throw new RuntimeException(String.format("Errors merging plists: %s:\n%s", paths, e.toString()));
            }
        }

        try {
            FileWriter writer = new FileWriter(out);
            FileLocatorBuilder builder = FileLocatorUtils.fileLocator();
            FileLocator locator = new FileLocator(builder);
            basePlist.initFileLocator(locator);
            basePlist.write(writer);
            writer.close();
        } catch (ConfigurationException e) {
            throw new RuntimeException("Failed to parse plist: " + e.toString());
        } catch (IOException e) {
            throw new RuntimeException("Failed to parse plist: " + e.toString());
        }
    }
}


package com.defold.manifestmergetool;

import java.io.*;
import java.lang.Boolean;
import java.util.Iterator;
import java.util.ArrayList;
import java.util.Arrays;
import java.math.BigInteger;
import java.math.BigDecimal;
import java.util.logging.Level;
import java.util.logging.Logger;

// https://cayenne.apache.org/docs/2.0/api/org/apache/cayenne/conf/FileConfiguration.html
import org.apache.commons.configuration2.ex.ConfigurationException;
import org.apache.commons.configuration2.io.FileLocator.FileLocatorBuilder;
import org.apache.commons.configuration2.io.FileLocatorUtils;
import org.apache.commons.configuration2.io.FileLocator;
import org.apache.commons.configuration2.plist.XMLPropertyListConfiguration;

class PlistMergeException extends Exception {
    PlistMergeException(String message) {
        super(message);
    }
}

public class InfoPlistMerger {
    private static Logger logger;

    InfoPlistMerger(Logger logger) {
        InfoPlistMerger.logger = logger;
    }

    // Merges the lib onto base, using the merging rules from https://developer.android.com/studio/build/manifest-merge.html
    public static void mergePlists(XMLPropertyListConfiguration base, XMLPropertyListConfiguration lib) throws PlistMergeException {
        @SuppressWarnings("unchecked")
        Iterator<String> it = lib.getKeys();
        while (it.hasNext()) {
            String key = it.next();

            Object baseValue = null;
            if (base.containsKey(key)) {
                baseValue = base.getProperty(key);
            }

            Object libValue = lib.getProperty(key);

            if (baseValue == null) {
                base.addProperty(key, libValue);
            } else {

                if (baseValue.getClass().equals(libValue.getClass())) {

                    if (!baseValue.getClass().equals(ArrayList.class)) {
                        boolean differ = false;
                        if (baseValue.getClass().equals(byte[].class)) {
                            if (!Arrays.equals((byte[])baseValue, (byte[])libValue)) {
                                differ = true;
                            }
                        } else if (!baseValue.equals(libValue)) {
                            differ = true;
                        }
                        if (differ) {
                            InfoPlistMerger.logger.log(Level.WARNING, String.format("Plist overriding value for key '%s': from '%s' to '%s'", key, baseValue.toString(), libValue.toString()));
                            base.addProperty(key, libValue);
                        }
                    }

                    if (baseValue.getClass().equals(String.class)) {
                    }
                    else if (baseValue.getClass().equals(Integer.class)) {
                    }
                    else if (baseValue.getClass().equals(BigInteger.class)) {
                    }
                    else if (baseValue.getClass().equals(BigDecimal.class)) {
                    }
                    else if (baseValue.getClass().equals(Boolean.class)) {
                    }
                    else if (baseValue.getClass().equals(byte[].class)) {
                    }
                    else if (baseValue.getClass().equals(ArrayList.class)) {
                        @SuppressWarnings("unchecked")
                        ArrayList<String> baseArray = (ArrayList<String>)baseValue;
                        @SuppressWarnings("unchecked")
                        ArrayList<String> libArray = (ArrayList<String>)libValue;
                        baseArray.addAll((ArrayList<String>)libArray);
                    }
                    else {
                        throw new PlistMergeException(String.format("Plist contains unknown type for key '%s': %s", key, baseValue.getClass().getName()));
                    }

                } else { // if the value classes differ, then raise an error!
                    throw new PlistMergeException(String.format("Plist contains conflicting types for key '%s': %s vs %s", key, baseValue.getClass().getName(), libValue.getClass().getName()));
                }
            }
        }
    }

    private static XMLPropertyListConfiguration loadPlist(File file) {
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

    public static void merge(File main, File[] libraries, File out) throws RuntimeException {
        XMLPropertyListConfiguration basePlist = loadPlist(main);

        // For error reporting/troubleshooting
        String paths = "\n" + main.getAbsolutePath();

        for (File library : libraries) {
            paths += "\n" + library.getAbsolutePath();
            XMLPropertyListConfiguration libraryPlist = loadPlist(library);
            try {
                mergePlists(basePlist, libraryPlist);
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


package com.defold.manifestmergetool;

import java.io.*;
import java.lang.Boolean;
import java.util.Iterator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Map;
import java.util.HashMap;
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

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.DocumentBuilder;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.w3c.dom.NamedNodeMap;


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
    private static void mergePlists(XMLPropertyListConfiguration base, XMLPropertyListConfiguration lib, Map<String, String> mergeMarkers) throws PlistMergeException {


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
                            String mergeMarker = mergeMarkers.get(key);
                            if (mergeMarker == null || !mergeMarker.equals("keep")) {
                                InfoPlistMerger.logger.log(Level.WARNING, String.format("Plist overriding value for key '%s': from '%s' to '%s'", key, baseValue.toString(), libValue.toString()));
                                base.addProperty(key, libValue);
                            }
                            else {
                                InfoPlistMerger.logger.log(Level.INFO, String.format("Plist keeping base '%s' value for key '%s'", baseValue.toString(), key));
                            }
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
                        for (String val : libArray) {
                            if (!baseArray.contains(val)) {
                                baseArray.add(val);
                            }
                        }
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


    // utility class to iterate a NodeList in search of <key> nodes
    private static class NodeListKeyParser {
        private NodeList nodes;
        private int index = 0;

        public NodeListKeyParser(NodeList nodes) {
            this.nodes = nodes;
        }

        public Node next() {
            while (index < nodes.getLength()) {
                Node n = nodes.item(index++);
                if (n.getNodeType() != Node.TEXT_NODE && n.getNodeName().equals("key")) {
                    return n;
                }
            }
            return null;
        }

        public boolean hasNext() {
            int previous_index = index;
            Node n = next();
            index = previous_index;
            return n != null;
        }
    }


    private static Map<String, String> parseMergeNodeMarkers(File file) {
        Map<String, String> markers = new HashMap<>();
        try {
            // parse the file and get the first <dict> tag
            DocumentBuilder builder = DocumentBuilderFactory.newInstance().newDocumentBuilder();
            Node dict = builder.parse(file).getElementsByTagName("dict").item(0);
            if (dict == null) {
                return markers;
            }

            // get all child nodes and search for <key merge="rule"> tags
            NodeListKeyParser parser = new NodeListKeyParser(dict.getChildNodes());
            while (parser.hasNext()) {
                Node key = parser.next();
                NamedNodeMap attributes = key.getAttributes();
                Node mergeMarker = attributes.getNamedItem("merge");
                if (mergeMarker != null) {
                    InfoPlistMerger.logger.log(Level.INFO, String.format("Found merge node marker for key '%s' with rule '%s'", key.getTextContent(), mergeMarker.getNodeValue()));
                    markers.put(key.getTextContent(), mergeMarker.getNodeValue());
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse merge node markers: " + e.toString());
        }
        return markers;
    }

    public static void merge(File main, File[] libraries, File out) throws RuntimeException {

        Map<String, String> markers = parseMergeNodeMarkers(main);

        XMLPropertyListConfiguration basePlist = loadPlist(main);

        // For error reporting/troubleshooting
        String paths = "\n" + main.getAbsolutePath();

        for (File library : libraries) {
            paths += "\n" + library.getAbsolutePath();
            XMLPropertyListConfiguration libraryPlist = loadPlist(library);
            try {
                mergePlists(basePlist, libraryPlist, markers);
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

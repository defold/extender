
package com.defold.manifestmergetool;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.StringWriter;
import java.io.IOException;
import java.io.FileNotFoundException;
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
import org.apache.commons.configuration2.io.FileHandler;
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

    public InfoPlistMerger(Logger logger) {
        InfoPlistMerger.logger = logger;
    }

    // Merges the lib onto base, using the merging rules from https://developer.android.com/studio/build/manifest-merge.html
    private void mergePlists(XMLPropertyListConfiguration base, XMLPropertyListConfiguration lib, Map<String, MergePolicy> baseMergeMarkers, Map<String, MergePolicy> libMergeMarkers) throws PlistMergeException {
        @SuppressWarnings("unchecked")
        Iterator<String> it = lib.getKeys();
        while (it.hasNext()) {
            String key = it.next();

            Object libValue = lib.getProperty(key);
            Object baseValue = base.getProperty(key);

            // set the value if it doesn't exist on the base
            if (baseValue == null) {
                base.setProperty(key, libValue);
                continue;
            }

            // it is not possible to merge if the values are of different types
            if (!baseValue.getClass().equals(libValue.getClass())) {
                throw new PlistMergeException(String.format("Plist contains conflicting types for key '%s': %s vs %s", key, baseValue.getClass().getName(), libValue.getClass().getName()));
            }

            MergePolicy baseMergePolicy = baseMergeMarkers.getOrDefault(key, MergePolicy.MERGE);
            MergePolicy libMergePolicy = libMergeMarkers.getOrDefault(key, MergePolicy.MERGE);
            // check that base and lib doesn't hav conflicting merge policies
            if (baseMergePolicy == MergePolicy.KEEP && libMergePolicy == MergePolicy.REPLACE) {
                throw new PlistMergeException(String.format("Plist contains conflicting merge policies for key '%s': %s vs %s", key, baseMergePolicy, libMergePolicy));
            }

            // value is a dictionary
            if (baseValue.getClass().equals(XMLPropertyListConfiguration.class)) {
                XMLPropertyListConfiguration baseValueDict = (XMLPropertyListConfiguration)baseValue;
                XMLPropertyListConfiguration libValueDict = (XMLPropertyListConfiguration)libValue;
                if (baseMergePolicy == MergePolicy.MERGE) {
                    mergePlists(baseValueDict, libValueDict, baseMergeMarkers, libMergeMarkers);
                }
                else if (libMergePolicy == MergePolicy.REPLACE) {
                    base.setProperty(key, libValue);
                }
            }
            // value is an array
            else if (baseValue.getClass().equals(ArrayList.class)) {
                @SuppressWarnings("unchecked")
                ArrayList<Object> baseArray = (ArrayList<Object>)baseValue;
                @SuppressWarnings("unchecked")
                ArrayList<Object> libArray = (ArrayList<Object>)libValue;

                // if the base policy is to keep the values in the array then we
                // add the new values (ignoring duplicates) instead of trying to
                // merge them
                if (baseMergePolicy == MergePolicy.KEEP) {
                    for (Object libArrayValue : libArray) {
                        if (!baseArray.contains(libArrayValue)) {
                            baseArray.add(libArrayValue);
                        }
                    }
                }
                // if the lib policy is to replace the values in the array then
                // we do a simple replace of the array
                else if (libMergePolicy == MergePolicy.REPLACE) {
                    base.setProperty(key, libValue);
                }
                // if the base policy is to merge the values in the array then
                // we go through the values in the array and try to either merge
                // them with existing values or add them as new values (ignoring
                // duplicates)
                else if (baseMergePolicy == MergePolicy.MERGE) {
                    for (Object libArrayValue : libArray) {
                        // the value is a dictionary, try to find a dictionary in
                        // the base array to merge with or add it to the base array
                        if (libArrayValue.getClass().equals(XMLPropertyListConfiguration.class)) {
                            boolean merged = false;
                            for (Object baseArrayValue : baseArray) {
                                if (baseArrayValue.getClass().equals(XMLPropertyListConfiguration.class)) {
                                    InfoPlistMerger.logger.log(Level.WARNING, String.format("Plist merging dictionary into existing dictionary for key '%s'", key));
                                    XMLPropertyListConfiguration baseArrayValueDict = (XMLPropertyListConfiguration)baseArrayValue;
                                    XMLPropertyListConfiguration libArrayValueDict = (XMLPropertyListConfiguration)libArrayValue;
                                    mergePlists(baseArrayValueDict, libArrayValueDict, baseMergeMarkers, libMergeMarkers);
                                    merged = true;
                                    break;
                                }
                            }
                            // add the value if there was no dictionary to merge it with
                            if (!merged) {
                                InfoPlistMerger.logger.log(Level.WARNING, String.format("Plist adding array dictionary to array for key '%s'", key));
                                baseArray.add(libArrayValue);
                            }
                        }
                        else {
                            // don't add duplicates to the array
                            if (!baseArray.contains(libArrayValue)) {
                                InfoPlistMerger.logger.log(Level.WARNING, String.format("Plist merging arrays by adding values for key '%s'", key));
                                baseArray.add(libArrayValue);
                            }
                        }
                    }
                }
            }
            // value is a primitive value of some kind (string, nummber, data etc)
            else {
                if (libMergePolicy == MergePolicy.REPLACE) {
                    InfoPlistMerger.logger.log(Level.WARNING, String.format("Plist replacing value for key '%s': from '%s' to '%s'", key, baseValue.toString(), libValue.toString()));
                    base.setProperty(key, libValue);
                }
                else if (baseMergePolicy == MergePolicy.MERGE) {
                    InfoPlistMerger.logger.log(Level.WARNING, String.format("Plist adding value for key '%s': '%s'", key, libValue.toString()));
                    base.addProperty(key, libValue);
                }
            }
        }
    }


    private XMLPropertyListConfiguration loadPlist(File file) {
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


    private Map<String, MergePolicy> parseMergeNodeMarkers(File file) {
        Map<String, MergePolicy> markers = new HashMap<>();
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
                    MergePolicy policy = MergePolicy.fromString(mergeMarker.getNodeValue());
                    markers.put(key.getTextContent(), policy);
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse merge node markers: " + e.toString());
        }
        return markers;
    }

    public void merge(File main, File[] libraries, File out) throws RuntimeException {

        Map<String, MergePolicy> baseMarkers = parseMergeNodeMarkers(main);

        XMLPropertyListConfiguration basePlist = loadPlist(main);

        // For error reporting/troubleshooting
        String paths = "\n" + main.getAbsolutePath();

        for (File library : libraries) {
            paths += "\n" + library.getAbsolutePath();
            XMLPropertyListConfiguration libraryPlist = loadPlist(library);
            Map<String, MergePolicy> libraryMarkers = parseMergeNodeMarkers(library);
            try {
                mergePlists(basePlist, libraryPlist, baseMarkers, libraryMarkers);
            } catch (PlistMergeException e) {
                throw new RuntimeException(String.format("Errors merging plists: %s:\n%s", paths, e.toString()));
            }
        }


        try {
            FileHandler handler = new FileHandler(basePlist);
            FileWriter writer = new FileWriter(out);
            handler.save(writer);
        } catch (ConfigurationException | IOException e) {
            throw new RuntimeException("Failed to write plist: " + e.toString());
        }
    }
}

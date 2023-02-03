package com.defold.extender;

import org.junit.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.*;
import java.util.regex.Pattern;

import static org.junit.Assert.*;

public class ExtensionManifestValidatorTest {

    @Test
    public void testWhitelistCheckPatterns() {
        List<Pattern> patterns = new ArrayList<>();
        patterns.add(Pattern.compile("^(apa)$"));
        patterns.add(Pattern.compile("^(bepa)$"));

        List<String> values = new ArrayList<>();

        values.clear();
        values.add("bepafant");
        assertEquals("bepafant", ExtensionManifestValidator.whitelistCheck(patterns, values));

        values.clear();
        values.add("apa");
        values.add("bepa");
        assertEquals(null, ExtensionManifestValidator.whitelistCheck(patterns, values));

        values.clear();
        values.add("apa");
        values.add("bepa");
        values.add("foo");
        assertEquals("foo", ExtensionManifestValidator.whitelistCheck(patterns, values));
    }

    @Test
    public void testWhitelistCheckContext() throws ExtenderException, IOException {

        String[] allowedLibsTemplatesArray = new String[]{"dtrace_dyld", "AccountPolicyTranslation", "alias.A", "alias", "apr-1.0", "apr-1", "aprutil-1.0", "aprutil-1", "archive.2", "archive", "ATCommandStudioDynamic", "auditd.0", "auditd", "auto", "AVFAudio", "blas", "BSDPClient.A", "BSDPClient", "bsm.0", "bsm", "bz2.1.0.5", "bz2.1.0", "bz2", "c\\+\\+.1", "c\\+\\+", "c\\+\\+abi", "c", "cblas", "charset.1.0.0", "charset.1", "charset", "ChineseTokenizer", "clapack", "cmph", "com_err", "compression", "CoreStorage", "CRFSuite", "CRFSuite0.12", "crypto.0.9.7", "crypto.0.9.8", "crypto.35", "crypto", "csfde", "cups.2", "cups", "cupscgi.1", "cupscgi", "cupsimage.2", "cupsimage", "cupsmime.1", "cupsmime", "cupsppdc.1", "cupsppdc", "curl.3", "curl.4", "curl", "curses", "dbm", "des425", "DHCPServer.A", "DHCPServer", "DiagnosticMessagesClient", "dl", "dns_services", "dtrace", "ecpg.6.5", "ecpg.6", "ecpg", "ecpg_compat.3.5", "ecpg_compat.3", "ecpg_compat", "edit.2", "edit.3.0", "edit.3", "edit", "energytrace", "expat.1", "expat", "exslt.0", "exslt", "extension", "f77lapack", "ffi", "form.5.4", "form", "Fosl_dynamic", "gcc_s.1", "gcc_s.10.4", "gcc_s.10.5", "germantok", "gmalloc", "gssapi_krb5", "heimdal-asn1", "hunspell-1.2.0.0.0", "hunspell-1.2.0", "hunspell-1.2", "IASAuthReboot", "IASUnifiedProgress", "iconv.2.4.0", "iconv.2", "iconv", "icucore.A", "icucore", "info", "iodbc.2.1.18", "iodbc.2", "iodbc", "iodbcinst.2.1.18", "iodbcinst.2", "iodbcinst", "ipconfig", "ipsec.A", "ipsec", "k5crypto", "krb4", "krb5", "krb524", "krb5support", "ktrace", "langid", "lapack", "lber", "ldap", "ldap_r", "lzma.5", "lzma", "m", "marisa", "Match.1", "Match", "mecab.1.0.0", "mecab", "mecabra", "menu.5.4", "menu", "mx.A", "mx", "ncurses.5.4", "ncurses.5", "ncurses", "netsnmp.15.1.2", "netsnmp.15", "netsnmp.25", "netsnmp.5.2.1", "netsnmp.5", "netsnmp", "netsnmpagent.25", "netsnmpagent", "netsnmphelpers.25", "netsnmphelpers", "netsnmpmibs.25", "netsnmpmibs", "netsnmptrapd.25", "netsnmptrapd", "network", "objc.A", "objc", "odfde", "odmodule", "OpenScriptingUtil", "pam.1", "pam.2", "pam", "panel.5.4", "panel", "pcap.A", "pcap", "pcre.0", "pcre", "pcreposix.0", "pcreposix", "pgtypes.3.4", "pgtypes.3", "pgtypes", "pmenergy", "pmsample", "poll", "pq.5.6", "pq.5", "pq", "prequelite", "proc", "pthread", "python", "python2.6", "python2.7", "QMIParserDynamic", "quit", "readline", "resolv.9", "resolv", "rpcsvc", "ruby.2.0.0", "ruby.2.0", "ruby", "sandbox.1", "sandbox", "sasl2.2.0.1", "sasl2.2.0.15", "sasl2.2.0.21", "sasl2.2.0.22", "sasl2.2", "sasl2", "ScreenReader", "spindump", "sqlite3.0", "sqlite3", "ssl.0.9.7", "ssl.0.9.8", "ssl.35", "ssl", "stdc\\+\\+.6.0.9", "stdc\\+\\+.6", "stdc\\+\\+", "sysmon", "System.B", "System.B_debug", "System", "System_debug", "systemstats", "tcl", "tcl8.5", "TelephonyUtilDynamic", "termcap", "ThaiTokenizer", "tidy.A", "tidy", "tk", "tk8.5", "tls.6", "tls", "UniversalAccess", "util", "util1.0", "xar.1", "xar", "xcselect", "xml2.2", "xml2", "Xplugin.1", "Xplugin", "XSEvent", "xslt.1", "xslt", "z.1.1.3", "z.1.2.5", "z.1", "z", "ssh-keychain", "dispatch", "system_pthread", "cache", "commonCrypto", "compiler_rt", "copyfile", "corecrypto", "dispatch", "dyld", "keymgr", "kxld", "launch", "macho", "mathCommon.A", "mathCommon", "quarantine", "removefile", "system_asl", "system_blocks", "system_c", "system_configuration", "system_coreservices", "system_coretls", "system_dnssd", "system_info", "system_kernel", "system_m", "system_malloc", "system_network", "system_networkextension", "system_notify", "system_platform", "system_pthread", "system_sandbox", "system_secinit", "system_trace", "unc", "unwind", "xpc", "AGL", "AVFoundation", "AVKit", "Accelerate", "Accounts", "AddressBook", "AppKit", "AppKitScripting", "AppleScriptKit", "AppleScriptObjC", "ApplicationServices", "AudioToolbox", "AudioUnit", "AudioVideoBridging", "Automator", "CFNetwork", "CalendarStore", "Carbon", "CloudKit", "Cocoa", "Collaboration", "Contacts", "ContactsUI", "CoreAudio", "CoreAudioKit", "CoreBluetooth", "CoreData", "CoreFoundation", "CoreGraphics", "CoreImage", "CoreLocation", "CoreMIDI", "CoreMIDIServer", "CoreMedia", "CoreMediaIO", "CoreServices", "CoreTelephony", "CoreText", "CoreVideo", "CoreWLAN", "CryptoTokenKit", "DVComponentGlue", "DVDPlayback", "DirectoryService", "DiscRecording", "DiscRecordingUI", "DiskArbitration", "DrawSprocket", "EventKit", "ExceptionHandling", "FWAUserLib", "FinderSync", "ForceFeedback", "Foundation", "GLKit", "GLUT", "GSS", "GameController", "GameKit", "GameplayKit", "Hypervisor", "ICADevices", "IMServicePlugIn", "IOBluetooth", "IOBluetoothUI", "IOKit", "IOSurface", "ImageCaptureCore", "ImageIO", "InputMethodKit", "InstallerPlugins", "InstantMessage", "JavaFrameEmbedding", "JavaScriptCore", "JavaVM", "Kerberos", "Kernel", "LDAP", "LatentSemanticMapping", "LocalAuthentication", "MapKit", "MediaAccessibility", "MediaLibrary", "MediaToolbox", "Message", "Metal", "MetalKit", "ModelIO", "MultipeerConnectivity", "NetFS", "NetworkExtension", "NotificationCenter", "OSAKit", "OpenAL", "OpenCL", "OpenDirectory", "OpenGL", "PCSC", "Photos", "PhotosUI", "PreferencePanes", "PubSub", "Python", "QTKit", "Quartz", "QuartzCore", "QuickLook", "QuickTime", "Ruby", "SceneKit", "ScreenSaver", "Scripting", "ScriptingBridge", "Security", "SecurityFoundation", "SecurityInterface", "ServiceManagement", "Social", "SpriteKit", "StoreKit", "SyncServices", "System", "SystemConfiguration", "TWAIN", "Tcl", "Tk", "VideoDecodeAcceleration", "VideoToolbox", "WebKit", "vecLib", "vmnet"};
        List<String> allowedLibsTemplates = Arrays.asList(allowedLibsTemplatesArray);

        String[] allowedFlagsTemplatesArray = new String[]{"-ObjC", "-ObjC++", "-Wa,{{comma_separated_arg}}", "-W{{warning}}", "-ansi", "--ansi", "-std-default={{arg}}", "-stdlib=(libstdc\\+\\+|libc\\+\\+)", "-w", "-std=(c89|c99|c\\+\\+0x|c\\+\\+11|c\\+\\+14|c\\+\\+17|c\\+\\+20)", "-Wp,{{comma_separated_arg}}", "-W{{warning}}", "--extra-warnings", "--warn-{{warning}}", "--warn-={{warning}}", "-ferror-limit={{number}}", "-O([0-4]?|fast|s|z)"};
        List<String> allowedFlagsTemplates = Arrays.asList(allowedFlagsTemplatesArray);

        String[] allowedSymbolsArray = new String[]{"dtrace_dyld", "AccountPolicyTranslation", "alias.A", "alias", "apr-1.0", "apr-1", "aprutil-1.0", "aprutil-1", "archive.2", "archive", "ATCommandStudioDynamic", "auditd.0", "auditd", "auto", "AVFAudio", "blas", "BSDPClient.A", "BSDPClient", "bsm.0", "bsm", "bz2.1.0.5", "bz2.1.0", "bz2", "c\\+\\+.1", "c\\+\\+", "c\\+\\+abi", "c", "cblas", "charset.1.0.0", "charset.1", "charset", "ChineseTokenizer", "clapack", "cmph", "com_err", "compression", "CoreStorage", "CRFSuite", "CRFSuite0.12", "crypto.0.9.7", "crypto.0.9.8", "crypto.35", "crypto", "csfde", "cups.2", "cups", "cupscgi.1", "cupscgi", "cupsimage.2", "cupsimage", "cupsmime.1", "cupsmime", "cupsppdc.1", "cupsppdc", "curl.3", "curl.4", "curl", "curses", "dbm", "des425", "DHCPServer.A", "DHCPServer", "DiagnosticMessagesClient", "dl", "dns_services", "dtrace", "ecpg.6.5", "ecpg.6", "ecpg", "ecpg_compat.3.5", "ecpg_compat.3", "ecpg_compat", "edit.2", "edit.3.0", "edit.3", "edit", "energytrace", "expat.1", "expat", "exslt.0", "exslt", "extension", "f77lapack", "ffi", "form.5.4", "form", "Fosl_dynamic", "gcc_s.1", "gcc_s.10.4", "gcc_s.10.5", "germantok", "gmalloc", "gssapi_krb5", "heimdal-asn1", "hunspell-1.2.0.0.0", "hunspell-1.2.0", "hunspell-1.2", "IASAuthReboot", "IASUnifiedProgress", "iconv.2.4.0", "iconv.2", "iconv", "icucore.A", "icucore", "info", "iodbc.2.1.18", "iodbc.2", "iodbc", "iodbcinst.2.1.18", "iodbcinst.2", "iodbcinst", "ipconfig", "ipsec.A", "ipsec", "k5crypto", "krb4", "krb5", "krb524", "krb5support", "ktrace", "langid", "lapack", "lber", "ldap", "ldap_r", "lzma.5", "lzma", "m", "marisa", "Match.1", "Match", "mecab.1.0.0", "mecab", "mecabra", "menu.5.4", "menu", "mx.A", "mx", "ncurses.5.4", "ncurses.5", "ncurses", "netsnmp.15.1.2", "netsnmp.15", "netsnmp.25", "netsnmp.5.2.1", "netsnmp.5", "netsnmp", "netsnmpagent.25", "netsnmpagent", "netsnmphelpers.25", "netsnmphelpers", "netsnmpmibs.25", "netsnmpmibs", "netsnmptrapd.25", "netsnmptrapd", "network", "objc.A", "objc", "odfde", "odmodule", "OpenScriptingUtil", "pam.1", "pam.2", "pam", "panel.5.4", "panel", "pcap.A", "pcap", "pcre.0", "pcre", "pcreposix.0", "pcreposix", "pgtypes.3.4", "pgtypes.3", "pgtypes", "pmenergy", "pmsample", "poll", "pq.5.6", "pq.5", "pq", "prequelite", "proc", "pthread", "python", "python2.6", "python2.7", "QMIParserDynamic", "quit", "readline", "resolv.9", "resolv", "rpcsvc", "ruby.2.0.0", "ruby.2.0", "ruby", "sandbox.1", "sandbox", "sasl2.2.0.1", "sasl2.2.0.15", "sasl2.2.0.21", "sasl2.2.0.22", "sasl2.2", "sasl2", "ScreenReader", "spindump", "sqlite3.0", "sqlite3", "ssl.0.9.7", "ssl.0.9.8", "ssl.35", "ssl", "stdc\\+\\+.6.0.9", "stdc\\+\\+.6", "stdc\\+\\+", "sysmon", "System.B", "System.B_debug", "System", "System_debug", "systemstats", "tcl", "tcl8.5", "TelephonyUtilDynamic", "termcap", "ThaiTokenizer", "tidy.A", "tidy", "tk", "tk8.5", "tls.6", "tls", "UniversalAccess", "util", "util1.0", "xar.1", "xar", "xcselect", "xml2.2", "xml2", "Xplugin.1", "Xplugin", "XSEvent", "xslt.1", "xslt", "z.1.1.3", "z.1.2.5", "z.1", "z", "ssh-keychain", "dispatch", "system_pthread", "cache", "commonCrypto", "compiler_rt", "copyfile", "corecrypto", "dispatch", "dyld", "keymgr", "kxld", "launch", "macho", "mathCommon.A", "mathCommon", "quarantine", "removefile", "system_asl", "system_blocks", "system_c", "system_configuration", "system_coreservices", "system_coretls", "system_dnssd", "system_info", "system_kernel", "system_m", "system_malloc", "system_network", "system_networkextension", "system_notify", "system_platform", "system_pthread", "system_sandbox", "system_secinit", "system_trace", "unc", "unwind", "xpc", "AGL", "AVFoundation", "AVKit", "Accelerate", "Accounts", "AddressBook", "AppKit", "AppKitScripting", "AppleScriptKit", "AppleScriptObjC", "ApplicationServices", "AudioToolbox", "AudioUnit", "AudioVideoBridging", "Automator", "CFNetwork", "CalendarStore", "Carbon", "CloudKit", "Cocoa", "Collaboration", "Contacts", "ContactsUI", "CoreAudio", "CoreAudioKit", "CoreBluetooth", "CoreData", "CoreFoundation", "CoreGraphics", "CoreImage", "CoreLocation", "CoreMIDI", "CoreMIDIServer", "CoreMedia", "CoreMediaIO", "CoreServices", "CoreTelephony", "CoreText", "CoreVideo", "CoreWLAN", "CryptoTokenKit", "DVComponentGlue", "DVDPlayback", "DirectoryService", "DiscRecording", "DiscRecordingUI", "DiskArbitration", "DrawSprocket", "EventKit", "ExceptionHandling", "FWAUserLib", "FinderSync", "ForceFeedback", "Foundation", "GLKit", "GLUT", "GSS", "GameController", "GameKit", "GameplayKit", "Hypervisor", "ICADevices", "IMServicePlugIn", "IOBluetooth", "IOBluetoothUI", "IOKit", "IOSurface", "ImageCaptureCore", "ImageIO", "InputMethodKit", "InstallerPlugins", "InstantMessage", "JavaFrameEmbedding", "JavaScriptCore", "JavaVM", "Kerberos", "Kernel", "LDAP", "LatentSemanticMapping", "LocalAuthentication", "MapKit", "MediaAccessibility", "MediaLibrary", "MediaToolbox", "Message", "Metal", "MetalKit", "ModelIO", "MultipeerConnectivity", "NetFS", "NetworkExtension", "NotificationCenter", "OSAKit", "OpenAL", "OpenCL", "OpenDirectory", "OpenGL", "PCSC", "Photos", "PhotosUI", "PreferencePanes", "PubSub", "Python", "QTKit", "Quartz", "QuartzCore", "QuickLook", "QuickTime", "Ruby", "SceneKit", "ScreenSaver", "Scripting", "ScriptingBridge", "Security", "SecurityFoundation", "SecurityInterface", "ServiceManagement", "Social", "SpriteKit", "StoreKit", "SyncServices", "System", "SystemConfiguration", "TWAIN", "Tcl", "Tk", "VideoDecodeAcceleration", "VideoToolbox", "WebKit", "vecLib", "vmnet"};
        List<String> allowedSymbols = Arrays.asList(allowedSymbolsArray);

        InputStream configFileInputStream = Files.newInputStream(new File("test-data/sdk/a/defoldsdk/extender/build.yml").toPath());
        assertNotNull(new Yaml().loadAs(configFileInputStream, Configuration.class));

        ExtensionManifestValidator validator = new ExtensionManifestValidator(new WhitelistConfig(), allowedFlagsTemplates, allowedSymbols);

        List<String> stringValues = new ArrayList<>();
        Map<String, Object> context = new HashMap<>();

        // LIBS
        File extensionFolder = new File("ext-folder");

        stringValues.clear();
        context.clear();
        stringValues.add("AVFAudio");
        stringValues.add("QuickTime");
        context.put("libs", stringValues);

        validator.validate("test_extension", extensionFolder, context);


        stringValues.clear();
        context.clear();
        stringValues.add("c++");
        context.put("libs", stringValues);

        validator.validate("test_extension", extensionFolder, context);


        stringValues.clear();
        context.clear();
        stringValues.add("apa");
        stringValues.add("./foo");
        context.put("libs", stringValues);

        boolean thrown;
        try {
            thrown = false;
            validator.validate("test_extension", extensionFolder, context);
        } catch (ExtenderException e) {
            thrown = true;
        }
        assertTrue(thrown);


        // FLAGS

        stringValues.clear();
        context.clear();
        stringValues.add("-O");
        stringValues.add("-Weverything");
        context.put("flags", stringValues);

        validator.validate("test_extension", extensionFolder, context);


        stringValues.clear();
        context.clear();
        stringValues.add("-std=c++0x");
        stringValues.add("-std=c++11");
        stringValues.add("-std=c++14");
        stringValues.add("-std=c++17");
        stringValues.add("-std=c++20");
        context.put("flags", stringValues);

        validator.validate("test_extension", extensionFolder, context);


        stringValues.clear();
        context.clear();
        stringValues.add("-O");
        stringValues.add("-Weverything; rm -rf");
        context.put("flags", stringValues);

        try {
            thrown = false;
            validator.validate("test_extension", extensionFolder, context);
        } catch (ExtenderException e) {
            if (e.toString().contains("rm -rf")) {
                thrown = true;
            } else {
                throw e;
            }
        }
        assertTrue(thrown);
    }

    @Test
    public void testAllowedLibs() throws ExtenderException {
        Map<String, Object> context = new HashMap<>();

        List<String> empty = new ArrayList<>();
        ExtensionManifestValidator validator = new ExtensionManifestValidator(new WhitelistConfig(), empty, empty);

        List<String> l = new ArrayList<>();
        l.add("Crypt32");
        l.add("c++");
        l.add("z");
        context.put("libs", l);

        File extensionFolder = new File("ext-folder");
        boolean thrown = false;
        try {
            validator.validate("testExtension", extensionFolder, context);
        } catch (ExtenderException e) {
            thrown = true;
            System.out.println(e.toString());
        }

        assertFalse(thrown);

        l.add("../libfoobar.a");
        context.put("libs", l);
        try {
            validator.validate("testExtension", extensionFolder, context);
        } catch (ExtenderException e) {
            System.out.println(e.toString());
            if (e.toString().contains("Invalid")) { // expected error
                thrown = true;
            } else {
                throw e;
            }
        }
        assertTrue(thrown);
    }
}

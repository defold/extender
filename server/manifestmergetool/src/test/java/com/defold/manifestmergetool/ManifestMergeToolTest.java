package com.defold.manifestmergetool;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.apache.commons.io.FileUtils;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

import com.defold.manifestmergetool.ManifestMergeTool.Platform;

@RunWith(Parameterized.class)
public class ManifestMergeToolTest {

    private static final String LIB_MANIFEST_PATH = "builtins/manifests/ios/InfoLib.plist";
    private static final String MAIN_MANIFEST_PATH = "builtins/manifests/ios/Info.plist";
    private static final String MERGED_MANIFEST_PATH = "builtins/manifests/ios/InfoMerged.plist";

    private String contentRoot;
    private Platform platform;
    private File root;
    private File main;
    private File target;
    List<File> libraries;

    private File createFile(String root, String name, String content) throws IOException {
        File file = new File(root, name);
        file.deleteOnExit();
        FileUtils.copyInputStreamToFile(new ByteArrayInputStream(content.getBytes()), file);
        return file;
    }

    protected void createDefaultFiles() throws IOException {
        // These are the base manifest files
        String iosManifest = ""
                + "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\" \"http://www.apple.com/DTDs/PropertyList-1.0.dtd\">\n"
                + "<plist version=\"1.0\">\n"
                + "<dict>\n"
                + "        <key>NSAppTransportSecurity</key>\n"
                + "        <dict>\n"
                + "            <key>NSExceptionDomains</key>\n"
                + "            <dict>\n"
                + "                <key>foobar.net</key>\n"
                + "                <dict>\n"
                + "                    <key>testproperty</key>\n"
                + "                    <true/>\n"
                + "                </dict>\n"
                + "            </dict>\n"
                + "        </dict>\n"
                + "        <key>INT</key>\n"
                + "        <integer>8</integer>\n"
                + "        <key>REAL</key>\n"
                + "        <real>8.0</real>\n"
                + "        <key>BASE64</key>\n"
                + "        <data>SEVMTE8gV09STEQ=</data>\n"
                + "</dict>\n"
                + "</plist>\n";

        createFile(contentRoot, MAIN_MANIFEST_PATH, iosManifest);

        String androidManifest = ""
                + "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
                + "<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\""
                + "        package=\"com.defold.testmerge\""
                + "        android:versionCode=\"14\""
                + "        android:versionName=\"1.0\""
                + "        android:installLocation=\"auto\">"
                + "    <uses-feature android:required=\"true\" android:glEsVersion=\"0x00020000\" />"
                + "    <uses-sdk android:minSdkVersion=\"9\" android:targetSdkVersion=\"26\" />"
                + "    <application android:label=\"Test Project\" android:hasCode=\"true\">"
                + "    </application>"
                + "    <uses-permission android:name=\"android.permission.VIBRATE\" />"
                + "    <uses-permission android:name=\"android.permission.CAMERA\" />"
                + "</manifest>";

        createFile(contentRoot, "builtins/manifests/android/AndroidManifest.xml", androidManifest);

        String html5Manifest = ""
                + "<!DOCTYPE html>"
                + "<html>"
                + "<body>"
                + " <script id='engine-loader' type='text/javascript' src=\"dmloader.js\"></script>"
                + " <script id='engine-setup' type='text/javascript'>"
                + " function load_engine() {"
                + "     var engineJS = document.createElement('script');"
                + "     engineJS.type = 'text/javascript';"
                + "     engineJS.src = '{{exe-name}}_wasm.js';"
                + "     document.head.appendChild(engineJS);"
                + " }"
                + " </script>"
                + " <script id='engine-start' type='text/javascript'>"
                + "     load_engine();"
                + " </script>"
                + "</body>"
                + "</html>";

        createFile(contentRoot, "builtins/manifests/web/engine_template.html", html5Manifest);

        if (platform == Platform.ANDROID) {
            this.target = new File(contentRoot, "builtins/manifests/android/AndroidManifestMerged.xml");
            this.main = new File(contentRoot, "builtins/manifests/android/AndroidManifest.xml");
            this.libraries = new ArrayList<File>();
            this.libraries.add(new File(contentRoot, "builtins/manifests/android/AndroidManifestLib.xml"));
        } else if (platform == Platform.IOS) {
            this.target = new File(contentRoot, MERGED_MANIFEST_PATH);
            this.main = new File(contentRoot, MAIN_MANIFEST_PATH);
            this.libraries = new ArrayList<File>();
            this.libraries.add(new File(contentRoot, LIB_MANIFEST_PATH));
        } else if (platform == Platform.WEB) {
            this.target = new File(contentRoot, "builtins/manifests/web/engine_template_merged.html");
            this.main = new File(contentRoot, "builtins/manifests/web/engine_template.html");
            this.libraries = new ArrayList<File>();
            this.libraries.add(new File(contentRoot, "builtins/manifests/web/engine_template_lib.html"));
        }
    }

    @Parameters
    public static Collection<Platform[]> data() {
        Platform[][] data = new Platform[][] { {Platform.ANDROID}, {Platform.IOS}, {Platform.WEB}};
        return Arrays.asList(data);
    }

    public ManifestMergeToolTest(Platform platform) throws IOException {
        this.platform = platform;
        root = Files.createTempDirectory("defoldmergetest").toFile();
        //root.deleteOnExit();
        contentRoot = root.getAbsolutePath();
    }

    private String readFile(File file) throws IOException {
        List<String> lines = Files.readAllLines(file.toPath());

        String s = "";
        for (String line : lines) {
            s += line + "\n";
        }
        return s;
    }

    @Test
    public void testMergeAndroid() throws IOException {
        if (platform != Platform.ANDROID) {
            return;
        }

        createDefaultFiles();

        String manifest = ""
                + "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
                + "<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\" xmlns:tools=\"http://schemas.android.com/tools\" package=\"com.defold.testmerge\">"
                + "    <uses-feature android:required=\"true\" android:glEsVersion=\"0x00030000\" />"
                + "    <application>"
                + "        <meta-data android:name=\"com.facebook.sdk.ApplicationName\""
                + "            android:value=\"Test Project\" />"
                + "        <activity android:name=\"com.facebook.FacebookActivity\""
                + "          android:theme=\"@android:style/Theme.Translucent.NoTitleBar\""
                + "          android:configChanges=\"keyboard|keyboardHidden|screenLayout|screenSize|orientation\""
                + "          android:label=\"Test Project\" />"
                + "    </application>"
                + "    <uses-permission android:name=\"android.permission.CAMERA\" tools:node=\"remove\"/>"
                + "</manifest>";
        createFile(contentRoot, "builtins/manifests/android/AndroidManifestLib.xml", manifest);

        String expected = ""
                + "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
                + "<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\"\n"
                + "    package=\"com.defold.testmerge\"\n"
                + "    android:installLocation=\"auto\"\n"
                + "    android:versionCode=\"14\"\n"
                + "    android:versionName=\"1.0\" >\n"
                + "\n"
                + "    <uses-sdk\n"
                + "        android:minSdkVersion=\"9\"\n"
                + "        android:targetSdkVersion=\"26\" />\n"
                + "\n"
                + "    <uses-permission android:name=\"android.permission.VIBRATE\" />\n"
                + "\n"
                + "    <uses-feature\n"
                + "        android:glEsVersion=\"0x00030000\"\n"
                + "        android:required=\"true\" />\n"
                + "\n"
                + "    <uses-permission android:name=\"android.permission.WRITE_EXTERNAL_STORAGE\" />\n"
                + "    <uses-permission android:name=\"android.permission.READ_PHONE_STATE\" />\n"
                + "    <uses-permission android:name=\"android.permission.READ_EXTERNAL_STORAGE\" />\n"
                + "\n"
                + "    <application\n"
                + "        android:hasCode=\"true\"\n"
                + "        android:label=\"Test Project\" >\n"
                + "        <meta-data\n"
                + "            android:name=\"com.facebook.sdk.ApplicationName\"\n"
                + "            android:value=\"Test Project\" />\n"
                + "\n"
                + "        <activity\n"
                + "            android:name=\"com.facebook.FacebookActivity\"\n"
                + "            android:configChanges=\"keyboard|keyboardHidden|screenLayout|screenSize|orientation\"\n"
                + "            android:label=\"Test Project\"\n"
                + "            android:theme=\"@android:style/Theme.Translucent.NoTitleBar\" />\n"
                + "    </application>\n"
                + "\n"
                + "</manifest>\n";


        createFile(contentRoot, "builtins/manifests/android/AndroidManifestExpected.xml", expected);

        ManifestMergeTool.merge(ManifestMergeTool.Platform.ANDROID, this.main, this.target, this.libraries);

        String merged = readFile(target);
        assertEquals(expected, merged);
    }

    @Test
    public void testMergeAndroidFailSdkMinVersion() throws IOException {
        if (platform != Platform.ANDROID) {
            return;
        }

        createDefaultFiles();

        String manifest = ""
                + "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
                + "<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\" package=\"com.defold.testmerge\">"
                + "    <uses-sdk android:minSdkVersion=\"14\" android:targetSdkVersion=\"28\" />"
                + "</manifest>";
        createFile(contentRoot, "builtins/manifests/android/AndroidManifestLib.xml", manifest);

        try {
            ManifestMergeTool.merge(ManifestMergeTool.Platform.ANDROID, this.main, this.target, this.libraries);
            assertTrue(false); // We shouldn't get here!
        } catch (RuntimeException e) {
            assertTrue(true);
        }
    }


    @Test
    public void testMergeIOS() throws IOException {
        if (platform != Platform.IOS) {
            return;
        }

        createDefaultFiles();

        String libManifest = ""
                + "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\" \"http://www.apple.com/DTDs/PropertyList-1.0.dtd\">\n"
                + "<plist version=\"1.0\">\n"
                + "<dict>\n"
                + "    <key>NSAppTransportSecurity</key>\n"
                + "    <dict>\n"
                + "        <key>NSExceptionDomains</key>\n"
                + "        <dict>\n"
                + "            <key>facebook.com</key>\n"
                + "            <dict>\n"
                + "                <key>NSIncludesSubdomains</key>\n"
                + "                <true/>\n"
                + "                <key>NSThirdPartyExceptionRequiresForwardSecrecy</key>\n"
                + "                <false/>\n"
                + "            </dict>\n"
                + "        </dict>\n"
                + "    </dict>\n"
                + "    <key>INT</key>\n"
                + "    <integer>42</integer>\n"
                + "</dict>\n"
                + "</plist>\n";

        createFile(contentRoot, LIB_MANIFEST_PATH, libManifest);

        String expected = ""
                + "<?xml version=\"1.0\"?>\n"
                + "<!DOCTYPE plist SYSTEM \"file://localhost/System/Library/DTDs/PropertyList.dtd\">\n"
                + "<plist version=\"1.0\">\n"
                + "    <dict>\n"
                + "        <key>NSAppTransportSecurity</key>\n"
                + "        <dict>\n"
                + "            <key>NSExceptionDomains</key>\n"
                + "            <dict>\n"
                + "                <key>foobar.net</key>\n"
                + "                <dict>\n"
                + "                    <key>testproperty</key>\n"
                + "                    <true/>\n"
                + "                </dict>\n"
                + "\n"
                + "                <key>facebook.com</key>\n"
                + "                <dict>\n"
                + "                    <key>NSIncludesSubdomains</key>\n"
                + "                    <true/>\n"
                + "\n"
                + "                    <key>NSThirdPartyExceptionRequiresForwardSecrecy</key>\n"
                + "                    <false/>\n"
                + "                </dict>\n"
                + "            </dict>\n"
                + "        </dict>\n"
                + "\n"
                + "        <key>INT</key>\n"
                + "        <integer>8</integer>\n"
                + "\n"
                + "        <key>REAL</key>\n"
                + "        <real>8.0</real>\n"
                + "\n"
                + "        <key>BASE64</key>\n"
                + "        <data>SEVMTE8gV09STEQ=</data>\n"
                + "\n"
                + "        <key>INT</key>\n"
                + "        <integer>42</integer>\n"
                + "    </dict>\n"
                + "</plist>\n";

        createFile(contentRoot, "builtins/manifests/ios/InfoExpected.plist", expected);

        ManifestMergeTool.merge(ManifestMergeTool.Platform.IOS, this.main, this.target, this.libraries);

        String merged = readFile(this.target);
        assertEquals(expected, merged);
    }



    @Test
    public void testMergeIOSMergeMarkers() throws IOException {
        if (platform != Platform.IOS) {
            return;
        }

        createDefaultFiles();

        String builtinsManifest = ""
                + "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\" \"http://www.apple.com/DTDs/PropertyList-1.0.dtd\" [ <!ATTLIST key merge (keep) #IMPLIED> ]>  \n"
                + "<plist version=\"1.0\">\n"
                + "<dict>\n"
                + "    <key merge='keep'>BASE64</key>\n"
                + "    <data>SEVMTE8gV09STEQ=</data>\n"
                + "    <key>INT</key>\n"
                + "    <integer>8</integer>\n"
                + "    <key>STRING</key>\n"
                + "    <string>Hello world</string>\n"
                + "</dict>\n"
                + "</plist>\n";
        createFile(contentRoot, MAIN_MANIFEST_PATH, builtinsManifest);

        String libManifest = ""
                + "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\" \"http://www.apple.com/DTDs/PropertyList-1.0.dtd\">\n"
                + "<plist version=\"1.0\">\n"
                + "<dict>\n"
                + "    <key>BASE64</key>\n"
                + "    <data>foobar</data>\n"
                + "    <key>INT</key>\n"
                + "    <integer>42</integer>\n"
                + "    <key merge='replace'>STRING</key>\n"
                + "    <string>Foobar</string>\n"
                + "</dict>\n"
                + "</plist>\n";
        createFile(contentRoot, LIB_MANIFEST_PATH, libManifest);

        String expected = ""
                + "<?xml version=\"1.0\"?>\n"
                + "<!DOCTYPE plist SYSTEM \"file://localhost/System/Library/DTDs/PropertyList.dtd\">\n"
                + "<plist version=\"1.0\">\n"
                + "    <dict>\n"
                + "        <key>BASE64</key>\n"
                + "        <data>SEVMTE8gV09STEQ=</data>\n"
                + "\n"
                + "        <key>INT</key>\n"
                + "        <integer>8</integer>\n"
                + "\n"
                + "        <key>STRING</key>\n"
                + "        <string>Foobar</string>\n"
                + "\n"
                + "        <key>INT</key>\n"
                + "        <integer>42</integer>\n"
                + "    </dict>\n"
                + "</plist>\n";

        createFile(contentRoot, "builtins/manifests/ios/InfoExpected.plist", expected);

        ManifestMergeTool.merge(ManifestMergeTool.Platform.IOS, this.main, this.target, this.libraries);

        String merged = readFile(this.target);
        assertEquals(expected, merged);
    }

    @Test
    public void testMergeIOSArrayDuplication() throws IOException {
        if (platform != Platform.IOS) {
            return;
        }

        createDefaultFiles();

        String builtinsManifest = ""
                + "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\" \"http://www.apple.com/DTDs/PropertyList-1.0.dtd\" [ <!ATTLIST key merge (keep) #IMPLIED> ]>  \n"
                + "<plist version=\"1.0\">\n"
                + "<dict>\n"
                + "    <key>CFBundleSupportedPlatforms</key>\n"
                + "    <array>\n"
                + "        <string>iPhoneOS</string>\n"
                + "        <string>iPhoneSimulator</string>\n"
                + "    </array>\n"
                + "</dict>\n"
                + "</plist>\n";
        createFile(contentRoot, MAIN_MANIFEST_PATH, builtinsManifest);

        String libManifest = ""
                + "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\" \"http://www.apple.com/DTDs/PropertyList-1.0.dtd\">\n"
                + "<plist version=\"1.0\">\n"
                + "<dict>\n"
                + "    <key>CFBundleSupportedPlatforms</key>\n"
                + "    <array>\n"
                + "        <string>iPhoneOS</string>\n"
                + "    </array>\n"
                + "</dict>\n"
                + "</plist>\n";
        createFile(contentRoot, LIB_MANIFEST_PATH, libManifest);

        String expected = ""
                + "<?xml version=\"1.0\"?>\n"
                + "<!DOCTYPE plist SYSTEM \"file://localhost/System/Library/DTDs/PropertyList.dtd\">\n"
                + "<plist version=\"1.0\">\n"
                + "    <dict>\n"
                + "        <key>CFBundleSupportedPlatforms</key>\n"
                + "        <array>\n"
                + "            <string>iPhoneOS</string>\n"
                + "            <string>iPhoneSimulator</string>\n"
                + "        </array>\n"
                + "    </dict>\n"
                + "</plist>\n";

        createFile(contentRoot, "builtins/manifests/ios/InfoExpected.plist", expected);

        ManifestMergeTool.merge(ManifestMergeTool.Platform.IOS, this.main, this.target, this.libraries);

        String merged = readFile(this.target);
        assertEquals(expected, merged);
    }

    @Test
    public void testMergeIOSMixedTypes() throws IOException {
        if (platform != Platform.IOS) {
            return;
        }

        createDefaultFiles();

        String builtinsManifest = ""
                + "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\" \"http://www.apple.com/DTDs/PropertyList-1.0.dtd\" [ <!ATTLIST key merge (keep) #IMPLIED> ]>  \n"
                + "<plist version=\"1.0\">\n"
                + "<dict>\n"
                + "    <key>INT</key>\n"
                + "    <integer>8</integer>\n"
                + "</dict>\n"
                + "</plist>\n";
        createFile(contentRoot, MAIN_MANIFEST_PATH, builtinsManifest);

        String libManifest = ""
                + "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\" \"http://www.apple.com/DTDs/PropertyList-1.0.dtd\">\n"
                + "<plist version=\"1.0\">\n"
                + "<dict>\n"
                + "    <key>INT</key>\n"
                + "    <data>42</data>\n"
                + "</dict>\n"
                + "</plist>\n";

        createFile(contentRoot, LIB_MANIFEST_PATH, libManifest);

        try {
            ManifestMergeTool.merge(ManifestMergeTool.Platform.IOS, this.main, this.target, this.libraries);
            assertTrue(false); // We shouldn't get here!
        } catch (RuntimeException e) {
            assertTrue(true);
        }
    }

    @Test
    public void testMergeNestedDictionaries() throws IOException {
        if (platform != Platform.IOS) {
            return;
        }
        createDefaultFiles();

        String builtinsManifest = ""
                + "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\" \"http://www.apple.com/DTDs/PropertyList-1.0.dtd\" [ <!ATTLIST key merge (keep) #IMPLIED> ]>  \n"
                + "<plist version=\"1.0\">\n"
                + "<dict>\n"
                + "<key>UISupportedInterfaceOrientations~ipad</key>\n"
                + "<array>\n"
                + "        <string>UIInterfaceOrientationPortrait</string>\n"
                + "        <string>UIInterfaceOrientationPortraitUpsideDown</string>\n"
                + "</array>\n"
                + "<key>CFBundleURLTypes</key>\n"
                + "<array>\n"
                + "        <dict>\n"
                + "                <key>CFBundleTypeRole</key>\n"
                + "                <string>Editor</string>\n"
                + "                <key>CFBundleURLName</key>\n"
                + "                <string>REVERSED_CLIENT_ID</string>\n"
                + "                <key>CFBundleURLSchemes</key>\n"
                + "                <array>\n"
                + "                        <string>com.fjdkdknvhgjfd</string>\n"
                + "                </array>\n"
                + "        </dict>\n"
                + "</array>\n"
                + "<key>LSApplicationQueriesSchemes</key>\n"
                + "<array>\n"
                + "</array>\n"
                + "<key>UILaunchStoryboardName</key>\n"
                + "<string>LaunchScreen</string>\n"
                + "<key>UIRequiresFullScreen</key>\n"
                + "<true/>\n"
                + "</dict>\n"
                + "</plist>\n";
        createFile(contentRoot, MAIN_MANIFEST_PATH, builtinsManifest);

        String libManifest = ""
                + "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\" \"http://www.apple.com/DTDs/PropertyList-1.0.dtd\">\n"
                + "<plist version=\"1.0\">\n"
                + "<dict>\n"
                + "<key>LSApplicationQueriesSchemes</key>\n"
                + "<array>\n"
                + "    <string>fbapi</string>\n"
                + "    <string>fbapi20130214</string>\n"
                + "    <string>fbapi20130410</string>\n"
                + "    <string>fbapi20130702</string>\n"
                + "    <string>fbapi20131010</string>\n"
                + "    <string>fbapi20131219</string>\n"
                + "    <string>fbapi20140410</string>\n"
                + "    <string>fbapi20140116</string>\n"
                + "    <string>fbapi20150313</string>\n"
                + "    <string>fbapi20150629</string>\n"
                + "    <string>fbauth</string>\n"
                + "    <string>fbauth2</string>\n"
                + "    <string>fb-messenger-api20140430</string>\n"
                + "    <string>fb-messenger-platform-20150128</string>\n"
                + "    <string>fb-messenger-platform-20150218</string>\n"
                + "    <string>fb-messenger-platform-20150305</string>\n"
                + "</array>\n"
                + "<key>CFBundleURLTypes</key>\n"
                + "<array>\n"
                + "    <dict>\n"
                + "        <key>CFBundleURLSchemes</key>\n"
                + "        <array>\n"
                + "            <string>fb7886788786688</string>\n"
                + "        </array>\n"
                + "    </dict>\n"
                + "</array>\n"
                + "</dict>\n"
                + "</plist>\n";
        createFile(contentRoot, LIB_MANIFEST_PATH, libManifest);

        String expected = ""
                + "<?xml version=\"1.0\"?>\n"
                + "<!DOCTYPE plist SYSTEM \"file://localhost/System/Library/DTDs/PropertyList.dtd\">\n"
                + "<plist version=\"1.0\">\n"
                + "    <dict>\n"
                + "        <key>UISupportedInterfaceOrientations~ipad</key>\n"
                + "        <array>\n"
                + "            <string>UIInterfaceOrientationPortrait</string>\n"
                + "            <string>UIInterfaceOrientationPortraitUpsideDown</string>\n"
                + "        </array>\n"
                + "\n"
                + "        <key>CFBundleURLTypes</key>\n"
                + "        <array>\n"
                + "            <dict>\n"
                + "                <key>CFBundleTypeRole</key>\n"
                + "                <string>Editor</string>\n"
                + "\n"
                + "                <key>CFBundleURLName</key>\n"
                + "                <string>REVERSED_CLIENT_ID</string>\n"
                + "\n"
                + "                <key>CFBundleURLSchemes</key>\n"
                + "                <array>\n"
                + "                    <string>com.fjdkdknvhgjfd</string>\n"
                + "                    <string>fb7886788786688</string>\n"
                + "                </array>\n"
                + "            </dict>\n"
                + "        </array>\n"
                + "\n"
                + "        <key>LSApplicationQueriesSchemes</key>\n"
                + "        <array>\n"
                + "            <string>fbapi</string>\n"
                + "            <string>fbapi20130214</string>\n"
                + "            <string>fbapi20130410</string>\n"
                + "            <string>fbapi20130702</string>\n"
                + "            <string>fbapi20131010</string>\n"
                + "            <string>fbapi20131219</string>\n"
                + "            <string>fbapi20140410</string>\n"
                + "            <string>fbapi20140116</string>\n"
                + "            <string>fbapi20150313</string>\n"
                + "            <string>fbapi20150629</string>\n"
                + "            <string>fbauth</string>\n"
                + "            <string>fbauth2</string>\n"
                + "            <string>fb-messenger-api20140430</string>\n"
                + "            <string>fb-messenger-platform-20150128</string>\n"
                + "            <string>fb-messenger-platform-20150218</string>\n"
                + "            <string>fb-messenger-platform-20150305</string>\n"
                + "        </array>\n"
                + "\n"
                + "        <key>UILaunchStoryboardName</key>\n"
                + "        <string>LaunchScreen</string>\n"
                + "\n"
                + "        <key>UIRequiresFullScreen</key>\n"
                + "        <true/>\n"
                + "    </dict>\n"
                + "</plist>\n";

        createFile(contentRoot, "builtins/manifests/ios/InfoExpected.plist", expected);

        ManifestMergeTool.merge(ManifestMergeTool.Platform.IOS, this.main, this.target, this.libraries);

        String merged = readFile(this.target);
        assertEquals(expected, merged);
    }

    @Test
    public void testMergeArrays() throws IOException {
        if (platform != Platform.IOS && platform != Platform.OSX) {
            return;
        }
        createDefaultFiles();

        String builtinsManifest = ""
            + "<?xml version=\"1.0\"?>\n"
            + "<!DOCTYPE plist SYSTEM \"file://localhost/System/Library/DTDs/PropertyList.dtd\">\n"
            + "<plist version=\"1.0\">\n"
            + "    <dict>\n"
            + "        <key>SKAdNetworkItems</key>\n"
            + "        <array>\n"
            + "            <dict>\n"
            + "                <key>SKAdNetworkIdentifier</key>\n"
            + "                <string>ECPZ2SRF59.skadnetwork</string>\n"
            + "            </dict>\n"
            + "            <dict>\n"
            + "                <key>SKAdNetworkIdentifier</key>\n"
            + "                <string>7UG5ZH24HU.skadnetwork</string>\n"
            + "            </dict>\n"
            + "        </array>\n"
            + "    </dict>\n"
            + "</plist>\n";
        createFile(contentRoot, MAIN_MANIFEST_PATH, builtinsManifest);

        String libManifest = ""
            + "<?xml version=\"1.0\"?>\n"
            + "<!DOCTYPE plist SYSTEM \"file://localhost/System/Library/DTDs/PropertyList.dtd\">\n"
            + "<plist version=\"1.0\">\n"
            + "    <dict>\n"
            + "        <key merge='keep'>SKAdNetworkItems</key>\n"
            + "        <array>\n"
            + "            <dict>\n"
            + "                <key>SKAdNetworkIdentifier</key>\n"
            + "                <string>su67r6k2v3.skadnetwork</string>\n"
            + "            </dict>\n"
            + "            <dict>\n"
            + "                <key>SKAdNetworkIdentifier</key>\n"
            + "                <string>7ug5zh24hu.skadnetwork</string>\n"
            + "            </dict>\n"
            + "        </array>\n"
            + "    </dict>\n"
            + "</plist>\n";
        createFile(contentRoot, LIB_MANIFEST_PATH, libManifest);

        String expected = ""
            + "<?xml version=\"1.0\"?>\n"
            + "<!DOCTYPE plist SYSTEM \"file://localhost/System/Library/DTDs/PropertyList.dtd\">\n"
            + "<plist version=\"1.0\">\n"
            + "    <dict>\n"
            + "        <key>SKAdNetworkItems</key>\n"
            + "        <array>\n"
            + "            <dict>\n"
            + "                <key>SKAdNetworkIdentifier</key>\n"
            + "                <string>ECPZ2SRF59.skadnetwork</string>\n"
            + "            </dict>\n"
            + "            <dict>\n"
            + "                <key>SKAdNetworkIdentifier</key>\n"
            + "                <string>7UG5ZH24HU.skadnetwork</string>\n"
            + "            </dict>\n"
            + "            <dict>\n"
            + "                <key>SKAdNetworkIdentifier</key>\n"
            + "                <string>su67r6k2v3.skadnetwork</string>\n"
            + "            </dict>\n"
            + "            <dict>\n"
            + "                <key>SKAdNetworkIdentifier</key>\n"
            + "                <string>7ug5zh24hu.skadnetwork</string>\n"
            + "            </dict>\n"
            + "        </array>\n"
            + "    </dict>\n"
            + "</plist>\n";
        createFile(contentRoot, "builtins/manifests/ios/InfoExpected.plist", expected);

        ManifestMergeTool.merge(ManifestMergeTool.Platform.IOS, this.main, this.target, this.libraries);

        String merged = readFile(this.target);
        assertEquals(expected, merged);
    }

    @Test
    public void testMergePrivacyManifest() throws IOException {
        if (platform != Platform.IOS && platform != Platform.OSX) {
            return;
        }

        String main = ""
            + "<?xml version=\"1.0\"?>\n"
            + "<!DOCTYPE plist SYSTEM \"file://localhost/System/Library/DTDs/PropertyList.dtd\">\n"
            + "<plist version=\"1.0\">\n"
            + "    <dict>\n"
            + "        <key>NSPrivacyTracking</key>\n"
            + "        <false/>\n"
            + "\n"
            + "        <key>NSPrivacyCollectedDataTypes</key>\n"
            + "        <array>\n"
            + "          <dict>\n"
            + "              <key>FooKey</key>\n"
            + "              <string>FooString</string>\n"
            + "          </dict>\n"
            + "        </array>\n"
            + "\n"
            + "        <key>NSPrivacyAccessedAPITypes</key>\n"
            + "        <array>\n"
            + "          <dict>\n"
            + "              <key>NSPrivacyAccessedAPIType</key>\n"
            + "              <string>Foo</string>\n"
            + "\n"
            + "              <key>NSPrivacyAccessedAPITypeReasons</key>\n"
            + "              <array>\n"
            + "                  <string>A</string>\n"
            + "              </array>\n"
            + "          </dict>\n"
            + "        </array>\n"
            + "    </dict>\n"
            + "</plist>\n";

        String lib = ""
            + "<?xml version=\"1.0\"?>\n"
            + "<!DOCTYPE plist SYSTEM \"file://localhost/System/Library/DTDs/PropertyList.dtd\">\n"
            + "<plist version=\"1.0\">\n"
            + "    <dict>\n"
            + "        <key>NSPrivacyTracking</key>\n"
            + "        <false/>\n"
            + "\n"
            + "        <key>NSPrivacyTrackingDomains</key>\n"
            + "        <array>\n"
            + "            <string>foo.com</string>\n"
            + "        </array>\n"
            + "\n"
            + "        <key>NSPrivacyCollectedDataTypes</key>\n"
            + "        <array>\n"
            + "            <dict>\n"
            + "                <key>BarKey</key>\n"
            + "                <string>BarString</string>\n"
            + "            </dict>\n"
            + "        </array>\n"
            + "\n"
            + "        <key>NSPrivacyAccessedAPITypes</key>\n"
            + "        <array>\n"
            + "            <dict>\n"
            + "                <key>NSPrivacyAccessedAPIType</key>\n"
            + "                <string>Foo</string>\n"
            + "\n"
            + "                <key>NSPrivacyAccessedAPITypeReasons</key>\n"
            + "                <array>\n"
            + "                    <string>B</string>\n"
            + "                </array>\n"
            + "            </dict>\n"
            + "            <dict>\n"
            + "                <key>NSPrivacyAccessedAPIType</key>\n"
            + "                <string>Bar</string>\n"
            + "\n"
            + "                <key>NSPrivacyAccessedAPITypeReasons</key>\n"
            + "                <array>\n"
            + "                    <string>A</string>\n"
            + "                </array>\n"
            + "            </dict>\n"
            + "        </array>\n"
            + "    </dict>\n"
            + "</plist>\n";

        String expected = ""
            + "<?xml version=\"1.0\"?>\n"
            + "<!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\" \"http://www.apple.com/DTDs/PropertyList-1.0.dtd\">\n"
            + "<plist version=\"1.0\">\n"
            + "    <dict>\n"
            + "        <key>NSPrivacyTracking</key>\n"
            + "        <false/>\n"
            + "\n"
            + "        <key>NSPrivacyCollectedDataTypes</key>\n"
            + "        <array>\n"
            + "            <dict>\n"
            + "                <key>FooKey</key>\n"
            + "                <string>FooString</string>\n"
            + "            </dict>\n"
            + "            <dict>\n"
            + "                <key>BarKey</key>\n"
            + "                <string>BarString</string>\n"
            + "            </dict>\n"
            + "        </array>\n"
            + "\n"
            + "        <key>NSPrivacyAccessedAPITypes</key>\n"
            + "        <array>\n"
            + "            <dict>\n"
            + "                <key>NSPrivacyAccessedAPIType</key>\n"
            + "                <string>Foo</string>\n"
            + "\n"
            + "                <key>NSPrivacyAccessedAPITypeReasons</key>\n"
            + "                <array>\n"
            + "                    <string>A</string>\n"
            + "                    <string>B</string>\n"
            + "                </array>\n"
            + "            </dict>\n"
            + "            <dict>\n"
            + "                <key>NSPrivacyAccessedAPIType</key>\n"
            + "                <string>Bar</string>\n"
            + "\n"
            + "                <key>NSPrivacyAccessedAPITypeReasons</key>\n"
            + "                <array>\n"
            + "                    <string>A</string>\n"
            + "                </array>\n"
            + "            </dict>\n"
            + "        </array>\n"
            + "\n"
            + "        <key>NSPrivacyTrackingDomains</key>\n"
            + "        <array>\n"
            + "            <string>foo.com</string>\n"
            + "        </array>\n"
            + "    </dict>\n"
            + "</plist>\n";

        File mainFile = createFile(contentRoot, "builtins/manifests/ios/PrivacyInfo.xcprivacy", main);
        File libFile = createFile(contentRoot, "builtins/manifests/ios/PrivacyInfoLib.xcprivacy", lib);
        File targetFile = new File(contentRoot, "builtins/manifests/ios/PrivacyInfoMerged.plist");

        List<File> libraries = new ArrayList<>();
        libraries.add(libFile);
        ManifestMergeTool.merge(ManifestMergeTool.Platform.IOS, mainFile, targetFile, libraries);

        String merged = readFile(targetFile);
        assertEquals(expected, merged);
    }

    @Test
    public void testMergeHTML5() throws IOException {
        if (platform != Platform.WEB) {
            return;
        }

        createDefaultFiles();

        String libManifest = ""
                + "<html>"
                + "<body>"
                + " <script id='engine-loader' type='text/javascript' src=\"mydmloader.js\"></script>"
                + " <script id='engine-start' type='text/javascript' merge='keep'>"
                + "     my_load_engine();"
                + " </script>"
                + "</body>"
                + "</html>";

        createFile(contentRoot, "builtins/manifests/web/engine_template_lib.html", libManifest);

        String expected = ""
            + "<!doctype html>\n"
            + "<html>\n"
            + " <head></head>\n"
            + " <body> \n"
            + "  <script id=\"engine-loader\" type=\"text/javascript\" src=\"mydmloader.js\"></script> \n"
            + "  <script id=\"engine-setup\" type=\"text/javascript\"> function load_engine() {     var engineJS = document.createElement('script');     engineJS.type = 'text/javascript';     engineJS.src = '{{exe-name}}_wasm.js';     document.head.appendChild(engineJS); } </script> \n"
            + "  <script id=\"engine-start\" type=\"text/javascript\" merge=\"keep\">     my_load_engine(); </script>\n"
            + " </body>\n"
            + "</html>\n";

        createFile(contentRoot, "builtins/manifests/web/engine_template_expected.html", expected);

        ManifestMergeTool.merge(ManifestMergeTool.Platform.WEB, this.main, this.target, this.libraries);

        String merged = readFile(target);

        assertEquals(expected, merged);
    }
}

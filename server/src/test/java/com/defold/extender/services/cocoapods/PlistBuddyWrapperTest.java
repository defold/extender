package com.defold.extender.services.cocoapods;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import com.defold.extender.ExtenderException;

@EnabledOnOs({ OS.MAC })
public class PlistBuddyWrapperTest {
	
	@Test
	public void testCreateEmptyPlist() throws ExtenderException, IOException {
		File tmpDir = Files.createTempDirectory("empty-plist").toFile();
		tmpDir.deleteOnExit();
		File targetPlist = new File(tmpDir, "Info.plist");
		assertFalse(targetPlist.exists());
		PlistBuddyWrapper.createEmptyPlist(targetPlist);
		assertTrue(targetPlist.exists());
	}

	@Test
	public void testPlistKeys() throws IOException {
		File tmpDir = Files.createTempDirectory("plist-keys").toFile();
		tmpDir.deleteOnExit();
		File targetPlist = new File(tmpDir, "Info.plist");
		assertDoesNotThrow(() -> PlistBuddyWrapper.addStringProperty(targetPlist, "RegularProperty", "1"));
		assertDoesNotThrow(() -> PlistBuddyWrapper.addStringProperty(targetPlist, "Spaced property", "2"));
		assertDoesNotThrow(() -> PlistBuddyWrapper.addStringArrayProperty(targetPlist, "spaced string array", new String[]{}));
		assertDoesNotThrow(() -> PlistBuddyWrapper.addIntegerArrayProperty(targetPlist, "spaced integer array", new int[]{}));

		String expected = """
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
	<key>RegularProperty</key>
	<string>1</string>
	<key>Spaced property</key>
	<string>2</string>
	<key>spaced integer array</key>
	<array/>
	<key>spaced string array</key>
	<array/>
</dict>
</plist>  
""";
		assertEquals(expected, Files.readString(targetPlist.toPath()));
	}

	@Test
	public void testAddStringProperty() throws IOException, ExtenderException {
		File tmpDir = Files.createTempDirectory("string-prop").toFile();
		tmpDir.deleteOnExit();
		File targetPlist = new File(tmpDir, "Info.plist");

		assertDoesNotThrow(() -> PlistBuddyWrapper.addStringProperty(targetPlist, "StringPropertTest", "Test value with '\\ "));
		String expected = """
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
	<key>StringPropertTest</key>
	<string>Test value with '\\ </string>
</dict>
</plist>        
""";
		assertEquals(expected, Files.readString(targetPlist.toPath()));
	}

	@Test
	public void testAddStringArray() throws IOException {
		File tmpDir = Files.createTempDirectory("string-array-prop").toFile();
		tmpDir.deleteOnExit();
		File targetPlist = new File(tmpDir, "Info.plist");
		assertDoesNotThrow(() -> PlistBuddyWrapper.addStringArrayProperty(targetPlist, "TestStringArrayProp", new String[] { "string1", "222", "content43", "string with spaces", "string with \\ " }));

		String expected = """
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
	<key>TestStringArrayProp</key>
	<array>
		<string>string1</string>
		<string>222</string>
		<string>content43</string>
		<string>string with spaces</string>
		<string>string with \\ </string>
	</array>
</dict>
</plist>			
""";
		assertEquals(expected, Files.readString(targetPlist.toPath()));
	}

	@Test
	public void testAddIntegerArray() throws IOException {
		File tmpDir = Files.createTempDirectory("int-array-prop").toFile();
		tmpDir.deleteOnExit();
		File targetPlist = new File(tmpDir, "Info.plist");
		assertDoesNotThrow(() -> PlistBuddyWrapper.addIntegerArrayProperty(targetPlist, "TestIntegerArrayProp", new int[] { 0, -10, 231 }));
		String expected = """
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
	<key>TestIntegerArrayProp</key>
	<array>
		<integer>0</integer>
		<integer>-10</integer>
		<integer>231</integer>
	</array>
</dict>
</plist>			
""";
		assertEquals(expected, Files.readString(targetPlist.toPath()));
	}

	@Test
	public void testBundleInfoPlist() throws IOException {
		File tmpDir = Files.createTempDirectory("bundle-info").toFile();
		tmpDir.deleteOnExit();
		File targetPlist = new File(tmpDir, "Info.plist");
		PlistBuddyWrapper.CreateBundlePlistArgs args = new PlistBuddyWrapper.CreateBundlePlistArgs();
		args.bundleId = "com.defold.extender.TestResourceBundle";
		args.bundleName = "TestResourceBundle";
		args.version = "1";
		args.shortVersion = "4.3.1";
		args.minVersion = "13.0";
		args.supportedPlatforms = new String[] { "iPhoneOS", "iPhoneSimulator" };
		assertDoesNotThrow(() -> PlistBuddyWrapper.createBundleInfoPlist(targetPlist, args));

		String expected = """
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
	<key>CFBundleIdentifier</key>
	<string>com.defold.extender.TestResourceBundle</string>
	<key>CFBundleInfoDictionaryVersion</key>
	<string>6.0</string>
	<key>CFBundleName</key>
	<string>TestResourceBundle</string>
	<key>CFBundlePackageType</key>
	<string>BNDL</string>
	<key>CFBundleShortVersionString</key>
	<string>4.3.1</string>
	<key>CFBundleSupportedPlatforms</key>
	<array>
		<string>iPhoneOS</string>
		<string>iPhoneSimulator</string>
	</array>
	<key>CFBundleVersion</key>
	<string>1</string>
	<key>MinimumOSVersion</key>
	<string>13.0</string>
	<key>UIDeviceFamily</key>
	<array>
		<integer>1</integer>
		<integer>2</integer>
	</array>
</dict>
</plist>
""";
		assertEquals(expected, Files.readString(targetPlist.toPath()));
	}
}

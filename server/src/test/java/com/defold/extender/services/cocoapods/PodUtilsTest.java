// package com.defold.extender.services.cocoapods;

// import static org.junit.jupiter.api.Assertions.assertEquals;
// import static org.junit.jupiter.api.Assertions.assertFalse;
// import static org.junit.jupiter.api.Assertions.assertThrows;
// import static org.junit.jupiter.api.Assertions.assertTrue;

// import java.io.File;
// import java.io.IOException;
// import java.nio.file.Files;
// import java.nio.file.Path;
// import java.util.List;

// import org.junit.jupiter.api.Test;

// import com.defold.extender.ExtenderException;

// public class PodUtilsTest {

//     @Test
//     public void testPodNameSanitize() {
//         String[] testedNames = new String[] {
//             "DivKit_LayoutKit",
//             "GoogleUserMessagingPlatform",
//             "Google-Mobile-Ads-SDK",
//             "UIAlertController+Blocks",
//             "Socket.IO-Client-Swift"
//         };
//         String[] expectedNames = new String[] {
//             "DivKit_LayoutKit",
//             "GoogleUserMessagingPlatform",
//             "Google-Mobile-Ads-SDK",
//             "UIAlertController\\+Blocks",
//             "Socket.IO-Client-Swift"
//         };
//         assertEquals(testedNames.length, expectedNames.length);
//         for (int idx = 0; idx < testedNames.length; ++idx) {
//             assertEquals(expectedNames[idx], PodUtils.sanitizePodName(testedNames[idx]));
//         }
//     }

//     @Test
//     public void testListFilesByPattern() throws IOException {
//         File workDir = Files.createTempDirectory("collect-res-by-pattern").toFile();
//         workDir.deleteOnExit();
//         File parentDir = Path.of(workDir.toString(), "inner_folder1", "inner_folder2").toFile();
//         parentDir.mkdirs();
//         File bundleFile = new File(parentDir, "PodTest.bundle");
//         bundleFile.createNewFile();

//         File bundleDir = new File(parentDir, "PodTestBundleDir.bundle");
//         bundleDir.mkdirs();

//         List<File> files = PodUtils.listFilesAndDirsGlob(workDir, "inner_folder1/inner_folder2/PodTest.bundle");
//         assertEquals(1, files.size());

//         files = PodUtils.listFilesAndDirsGlob(workDir, "inner_folder1/inner_folder2/PodTestBundleDir.bundle");
//         assertEquals(1, files.size());
//     }

//     @Test
//     public void testHasSource() {
//         PodSpec spec = new PodSpec();
//         assertFalse(PodUtils.hasSourceFiles(spec));
//         spec.sourceFiles.addAll(List.of(new File("source/source1.cpp"), new File("source/source2.cpp")));
//         assertTrue(PodUtils.hasSourceFiles(spec));

//         PodSpec spec1 = new PodSpec();
//         spec1.swiftSourceFiles.addAll(List.of(new File("swift_code/swift1.swift")));
//         assertTrue(PodUtils.hasSourceFiles(spec1));
//     }

//     @Test
//     public void testSwiftModuleName() throws ExtenderException {
//         assertEquals("arm64-apple-ios", PodUtils.swiftModuleNameFromPlatform("arm64-ios"));
//         assertEquals("x86_64-apple-ios-simulator", PodUtils.swiftModuleNameFromPlatform("x86_64-ios"));
//         assertEquals("arm64-apple-macos", PodUtils.swiftModuleNameFromPlatform("arm64-macos"));
//         assertEquals("x86_64-apple-macos", PodUtils.swiftModuleNameFromPlatform("x86_64-macos"));
//         assertThrows(ExtenderException.class, () -> { PodUtils.swiftModuleNameFromPlatform("x86_64-linux"); });

//     }
// }

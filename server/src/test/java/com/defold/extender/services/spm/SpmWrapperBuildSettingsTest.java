package com.defold.extender.services.spm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.defold.extender.ExtenderException;
import com.defold.extender.services.spm.SpmWrapperBuildSettings.LinkInfo;

public class SpmWrapperBuildSettingsTest {

    // what the script phase of the wrapper wrote in an Xcode 27.1 iOS build of a package whose two
    // C targets have their own module.modulemap, one of them in a folder with a space, and link
    // libraries and frameworks through linkerSettings
    private static Map<String, String> fixture() throws IOException {
        File settingsDir = new File("test-data/spm-build-settings");
        return SpmWrapperBuildSettings.read(settingsDir, new File(settingsDir, "build-settings.txt"));
    }

    @Test
    public void testSwiftModuleMaps() throws IOException {
        String sources = "/tmp/job123/SwiftPackageManagerService/clonedSourcePackages/checkouts/Lib/Sources";
        assertEquals(List.of(sources + "/CBar/include/module.modulemap", sources + "/C Space/include/module.modulemap"),
            SpmWrapperBuildSettings.swiftModuleMaps(fixture().get("OTHER_SWIFT_FLAGS")));
    }

    @Test
    public void testSwiftModuleMapsTakesOnlyTheFlagsOfClang() {
        // only -Xcc values reach clang, and a response file among the flags is not read
        assertEquals(List.of("/x/a.modulemap", "/x/b.modulemap"), SpmWrapperBuildSettings.swiftModuleMaps(
            "-fmodule-map-file=/x/swift.modulemap -Xcc -fmodule-map-file=/x/a.modulemap "
            + "-Xcc '-fmodule-map-file=/x/b.modulemap' -Xcc -fmodule-map-file=/x/a.modulemap @/x/args.resp"));
        assertEquals(List.of(), SpmWrapperBuildSettings.swiftModuleMaps(""));
    }

    @Test
    public void testLinkInfo() throws IOException, ExtenderException {
        LinkInfo info = SpmWrapperBuildSettings.linkInfo(fixture().get("OTHER_LDFLAGS"));
        assertEquals(List.of("z", "sqlite3"), info.systemLibs());
        assertEquals(List.of("CoreML", "Accelerate"), info.frameworkNames());
    }

    @Test
    public void testLinkInfoOfXcode26() throws ExtenderException {
        // Xcode 26.6 repeats a linker warning flag ahead of what the packages link
        LinkInfo info = SpmWrapperBuildSettings.linkInfo("-Wl,-no_warn_duplicate_libraries "
            + "-Wl,-no_warn_duplicate_libraries  -framework CoreML -lz -framework Accelerate -lsqlite3 -lz");
        assertEquals(List.of("z", "sqlite3"), info.systemLibs());
        assertEquals(List.of("CoreML", "Accelerate"), info.frameworkNames());
    }

    @Test
    public void testLinkInfoTakesOnlyPlainNames() throws ExtenderException {
        // the names go into the command line of the engine link: anything but -l<name> and
        // -framework <name> is left out, and so is a name that is not plain
        LinkInfo info = SpmWrapperBuildSettings.linkInfo(
            "-lc++ -framework 'Foo Bar' -framework ../Evil -framework -Wl,-x -l/abs/libx.a -l'a b' "
            + "-lto_library /x/libLTO.dylib -load_hidden -lnot -ld_classic -ld_new -lazy_framework Lazy "
            + "-Xlinker -lfoo @/x/args.resp -L/x -F/x -Wl,-force_load,/x/libFoo.a -force_load /x/libBar.a "
            + "-framework UIKit -framework");
        assertEquals(List.of("c++", "foo"), info.systemLibs());
        assertEquals(List.of("UIKit"), info.frameworkNames());
    }

    @Test
    public void testLinkInfoLimitsTheNumberOfNames() throws ExtenderException {
        StringBuilder flags = new StringBuilder();
        for (int i = 0; i < 1024; i++) {
            flags.append(i % 2 == 0 ? " -lx" : " -framework X").append(i).append(" -lx0");
        }
        // a repeated name counts once
        LinkInfo info = SpmWrapperBuildSettings.linkInfo(flags.toString());
        assertEquals(512, info.systemLibs().size());
        assertEquals(512, info.frameworkNames().size());
        assertThrows(ExtenderException.class, () -> SpmWrapperBuildSettings.linkInfo(flags + " -lmore"));
    }

    @Test
    public void testRead(@TempDir File jobDir) throws IOException {
        File settings = new File(jobDir, "build-settings.txt");
        assertNull(SpmWrapperBuildSettings.read(jobDir, settings));

        Files.writeString(settings.toPath(), "OTHER_SWIFT_FLAGS_X=-Xcc x\nOTHER_SWIFT_FLAGS=-Xcc a=b\n"
            + "OTHER_SWIFT_FLAGS=later\nOTHER_LDFLAGS=\nno separator\n=no name\n");
        assertEquals(Map.of("OTHER_SWIFT_FLAGS_X", "-Xcc x", "OTHER_SWIFT_FLAGS", "-Xcc a=b", "OTHER_LDFLAGS", ""),
            SpmWrapperBuildSettings.read(jobDir, settings));
    }

    @Test
    public void testReadRefusesWhatTheBuildCouldPutInItsPlace(@TempDir File jobDir, @TempDir File outsideDir)
            throws IOException {
        File outside = new File(outsideDir, "build-settings.txt");
        Files.writeString(outside.toPath(), "OTHER_LDFLAGS=-lz\n");
        File inside = new File(jobDir, "other.txt");
        Files.writeString(inside.toPath(), "OTHER_LDFLAGS=-lz\n");

        // the script writes a regular file; a link in its place, even to a file of the job, is not its output
        File linkOut = new File(jobDir, "out.txt");
        Files.createSymbolicLink(linkOut.toPath(), outside.toPath());
        File linkIn = new File(jobDir, "in.txt");
        Files.createSymbolicLink(linkIn.toPath(), inside.toPath());
        File linkedDir = new File(jobDir, "Wrapper");
        Files.createSymbolicLink(linkedDir.toPath(), outsideDir.toPath());
        File dir = new File(jobDir, "dir.txt");
        dir.mkdirs();
        File large = new File(jobDir, "large.txt");
        try (RandomAccessFile file = new RandomAccessFile(large, "rw")) {
            file.setLength(4L * 1024 * 1024 + 1);
        }

        for (File settings : List.of(linkOut, linkIn, new File(linkedDir, "build-settings.txt"), dir, large)) {
            assertThrows(IOException.class, () -> SpmWrapperBuildSettings.read(jobDir, settings), settings.getName());
        }
    }
}

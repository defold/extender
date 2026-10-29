package com.defold.extender.services.spm;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

@EnabledOnOs({OS.MAC})
public class SpmWrapperArchiveTest {

    private static final String DEVELOPER_DIR =
        System.getenv().getOrDefault("DEVELOPER_DIR", "/Applications/Xcode.app/Contents/Developer");

    @TempDir
    File jobDir;
    File workDir;
    File sources;

    @BeforeEach
    void setUp() throws Exception {
        workDir = new File(jobDir, "SwiftPackageManagerService");
        sources = new File(jobDir, "sources");
        assertTrue(workDir.mkdirs() && new File(sources, "other").mkdirs());
        compile("a.c", "int a(void) { return 1; }");
        compile("b.c", "int b(void) { return 2; }");
        compile("other/a.c", "int other_a(void) { return 3; }");
        run("libtool", "-static", "-o", "libx.a", "a.o");
    }

    @Test
    public void testRemovesRepeatedLibraryMembersOnly() throws Exception {
        // the wrapper link of a graph where two packages reference the same binary target:
        // libx twice, plus a different member that happens to share a name with libx's
        File wrapper = new File(sources, "SpmWrapper");
        run("libtool", "-static", "-o", wrapper.getName(), "b.o", "libx.a", "libx.a", "other/a.o");
        assertEquals(List.of("b.o", "a.o", "a.o", "a.o"), names(wrapper));
        assertTrue(link(wrapper).contains("duplicate symbol"));

        assertEquals(1, removeDuplicateMembers(wrapper));

        List<SpmWrapperArchive.Member> members = members(wrapper);
        assertEquals(List.of("b.o", "a.o", "a.o"), members.stream().map(SpmWrapperArchive.Member::name).toList());
        assertNotEquals(members.get(1).digest(), members.get(2).digest());
        assertEquals("", link(wrapper));
        assertEquals(List.of(), List.of(workDir.list()));
    }

    @Test
    public void testArchiveWithoutRepeatsIsLeftAlone() throws Exception {
        File wrapper = new File(sources, "SpmWrapper");
        run("libtool", "-static", "-o", wrapper.getName(), "b.o", "libx.a", "other/a.o");
        byte[] before = Files.readAllBytes(wrapper.toPath());
        assertEquals(List.of("b.o", "a.o", "a.o"), names(wrapper));

        assertEquals(0, removeDuplicateMembers(wrapper));
        assertArrayEquals(before, Files.readAllBytes(wrapper.toPath()));
    }

    @Test
    public void testNonArchiveWrapperIsLeftAlone() throws Exception {
        File dylib = new File(sources, "SpmWrapper");
        run("clang", "-dynamiclib", "-o", dylib.getName(), "b.o");
        byte[] before = Files.readAllBytes(dylib.toPath());

        assertEquals(0, removeDuplicateMembers(dylib));
        assertArrayEquals(before, Files.readAllBytes(dylib.toPath()));
        assertEquals(0, removeDuplicateMembers(new File(sources, "missing")));
        try (FileChannel channel = FileChannel.open(dylib.toPath(), StandardOpenOption.READ)) {
            assertNull(SpmWrapperArchive.readMembers(channel));
        }
    }

    @Test
    public void testMemberNamesCannotLeaveTheirDirectory() {
        assertEquals("a.o", SpmWrapperArchive.safeFileName("a.o", 0));
        assertEquals("member1.o", SpmWrapperArchive.safeFileName("../a.o", 1));
        assertEquals("member2.o", SpmWrapperArchive.safeFileName("..", 2));
        assertEquals("member3.o", SpmWrapperArchive.safeFileName("a\n.o", 3));
        assertEquals("member4.o", SpmWrapperArchive.safeFileName("", 4));
    }

    private int removeDuplicateMembers(File wrapper) throws Exception {
        return SpmWrapperArchive.removeDuplicateMembers(jobDir, wrapper, workDir, DEVELOPER_DIR, "arm64",
            Map.of("DEVELOPER_DIR", DEVELOPER_DIR));
    }

    private static List<SpmWrapperArchive.Member> members(File archive) throws IOException {
        try (FileChannel channel = FileChannel.open(archive.toPath(), StandardOpenOption.READ)) {
            return SpmWrapperArchive.readMembers(channel);
        }
    }

    private static List<String> names(File archive) throws IOException {
        return members(archive).stream().map(SpmWrapperArchive.Member::name).toList();
    }

    private void compile(String source, String code) throws Exception {
        Files.writeString(new File(sources, source).toPath(), code);
        run("clang", "-arch", "arm64", "-c", "-o", source.replace(".c", ".o"), source);
    }

    /** Loads every member, as an -ObjC or -force_load engine link may: repeats are duplicate symbols. */
    private String link(File archive) throws Exception {
        Files.writeString(new File(sources, "main.c").toPath(),
            "int a(void); int b(void); int other_a(void);\nint main(void) { return a() + b() + other_a(); }\n");
        ProcessBuilder builder = new ProcessBuilder("/usr/bin/xcrun", "clang", "-arch", "arm64", "-o", "main", "main.c",
            "-Wl,-force_load," + archive.getAbsolutePath()).directory(sources).redirectErrorStream(true);
        builder.environment().put("DEVELOPER_DIR", DEVELOPER_DIR);
        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes());
        return process.waitFor() == 0 ? "" : output;
    }

    private void run(String... command) throws Exception {
        String[] full = new String[command.length + 1];
        full[0] = "/usr/bin/xcrun";
        System.arraycopy(command, 0, full, 1, command.length);
        ProcessBuilder builder = new ProcessBuilder(full).directory(sources).redirectErrorStream(true);
        builder.environment().put("DEVELOPER_DIR", DEVELOPER_DIR);
        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes());
        assertEquals(0, process.waitFor(), String.join(" ", full) + ":\n" + output);
    }
}

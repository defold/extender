package com.defold.extender.services.spm;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.defold.extender.ExtenderException;
import com.defold.extender.process.JobFiles;
import com.defold.extender.process.ProcessUtils;

/**
 * Removes members a static wrapper archive holds more than once.
 *
 * Xcode puts a binary target's library on the wrapper's libtool line once per package that
 * references the binary target's xcframework, and libtool -static archives every member of
 * every input without looking for repeats, so the engine link then fails on duplicate
 * symbols. A member is a duplicate only when both its name and its bytes match an earlier
 * one; members that merely share a name are kept. The archive is rebuilt with the
 * toolchain libtool only when something repeats.
 */
final class SpmWrapperArchive {

    private static final Logger LOGGER = LoggerFactory.getLogger(SpmWrapperArchive.class);

    private static final byte[] MAGIC = "!<arch>\n".getBytes(StandardCharsets.US_ASCII);
    private static final int HEADER_SIZE = 60;
    private static final String LONG_NAME_PREFIX = "#1/";
    private static final String SYMBOL_TABLE_PREFIX = "__.SYMDEF";

    record Member(String name, long contentOffset, long contentSize, String digest) {}

    private SpmWrapperArchive() { }

    /**
     * Returns the number of duplicate members removed; 0 leaves the archive untouched, as does
     * anything that is not a BSD ar archive (a dynamic wrapper, for one).
     */
    static int removeDuplicateMembers(File jobDir, File binary, File workDir, String developerDir, String arch,
            Map<String, String> env) throws IOException, ExtenderException {
        if (!binary.isFile()) {
            return 0;
        }
        File archive = JobFiles.requireWithin(jobDir, binary);
        List<Member> unique = new ArrayList<>();
        int duplicates = 0;
        try (FileChannel channel = FileChannel.open(archive.toPath(), StandardOpenOption.READ)) {
            List<Member> members = readMembers(channel);
            if (members == null) {
                return 0;
            }
            Set<String> seen = new HashSet<>();
            for (Member member : members) {
                if (seen.add(member.name() + '\0' + member.digest())) {
                    unique.add(member);
                } else {
                    duplicates++;
                }
            }
            if (duplicates == 0) {
                return 0;
            }
            LOGGER.info("Removing {} duplicate members from the Swift package wrapper archive", duplicates);
            rebuild(jobDir, channel, unique, archive, workDir, developerDir, arch, env);
        }
        return duplicates;
    }

    /** The members in archive order, symbol tables left out; null when this is not a BSD ar archive. */
    static List<Member> readMembers(FileChannel channel) throws IOException {
        long length = channel.size();
        ByteBuffer magic = ByteBuffer.allocate(MAGIC.length);
        if (length < MAGIC.length) {
            return null;
        }
        readFully(channel, magic, 0);
        if (!magic.flip().equals(ByteBuffer.wrap(MAGIC))) {
            return null;
        }
        MessageDigest sha256 = sha256();
        List<Member> members = new ArrayList<>();
        long offset = MAGIC.length;
        while (offset < length) {
            if (length - offset < HEADER_SIZE) {
                throw new IOException("Truncated archive member header at offset " + offset);
            }
            ByteBuffer header = ByteBuffer.allocate(HEADER_SIZE);
            readFully(channel, header, offset);
            String fields = new String(header.array(), StandardCharsets.US_ASCII);
            if (!fields.endsWith("`\n")) {
                throw new IOException("Malformed archive member header at offset " + offset);
            }
            String rawName = fields.substring(0, 16).stripTrailing();
            long size = parseSize(fields.substring(48, 58).trim(), offset);
            long dataOffset = offset + HEADER_SIZE;
            if (size > length - dataOffset) {
                throw new IOException("Archive member at offset " + offset + " runs past the end of the file");
            }
            String name;
            long nameLength = 0;
            if (rawName.startsWith(LONG_NAME_PREFIX)) {
                nameLength = parseSize(rawName.substring(LONG_NAME_PREFIX.length()), offset);
                if (nameLength > size) {
                    throw new IOException("Archive member name at offset " + offset + " runs past the member");
                }
                ByteBuffer nameBytes = ByteBuffer.allocate((int) nameLength);
                readFully(channel, nameBytes, dataOffset);
                name = new String(nameBytes.array(), StandardCharsets.UTF_8).replaceAll("\0+$", "");
            } else if (rawName.startsWith("/")) {
                // a System V (GNU) archive: never written by the Apple toolchain
                return null;
            } else {
                name = rawName;
            }
            long contentOffset = dataOffset + nameLength;
            long contentSize = size - nameLength;
            if (!name.startsWith(SYMBOL_TABLE_PREFIX)) {
                members.add(new Member(name, contentOffset, contentSize, digest(channel, contentOffset, contentSize, sha256)));
            }
            offset = dataOffset + size + (size % 2);
        }
        return members;
    }

    private static void rebuild(File jobDir, FileChannel channel, List<Member> members, File archive, File workDir,
            String developerDir, String arch, Map<String, String> env) throws IOException, ExtenderException {
        Path tmpDir = Files.createTempDirectory(JobFiles.requireWithin(jobDir.toPath(), workDir.toPath()), "wrapper-archive");
        try {
            StringBuilder fileList = new StringBuilder();
            for (int i = 0; i < members.size(); i++) {
                Member member = members.get(i);
                // each member in a directory of its own: libtool names a member after its file,
                // and different members may share a name
                Path memberDir = Files.createDirectory(tmpDir.resolve(Integer.toString(i)));
                Path file = memberDir.resolve(safeFileName(member.name(), i));
                try (OutputStream os = JobFiles.newOutputStream(jobDir.toPath(), file)) {
                    long copied = channel.transferTo(member.contentOffset(), member.contentSize(), Channels.newChannel(os));
                    if (copied != member.contentSize()) {
                        throw new IOException("Short read extracting " + member.name() + " from " + archive);
                    }
                }
                fileList.append(file).append('\n');
            }
            File listFile = tmpDir.resolve("members.LinkFileList").toFile();
            JobFiles.writeString(jobDir, listFile, fileList.toString(), StandardCharsets.UTF_8);
            File rebuilt = tmpDir.resolve("rebuilt.a").toFile();
            ProcessUtils.execCommand(List.of(
                developerDir + "/Toolchains/XcodeDefault.xctoolchain/usr/bin/libtool",
                "-static", "-arch_only", arch, "-D", "-no_warning_for_no_symbols",
                "-filelist", listFile.getAbsolutePath(),
                "-o", rebuilt.getAbsolutePath()), workDir, env);

            try (FileChannel rebuiltChannel = FileChannel.open(rebuilt.toPath(), StandardOpenOption.READ)) {
                List<Member> written = readMembers(rebuiltChannel);
                if (written == null || !sameMembers(members, written)) {
                    throw new ExtenderException("Rebuilding the Swift package wrapper archive without duplicate members "
                        + "produced an archive with different members");
                }
            }
            Files.move(rebuilt.toPath(), archive.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } finally {
            FileUtils.deleteQuietly(tmpDir.toFile());
        }
    }

    private static boolean sameMembers(List<Member> expected, List<Member> actual) {
        if (expected.size() != actual.size()) {
            return false;
        }
        for (int i = 0; i < expected.size(); i++) {
            if (!expected.get(i).digest().equals(actual.get(i).digest())) {
                return false;
            }
        }
        return true;
    }

    static String safeFileName(String name, int index) {
        if (name.isEmpty() || name.equals(".") || name.equals("..") || name.chars().anyMatch(c -> c == '/' || c < 0x20)) {
            return "member" + index + ".o";
        }
        return name;
    }

    private static long parseSize(String value, long headerOffset) throws IOException {
        try {
            long size = Long.parseLong(value);
            if (size < 0) {
                throw new NumberFormatException(value);
            }
            return size;
        } catch (NumberFormatException e) {
            throw new IOException("Malformed size '" + value + "' in the archive member header at offset " + headerOffset);
        }
    }

    private static void readFully(FileChannel channel, ByteBuffer buffer, long offset) throws IOException {
        while (buffer.hasRemaining()) {
            if (channel.read(buffer, offset + buffer.position()) < 0) {
                throw new IOException("Unexpected end of archive at offset " + (offset + buffer.position()));
            }
        }
    }

    private static String digest(FileChannel channel, long offset, long size, MessageDigest sha256) throws IOException {
        sha256.reset();
        ByteBuffer buffer = ByteBuffer.allocate(64 * 1024);
        long position = offset;
        long end = offset + size;
        while (position < end) {
            buffer.clear();
            buffer.limit((int) Math.min(buffer.capacity(), end - position));
            int read = channel.read(buffer, position);
            if (read < 0) {
                throw new IOException("Unexpected end of archive at offset " + position);
            }
            buffer.flip();
            sha256.update(buffer);
            position += read;
        }
        return HexFormat.of().formatHex(sha256.digest());
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}

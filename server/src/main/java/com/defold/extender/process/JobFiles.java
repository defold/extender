package com.defold.extender.process;

import java.io.File;
import java.io.FileFilter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

import org.apache.commons.io.FileUtils;

/**
 * File access the server makes in a job directory. Build commands run sandboxed but can write
 * anywhere in the job directory, including links to files the sandbox denies them; the server
 * runs unconfined, so it must neither write through such a link nor read or ship what one
 * points at. Every method takes the job directory first: the operation happens inside it.
 */
public final class JobFiles {

    private JobFiles() {}

    /**
     * Opens {@code file} for writing as a new file. Whatever is at that name (a link, or a hard
     * link to a file elsewhere) is removed first rather than written through, and the directory
     * it is created in must resolve inside {@code jobDir}: any directory below it may have been
     * swapped for a link.
     */
    public static OutputStream newOutputStream(Path jobDir, Path file) throws IOException {
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) {
            requireWithin(jobDir, parent);
        }
        Files.deleteIfExists(file);
        return Files.newOutputStream(file, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS);
    }

    public static OutputStream newOutputStream(File jobDir, File file) throws IOException {
        return newOutputStream(jobDir.toPath(), file.toPath());
    }

    public static void write(Path jobDir, Path file, byte[] data) throws IOException {
        try (OutputStream os = newOutputStream(jobDir, file)) {
            os.write(data);
        }
    }

    public static void write(File jobDir, File file, byte[] data) throws IOException {
        write(jobDir.toPath(), file.toPath(), data);
    }

    public static void writeString(File jobDir, File file, String content, Charset charset) throws IOException {
        write(jobDir.toPath(), file.toPath(), content.getBytes(charset));
    }

    /** The real path of {@code path}, which must lie inside the real path of {@code jobDir}. */
    public static Path requireWithin(Path jobDir, Path path) throws IOException {
        Path real = path.toRealPath();
        Path realJobDir = jobDir.toRealPath();
        if (!real.startsWith(realJobDir)) {
            throw new IOException(String.format("%s resolves to %s, outside %s", path, real, realJobDir));
        }
        return real;
    }

    public static File requireWithin(File jobDir, File file) throws IOException {
        return requireWithin(jobDir.toPath(), file.toPath()).toFile();
    }

    /**
     * Copies a file whose real path must lie inside {@code jobDir}; links inside it are fine
     * (frameworks carry {@code Versions/Current}), links out of it are refused.
     */
    public static void copyFile(File jobDir, File source, File target) throws IOException {
        Path real = requireWithin(jobDir.toPath(), source.toPath());
        if (!Files.isRegularFile(real)) {
            throw new IOException(source + " is not a regular file");
        }
        Files.createDirectories(target.toPath().toAbsolutePath().getParent());
        Files.copy(real, target.toPath(), StandardCopyOption.REPLACE_EXISTING);
    }

    public static void copyFileToDirectory(File jobDir, File source, File directory) throws IOException {
        copyFile(jobDir, source, new File(directory, source.getName()));
    }

    /** {@link FileUtils#copyDirectory(File, File, FileFilter)} refusing every entry that resolves outside {@code jobDir}. */
    public static void copyDirectory(File jobDir, File source, File target, FileFilter filter) throws IOException {
        requireWithin(jobDir, source);
        FileFilter confined = pathname -> {
            try {
                requireWithin(jobDir, pathname);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            return filter == null || filter.accept(pathname);
        };
        try {
            FileUtils.copyDirectory(source, target, confined);
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }

    public static void copyDirectoryToDirectory(File jobDir, File source, File directory) throws IOException {
        copyDirectory(jobDir, source, new File(directory, source.getName()), null);
    }
}

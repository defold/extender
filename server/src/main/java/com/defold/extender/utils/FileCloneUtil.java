package com.defold.extender.utils;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;

public class FileCloneUtil {
    /**
     * Recursively copy src into dst. Symlinks are copied as links.
     * On macOS with JDK 21+ each file is cloned with clonefile(2) (APFS copy-on-write), falling
     * back to a regular copy across volumes or on filesystems without clone support.
     * @param src Source directory
     * @param dst Destination directory, created if missing
     * @param skipExisting If true, files already present in dst are left untouched and only new
     *                     entries are added. If false, an existing file fails the copy.
     */
    public static void cloneTree(Path src, Path dst, boolean skipExisting) throws IOException {
        Files.walkFileTree(src, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Files.createDirectories(dst.resolve(src.relativize(dir)));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Path target = dst.resolve(src.relativize(file));
                if (skipExisting && Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                    return FileVisitResult.CONTINUE;
                }
                // COPY_ATTRIBUTES is what makes the JDK try clonefile(2); without it Files.copy
                // silently does a full data copy
                Files.copy(file, target, StandardCopyOption.COPY_ATTRIBUTES, LinkOption.NOFOLLOW_LINKS);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}

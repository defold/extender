package com.defold.extender.cache;

import org.apache.commons.lang3.StringUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

public class SimpleDiskDataCache implements DataCache {

    private final File baseDirectory;

    public SimpleDiskDataCache(final String baseDirectory) throws IOException {
        this.baseDirectory = StringUtils.isNotBlank(baseDirectory)
                ? new File(pruneLastSlashInDirectory(baseDirectory))
                : createTemporaryDirectory();
    }

    @Override
    public InputStream get(final String key) {
        final String path = getDestinationPath(key);
        try {
            return new FileInputStream(path);
        } catch(FileNotFoundException e) {
            throw new IllegalArgumentException("Cached file not found: " + path);
        }
    }

    @Override
    public boolean exists(String key) {
        return Files.exists(Paths.get(getDestinationPath(key)));
    }

    @Override
    public void put(final String key, final File file) throws IOException {
        final File destination = createDestinationPath(key);

        Files.copy(
                file.toPath(),
                destination.toPath(),
                StandardCopyOption.REPLACE_EXISTING);
    }

    private String pruneLastSlashInDirectory(final String directory) {
        return StringUtils.removeEnd(directory, "/");
    }

    private File createTemporaryDirectory() throws IOException {
        File temporaryDirectory = Files.createTempDirectory("upload").toFile();
        temporaryDirectory.deleteOnExit();
        return temporaryDirectory;
    }

    private String getDestinationPath(final String key) {
        final String subDirectory = key.substring(0,2);
        return String.format("%s/%s/%s", baseDirectory, subDirectory, key);
    }

    private File createDestinationPath(final String key) {
        final File destination = new File(getDestinationPath(key));
        createParentDirectory(destination);
        return destination;
    }

    private void createParentDirectory(File destination) {
        final File parentDirectory = destination.getParentFile();

        if (! parentDirectory.exists()) {
            parentDirectory.mkdir();
        } else if (! parentDirectory.isDirectory()) {
            throw new IllegalArgumentException("Cached file parent directory is not a directory: " + parentDirectory.getPath());
        }
    }
}

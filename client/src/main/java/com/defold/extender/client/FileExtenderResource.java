package com.defold.extender.client;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

public class FileExtenderResource implements ExtenderResource {
    private File file = null;
    private String filePath;

    public FileExtenderResource(String filePath) {
        this(new File(filePath));
    }

    public FileExtenderResource(String filePath, String zipPath) {
        this(new File(filePath));
        this.filePath = zipPath;
    }

    FileExtenderResource(File file) {
        this.file = file;
        this.filePath = file.getPath();
    }

    @Override
    public String getPath() {
        return filePath;
    }

    @Override
    public byte[] getContent() throws IOException {
        return Files.readAllBytes(this.file.toPath());
    }

    @Override
    public long getLastModified() {
        return file.lastModified();
    }
}

package com.defold.extender.client;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

public class FileExtenderResource implements ExtenderResource {
    private File file = null;
    private String filePath;
    private String fileAbsPath;

    public FileExtenderResource(String filePath) {
        this(new File(filePath));
    }

    FileExtenderResource(File file) {
        this.file = file;
        this.filePath = file.getPath();
        this.fileAbsPath = file.getAbsolutePath();
    }

    @Override
    public byte[] sha1() throws IOException {
        return new byte[0];
    }

    @Override
    public String getAbsPath() {
        return fileAbsPath;
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

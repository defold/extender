package com.defold.extender.services;

import java.io.File;

public final class GradleArtifact {
    public enum Kind {
        EXPLODED_AAR,
        JAR
    }

    private final File file;
    private final String component;
    private final String originalFileName;
    private final Kind kind;
    private final String resourcePackageName;

    GradleArtifact(
            File file,
            String component,
            String originalFileName,
            Kind kind,
            String resourcePackageName) {
        this.file = file;
        this.component = component;
        this.originalFileName = originalFileName;
        this.kind = kind;
        this.resourcePackageName = resourcePackageName;
    }

    public File getFile() {
        return file;
    }

    public String getComponent() {
        return component;
    }

    public String getOriginalFileName() {
        return originalFileName;
    }

    public Kind getKind() {
        return kind;
    }

    public String getResourcePackageName() {
        return resourcePackageName;
    }
}

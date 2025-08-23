package com.defold.extender.services.cocoapods;

import java.io.File;
import java.util.Set;

public class MainPodfile {
    public Set<String> podDefinitions;
    public String platformMinVersion;
    public String platform;
    public File file;
    public boolean useFrameworks = true; // left true by default for now for backward compatability

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("file: " + file);
        sb.append("pod count: " + podDefinitions.size() + "\n");
        sb.append("pod definitions: " + podDefinitions + "\n");
        sb.append("platform: " + platform + "\n");
        sb.append("min version: " + platformMinVersion + "\n");
        sb.append("use framerorks: " + String.valueOf(useFrameworks) + "\n");
        return sb.toString();
    }
}

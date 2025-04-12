package com.defold.extender.services.cocoapods;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class MainPodfile {
    public List<String> podnames = new ArrayList<>();
    public String platformMinVersion;
    public String platform;
    public File file;

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("file: " + file);
        sb.append("pod count: " + podnames.size() + "\n");
        sb.append("pod names: " + podnames + "\n");
        sb.append("platform: " + platform + "\n");
        sb.append("min version: " + platformMinVersion + "\n");
        return sb.toString();
    }
}

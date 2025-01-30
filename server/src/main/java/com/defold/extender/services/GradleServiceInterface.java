package com.defold.extender.services;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;

import com.defold.extender.ExtenderException;

public interface GradleServiceInterface {
    // Resolve dependencies, download them, extract to
    public List<File> resolveDependencies(Map<String, Object> env, File cwd, File buildDirectory, Boolean useJetifier, List<File> outputFiles) throws IOException, ExtenderException;
    
    public long getCacheSize() throws IOException;
}

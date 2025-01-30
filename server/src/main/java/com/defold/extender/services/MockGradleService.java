package com.defold.extender.services;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import com.defold.extender.ExtenderException;

@Service
@ConditionalOnProperty(name = "extender.gradle.enabled", havingValue = "false", matchIfMissing = true)
public class MockGradleService implements GradleServiceInterface {
    @Override
    public List<File> resolveDependencies(Map<String, Object> env, File cwd, File buildDirectory, Boolean useJetifier, List<File> outputFiles)
            throws IOException, ExtenderException {
        return List.of();
    }

    @Override
    public long getCacheSize() throws IOException {
        return 0;
    }
    
}

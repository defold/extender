package com.defold.extender.services;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.defold.extender.ExtenderException;

@Service
public class GradleService {
    private final GradleServiceInterface gradleService;

    public GradleService(GradleServiceInterface service) {
        gradleService = service;
    }

    public List<File> resolveDependencies(Map<String, Object> env, File cwd, Boolean useJetifier)
        throws IOException, ExtenderException {
        return gradleService.resolveDependencies(env, cwd, useJetifier);
    }

    public long getCacheSize() throws IOException {
        return gradleService.getCacheSize();
    }
}

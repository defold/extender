package com.defold.extender.services;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.defold.extender.ExtenderException;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.BaseUnits;

@Service
public class GradleService {
    private final GradleServiceInterface gradleService;

    public GradleService(GradleServiceInterface service,
                        MeterRegistry registry) {
        gradleService = service;
        Gauge.builder("extender.job.gradle.cacheSize", this, GradleService::getCacheSize).baseUnit(BaseUnits.BYTES).register(registry);
    }

    public List<File> resolveDependencies(Map<String, Object> env, File cwd, File buildDirectory, Boolean useJetifier, List<File> outputFiles)
        throws IOException, ExtenderException {
        return gradleService.resolveDependencies(env, cwd, buildDirectory, useJetifier, outputFiles);
    }

    public long getCacheSize() {
        try {
            return gradleService.getCacheSize();
        } catch (IOException exc) {
            return 0;
        }
    }
}

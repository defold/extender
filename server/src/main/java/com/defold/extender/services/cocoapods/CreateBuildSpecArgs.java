package com.defold.extender.services.cocoapods;

import java.io.File;
import java.util.Map;

import com.defold.extender.ExtenderBuildState;

public class CreateBuildSpecArgs {
    public static class Builder {
        private File podsDir;
        private File buildDir;
        private PodUtils.Platform selectedPlatform;
        private String configuration;
        private Map<String, Object> jobEnvContext;
        private IConfigParser configParser;

        public Builder() { }

        public Builder setJobContext(Map<String, Object> context) {
            this.jobEnvContext = context;
            return this;
        }

        public Builder setConfigParser(IConfigParser parser) {
            this.configParser = parser;
            return this;
        }

        public Builder setExtenderBuildState(ExtenderBuildState buildState) {
            this.buildDir = buildState.getBuildDir();
            this.configuration = buildState.getBuildConfiguration();
            return this;
        }

        public Builder setCocoapodsBuildState(CocoaPodsServiceBuildState buildState) {
            this.podsDir = buildState.getPodsDir();
            this.selectedPlatform = buildState.getSelectedPlatform();
            return this;
        }

        public CreateBuildSpecArgs build() {
            return new CreateBuildSpecArgs(this);
        }
    }

    private CreateBuildSpecArgs(Builder builder) {
        this.podsDir = builder.podsDir;
        this.buildDir = builder.buildDir;
        this.jobEnvContext = builder.jobEnvContext;
        this.selectedPlatform = builder.selectedPlatform;
        this.configuration = builder.configuration;
        this.configParser = builder.configParser;
    }

    private CreateBuildSpecArgs(CreateBuildSpecArgs copy) {
        this.podsDir = copy.podsDir;
        this.buildDir = copy.buildDir;
        this.jobEnvContext = copy.jobEnvContext;
        this.selectedPlatform = copy.selectedPlatform;
        this.configuration = copy.configuration;
        this.configParser = copy.configParser;
    }

    protected File podsDir;
    protected File buildDir;
    protected PodUtils.Platform selectedPlatform;
    protected String configuration;
    protected Map<String, Object> jobEnvContext;
    protected IConfigParser configParser;
}

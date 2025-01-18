package com.defold.extender.services;

import java.nio.file.Path;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Builder;
import lombok.Data;
import lombok.experimental.SuperBuilder;


@Data
@SuperBuilder(toBuilder = true)
@Component
@ConfigurationProperties(prefix = "extender.sdk")
public class DefoldSdkServiceConfiguration {
    private Path location;
    private int cacheSize;
    @Builder.Default private int mappingsCacheSize = 20;
    private boolean cacheClearOnExit;
    private String[] sdkUrls;
    private String[] mappingsUrls;
    // retry count in case of checksum validation fail
    @Builder.Default private int maxVerificationRetryCount = 3;

    // protected DefoldSdkServiceConfiguration(DefoldSdkServiceConfiguration copy) {
    //     this.location = copy.location;
    //     this.cacheSize = copy.cacheSize;
    //     this.mappingsCacheSize = copy.mappingsCacheSize;
    //     this.cacheClearOnExit = copy.cacheClearOnExit;
    //     this.sdkUrls = copy.sdkUrls;
    //     this.mappingsUrls = copy.;
    //     this.maxVerificationRetryCount = 3;
    // }
}

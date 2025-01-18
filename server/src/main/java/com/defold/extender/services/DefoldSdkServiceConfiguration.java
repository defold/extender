package com.defold.extender.services;

import java.nio.file.Path;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.experimental.SuperBuilder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Component
@ConfigurationProperties(prefix = "extender.sdk")
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder(toBuilder = true)
public class DefoldSdkServiceConfiguration {
    private Path location;
    private String[] sdkUrls;
    private String[] mappingsUrls;
    private int cacheSize;
    @Builder.Default private int mappingsCacheSize = 20;
    // retry count in case of checksum validation fail
    @Builder.Default private int maxVerificationRetryCount = 3;
    private boolean cacheClearOnExit;
    private boolean enableSdkVerification;
}

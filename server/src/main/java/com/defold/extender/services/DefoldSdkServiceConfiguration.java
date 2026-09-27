package com.defold.extender.services;

import java.nio.file.Path;
import java.util.List;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.experimental.SuperBuilder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Component
@ConfigurationProperties(prefix = "extender.sdk", ignoreUnknownFields = false)
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder(toBuilder = true)
public class DefoldSdkServiceConfiguration {
    private Path location;
    @Builder.Default private List<Source> sources = List.of();
    // Transitional support for deployments whose paired URLs use the standard filenames.
    private String[] sdkUrls;
    private String[] mappingsUrls;
    private int cacheSize;
    @Builder.Default private int mappingsCacheSize = 20;
    // retry count in case of checksum validation fail
    @Builder.Default private int maxVerificationRetryCount = 3;
    @Builder.Default private int maxRedirectCount = 5;
    private boolean cacheClearOnExit;
    private boolean enableSdkVerification;

    public List<Source> getConfiguredSources() {
        if (sdkUrls == null && mappingsUrls == null) {
            return sources;
        }
        if (sdkUrls == null || mappingsUrls == null) {
            throw new IllegalArgumentException("Both legacy SDK URL lists are required; migrate to extender.sdk.sources");
        }
        var archives = new HashSet<>(Arrays.asList(sdkUrls));
        var result = new ArrayList<Source>();
        for (String mapping : mappingsUrls) {
            String archive = mapping.replace("platform.sdks.json", "defoldsdk.zip");
            if (mapping.equals(archive) || !archives.remove(archive)) {
                throw new IllegalArgumentException("Legacy SDK URLs cannot be paired unambiguously; migrate to extender.sdk.sources");
            }
            result.add(new Source(mapping, archive));
        }
        if (!archives.isEmpty()) {
            throw new IllegalArgumentException("Legacy SDK URLs cannot be paired unambiguously; migrate to extender.sdk.sources");
        }
        return result;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Source {
        private String mappingsUrl;
        private String sdkUrl;
    }
}

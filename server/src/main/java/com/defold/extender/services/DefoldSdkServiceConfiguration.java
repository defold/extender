package com.defold.extender.services;

import java.nio.file.Path;
import java.util.Map;

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
    public static final String DEFAULT_URL_KEY = "other";

    private Path location;
    private Map<String, String> sdkUrls;
    private Map<String, String> mappingsUrls;
    private int cacheSize;
    @Builder.Default private int mappingsCacheSize = 20;
    // retry count in case of checksum validation fail
    @Builder.Default private int maxVerificationRetryCount = 3;
    @Builder.Default private int maxRedirectCount = 5;
    private boolean cacheClearOnExit;
    private boolean enableSdkVerification;

    public String getUrlKey(String platform) {
        if (platform != null
            && (sdkUrls != null && sdkUrls.containsKey(platform) || mappingsUrls != null && mappingsUrls.containsKey(platform))) {
            return platform;
        }
        return DEFAULT_URL_KEY;
    }

    public String getSdkUrl(String platform) {
        return resolve(sdkUrls, platform);
    }

    public String getMappingsUrl(String platform) {
        return resolve(mappingsUrls, platform);
    }

    private static String resolve(Map<String, String> urls, String platform) {
        if (urls == null) {
            return null;
        }
        if (platform != null && urls.containsKey(platform)) {
            return urls.get(platform);
        }
        return urls.get(DEFAULT_URL_KEY);
    }
}

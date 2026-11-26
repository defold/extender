package com.defold.extender.remote.operator;

import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import com.defold.extender.remote.RemoteHostConfiguration;
import com.defold.extender.remote.RemoteInstanceConfig;
import com.defold.extender.remote.RemoteInstanceController;


/**
 * Optional remote instance controller for builders managed by an extender operator on Kubernetes
 * (scaled to zero while idle). Off unless {@code extender.operator.controller.enabled=true}; when
 * off, nothing is bound or validated.
 *
 * <p>Provides the router's {@link RemoteInstanceController} (consumed as an {@code Optional} by
 * RemoteEngineBuilder and HealthReporterService), so it is mutually exclusive with the GCP controller.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = OperatorControllerProperties.PREFIX, name = "enabled", havingValue = "true")
@EnableConfigurationProperties(OperatorControllerProperties.class)
public class OperatorControllerConfiguration {

    static final String GCP_CONTROLLER_PROPERTY = "extender.gcp.controller.enabled";
    private static final Logger log = LoggerFactory.getLogger(OperatorControllerConfiguration.class);

    public OperatorControllerConfiguration(Environment environment) {
        // Two RemoteInstanceController beans would break the router's Optional injection with an
        // obscure error; say what is wrong instead.
        if (environment.getProperty(GCP_CONTROLLER_PROPERTY, Boolean.class, false)) {
            throw new IllegalStateException(GCP_CONTROLLER_PROPERTY + " and " + OperatorControllerProperties.PREFIX
                    + ".enabled are mutually exclusive: both provide the router's RemoteInstanceController");
        }
    }

    @Bean
    @ConditionalOnMissingBean
    TokenProvider operatorTokenProvider(OperatorControllerProperties properties) {
        TokenProvider provider = new TokenProvider(properties.tokenFile());
        log.info("Extender operator integration enabled: url={}, token-file={}", properties.url(), properties.tokenFile());
        return provider;
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    TouchApiClient operatorTouchApiClient(OperatorControllerProperties properties, TokenProvider tokens) {
        return new TouchApiClient(properties.url(), tokens);
    }

    @Bean
    @ConditionalOnMissingBean(RemoteInstanceController.class)
    OperatorInstanceController operatorInstanceController(OperatorControllerProperties properties,
            TouchApiClient client, RemoteHostConfiguration remoteHosts) {
        Set<String> controlled = controlledInstanceIds(remoteHosts.getPlatforms());
        log.info("Extender operator controls builders {}", controlled);
        return new OperatorInstanceController(client, controlled, new OperatorInstanceController.Timing(
                properties.waitTimeout(), properties.pollInterval(), properties.stateCacheTtl()));
    }

    /**
     * Remote builders with an instance id that aren't alwaysOn (same rule as the GCP controller).
     * Ids that can't be an ExtenderBuilder name (not DNS-1123, e.g. GCE-style {@code winsdk-2026_1451})
     * belong to something else: skipped with a WARN instead of failing the whole router.
     */
    static Set<String> controlledInstanceIds(Map<String, RemoteInstanceConfig> platforms) {
        Set<String> ids = new TreeSet<>();
        if (platforms == null) {
            return ids;
        }
        for (Map.Entry<String, RemoteInstanceConfig> entry : platforms.entrySet()) {
            RemoteInstanceConfig config = entry.getValue();
            if (config == null || config.getInstanceId() == null || config.getInstanceId().isBlank() || config.getAlwaysOn()) {
                continue;
            }
            if (!TouchApiClient.validName(config.getInstanceId())) {
                log.warn("Platform '{}' instance id '{}' is not an ExtenderBuilder name (DNS-1123 label); not controlled by the operator",
                        entry.getKey(), config.getInstanceId());
                continue;
            }
            ids.add(config.getInstanceId());
        }
        return ids;
    }
}

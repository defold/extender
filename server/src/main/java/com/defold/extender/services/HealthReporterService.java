package com.defold.extender.services;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.util.EntityUtils;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.defold.extender.remote.RemoteInstanceConfig;
import com.defold.extender.tracing.ExtenderTracerInterceptor;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;


@Service
public class HealthReporterService {

    @Value("${extender.health-reporter.connection-timeout:1500}") int connectionTimeout;
    public enum OperationalStatus {
        Unreachable,
        NotFullyOperational,
        Operational
    }

    private GCPInstanceService instanceService = null;
    private final HttpClient httpClient;

    public HealthReporterService(Optional<GCPInstanceService> instanceService,
                            @Autowired Tracer tracer,
                            @Autowired Propagator propogator) {
        instanceService.ifPresent(val -> { this.instanceService = val; });
        RequestConfig config = RequestConfig.custom()
            .setConnectTimeout(connectionTimeout)
            .setConnectionRequestTimeout(connectionTimeout)
            .setSocketTimeout(connectionTimeout)
            .build();
        this.httpClient = HttpClientBuilder.create()
            .setDefaultRequestConfig(config)
            .addInterceptorLast(new ExtenderTracerInterceptor(tracer, propogator))
            .build();
    }


    @SuppressWarnings("unchecked")
    public String collectHealthReport(boolean isRemoteBuildEnabled, Map<String, RemoteInstanceConfig> remoteBuilderPlatformMappings) {
        if (isRemoteBuildEnabled) {
            // we collect information by platform. If one of the builder is unreachable - set status to "not fully operational"
            Map<String, OperationalStatus> platformOperationalStatus = new HashMap<>();
            JSONObject result = new JSONObject();
            JSONParser parser = new JSONParser();
            for (Map.Entry<String, RemoteInstanceConfig> entry : remoteBuilderPlatformMappings.entrySet()) {
                String platform = getPlatform(entry.getKey());
                String instanceId = entry.getValue().getInstanceId();
                // if instance controlled by instanceService and is currently suspended or suspending - mark it as 'operational'
                if (instanceService != null && instanceService.isInstanceControlled(instanceId)) {
                    if (instanceService.isInstanceSuspended(instanceId) || instanceService.isInstanceSuspending(instanceId)) {
                    updateOperationalStatus(platformOperationalStatus, platform, true);
                    continue;
                } else if (!instanceService.isInstanceRunning(instanceId)) {
                    updateOperationalStatus(platformOperationalStatus, platform, false);
                    continue;
                }
                final String healthUrl = String.format("%s/health_report", entry.getValue().getUrl());
                final HttpGet request = new HttpGet(healthUrl);
                try {
                    HttpResponse response = httpClient.execute(request);
                    if (response.getStatusLine().getStatusCode() == org.apache.http.HttpStatus.SC_OK) {
                        JSONObject responseBody = (JSONObject)parser.parse(EntityUtils.toString(response.getEntity()));
                        if (responseBody.containsKey("status") 
                            && OperationalStatus.valueOf((String)responseBody.get("status")) == OperationalStatus.Operational) {
                            updateOperationalStatus(platformOperationalStatus, platform, true);
                        } else {
                            updateOperationalStatus(platformOperationalStatus, platform, false);
                        }
                    } else {
                        updateOperationalStatus(platformOperationalStatus, platform, false);
                    }
                } catch(Exception exc) {
                    updateOperationalStatus(platformOperationalStatus, platform, false);
                }
            }

            for (Map.Entry<String, OperationalStatus> entry : platformOperationalStatus.entrySet()) {
                result.put(entry.getKey(), entry.getValue().toString());
            }
            return result.toJSONString();
        } else {
            return JSONObject.toJSONString(Collections.singletonMap("status", OperationalStatus.Operational.toString()));
        }
    }

    private String getPlatform(String hostId) {
        return hostId.split("-")[0];
    }

    private void updateOperationalStatus(Map<String, OperationalStatus> statuses, String platform, boolean isBuilderUp) {
        if (statuses.containsKey(platform)) {
            OperationalStatus prevStatus = (OperationalStatus)statuses.get(platform);
            if (isBuilderUp) {
                if (prevStatus == OperationalStatus.Unreachable) {
                    statuses.put(platform, OperationalStatus.NotFullyOperational);
                }
            } else {
                if (prevStatus == OperationalStatus.Operational) {
                    statuses.put(platform, OperationalStatus.NotFullyOperational);
                }
            }
        } else {
            statuses.put(platform, isBuilderUp ? OperationalStatus.Operational : OperationalStatus.Unreachable);
        }
    }
}

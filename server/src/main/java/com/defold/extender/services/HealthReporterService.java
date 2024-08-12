package com.defold.extender.services;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.util.EntityUtils;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class HealthReporterService {

    @Value("${extender.health-reporter.connection-timeout:1500}") int connectionTimeout;
    public enum OperationalStatus {
        Unreachable,
        NotFullyOperational,
        Operational
    }

    public HealthReporterService() {
    }

    @SuppressWarnings("unchecked")
    public String collectHealthReport(boolean isRemoteBuildEnabled, Map<String, String> remoteBuilderPlatformMappings) {
        if (isRemoteBuildEnabled) {
            // we collect information by platform. If one of the builder is unreachable - set
            Map<String, OperationalStatus> platformOperationalStatus = new HashMap<>();
            JSONObject result = new JSONObject();
            JSONParser parser = new JSONParser();
            RequestConfig config = RequestConfig.custom()
                .setConnectTimeout(connectionTimeout)
                .setConnectionRequestTimeout(connectionTimeout)
                .setSocketTimeout(connectionTimeout).build();
            final HttpClient client  = HttpClientBuilder.create().setDefaultRequestConfig(config).build();
            for (Map.Entry<String, String> entry : remoteBuilderPlatformMappings.entrySet()) {
                final String healthUrl = String.format("%s/health_report", entry.getValue());
                final HttpGet request = new HttpGet(healthUrl);
                String platform = getPlatform(entry.getKey());
                try {
                    HttpResponse response = client.execute(request);
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

package com.defold.extender.services;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.util.EntityUtils;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.defold.extender.remote.RemoteInstanceConfig;
import com.defold.extender.tracing.ExtenderTracerInterceptor;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;


@Service
public class HealthReporterService {

    @Value("${extender.health-reporter.connection-timeout:5000}") int connectionTimeout;
    public enum OperationalStatus {
        Unreachable,
        NotFullyOperational,
        Operational
    }

    private GCPInstanceService instanceService = null;
    private final HttpClient httpClient;
    private static final Logger LOGGER = LoggerFactory.getLogger(HealthReporterService.class);

    public HealthReporterService(Optional<GCPInstanceService> instanceService,
                            @Autowired Tracer tracer,
                            @Autowired Propagator propogator) {
        instanceService.ifPresent(val -> { this.instanceService = val; });
        this.httpClient = HttpClientBuilder.create()
            .addInterceptorLast(new ExtenderTracerInterceptor(tracer, propogator))
            .build();
    }


    @SuppressWarnings("unchecked")
    public String collectHealthReport(boolean isRemoteBuildEnabled, Map<String, RemoteInstanceConfig> remoteBuilderPlatformMappings) {
        if (isRemoteBuildEnabled) {
            // we collect information by platform. If one of the builder is unreachable - set status to "not fully operational"
            Map<String, OperationalStatus> platformOperationalStatus = new HashMap<>();
            JSONObject result = new JSONObject();
            Map<String, CompletableFuture<Boolean>> reportResults = new HashMap<>(remoteBuilderPlatformMappings.size());
            List<HttpGet> runningRequests = new ArrayList<>();
            for (Map.Entry<String, RemoteInstanceConfig> entry : remoteBuilderPlatformMappings.entrySet()) {
                String instanceId = entry.getValue().getInstanceId();
                String platform = getPlatform(entry.getKey());
                // if instance controlled by instanceService and is currently suspended or suspending - mark it as 'operational'
                if (instanceService != null && instanceService.isInstanceControlled(instanceId)) {
                    if (instanceService.isInstanceSuspended(instanceId)
                    || instanceService.isInstanceSuspending(instanceId)
                    || instanceService.isInstanceStarting(instanceId)) {
                        updateOperationalStatus(platformOperationalStatus, platform, true);
                        continue;
                    } else if (!instanceService.isInstanceRunning(instanceId)) {
                        updateOperationalStatus(platformOperationalStatus, platform, false);
                        continue;
                    }
                }

                // if instance is not located in GCP - make http request to it asynchronously
                final String healthUrl = String.format("%s/health_report", entry.getValue().getUrl());
                final HttpGet request = new HttpGet(healthUrl);
                runningRequests.add(request);
                CompletableFuture<Boolean> innerRequest = CompletableFuture.supplyAsync(() -> {
                    JSONParser parser = new JSONParser();
                    try {
                        HttpResponse response = httpClient.execute(request);
                        if (response.getStatusLine().getStatusCode() == org.apache.http.HttpStatus.SC_OK) {
                            JSONObject responseBody = (JSONObject)parser.parse(EntityUtils.toString(response.getEntity()));
                            if (responseBody.containsKey("status")
                                && OperationalStatus.valueOf((String)responseBody.get("status")) == OperationalStatus.Operational) {
                                    return Boolean.TRUE;
                            } else {
                                return Boolean.FALSE;
                            }
                        } else {
                            EntityUtils.consumeQuietly(response.getEntity());
                            return Boolean.FALSE;
                        }
                    } catch(Exception exc) {
                        return Boolean.FALSE;
                    }
                }).completeOnTimeout(Boolean.FALSE, this.connectionTimeout, TimeUnit.MILLISECONDS);
                reportResults.put(entry.getKey(), innerRequest);
            }
            for (Map.Entry<String, CompletableFuture<Boolean>> status : reportResults.entrySet()) {
                String platform = getPlatform(status.getKey());
                Boolean isInstanceReachable = false;
                try {
                    isInstanceReachable = status.getValue().get();
                } catch(InterruptedException|ExecutionException exc) {
                    LOGGER.error("Error during request health status.", exc);
                }
                updateOperationalStatus(platformOperationalStatus, platform, isInstanceReachable);
            }

            // cleanup all stuck requests if any
            for (HttpGet request : runningRequests) {
                try {
                    request.abort();
                } catch (Exception exc) {
                    LOGGER.warn("Request abort was unsuccessful for {}", request.getURI().toString());
                }
            }
            runningRequests.clear();

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

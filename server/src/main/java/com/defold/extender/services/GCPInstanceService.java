package com.defold.extender.services;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.google.cloud.compute.v1.InstancesClient;
import com.google.cloud.compute.v1.Operation;
import com.defold.extender.remote.RemoteHostConfiguration;
import com.defold.extender.remote.RemoteInstanceConfig;
import com.defold.extender.services.data.GCPInstanceState;
import com.google.cloud.compute.v1.Instance.Status;

@Service
@ConditionalOnProperty(prefix = "extender", name = "gcp.controller.enabled", havingValue = "true")
public class GCPInstanceService {

    private static final Logger LOGGER = LoggerFactory.getLogger(GCPInstanceService.class);

    private InstancesClient instancesClient = null;
    @Value("${extender.gcp.controller.project_id}") private String projectId;
    @Value("${extender.gcp.controller.zone}") private String computeZone;
    @Value("${extender.gcp.controller.wait-timeout:45000}") private long operationWaitTimeout;
    @Value("${extender.gcp.controller.retry-attempts:3}") private int retryAttempts;
    @Value("${extender.gcp.controller.retry-timeout:10000}") private long retryTimeout;
    @Value("${extender.gcp.controller.time-before-suspend:1800000}") private long timeBeforeSuspend;
    @Value("${extender.gcp.controller.wait-startup-time:10000}") private long waitStartupTime;

    private Map<String, GCPInstanceState> instanceState = new HashMap<>();

    public GCPInstanceService(RemoteHostConfiguration remoteHostConfiguration) throws IOException{
        instancesClient = InstancesClient.create();
        for (Map.Entry<String, RemoteInstanceConfig> entry : remoteHostConfiguration.getPlatforms().entrySet()) {
            if (!entry.getValue().getAlwaysOn()) {
                instanceState.put(entry.getValue().getInstanceId(), new GCPInstanceState());
            }
        }
    }

    private void suspendInstance(final String instanceId) throws InterruptedException, ExecutionException, TimeoutException {
        LOGGER.info(String.format("Try suspend VM '%s'", instanceId));
        int attemptCount = 0;
        while (true) {
            Operation response = instancesClient.suspendAsync(projectId, computeZone, instanceId).get(operationWaitTimeout, TimeUnit.MILLISECONDS);
            if (response.hasError() || !getInstanceStatus(instanceId).equalsIgnoreCase(Status.SUSPENDED.toString())) {
                attemptCount++;
                if (attemptCount >= retryAttempts) {
                    throw new TimeoutException("Run out of attempt count. VM not suspended.");
                }
                LOGGER.warn(String.format("VM '%s' not suspended. Attempt %d from %d", instanceId, attemptCount, retryAttempts));
                Thread.sleep(retryTimeout);
            } else {
                LOGGER.info(String.format("VM '%s' was suspended", instanceId));
                break;
            }
        }
    }

    private void resumeInstance(String instanceId) throws InterruptedException, ExecutionException, TimeoutException {
        LOGGER.info(String.format("Try resume VM '%s'", instanceId));
        int attemptCount = 0;
        while (true) {
            Operation response = instancesClient.resumeAsync(projectId, computeZone, instanceId).get(operationWaitTimeout, TimeUnit.MILLISECONDS);
            if (response.hasError() || !getInstanceStatus(instanceId).equalsIgnoreCase(Status.RUNNING.toString())) {
                attemptCount++;
                if (attemptCount >= retryAttempts) {
                    throw new TimeoutException("Run out of attempt count. VM not resumed.");
                }
                LOGGER.warn(String.format("VM '%s' not resumed. Attempt %d from %d", instanceId, attemptCount, retryAttempts));
                Thread.sleep(retryTimeout);
            } else {
                LOGGER.info(String.format("VM '%s' was resumed. Wait %ds before return.", instanceId, waitStartupTime / 1000));
                Thread.sleep(waitStartupTime);
                instanceState.get(instanceId).lastTimeTouched = System.currentTimeMillis();
                break;
            }
        }
    }

    public String getInstanceStatus(final String instanceId) {
        return instancesClient.get(projectId, computeZone, instanceId).getStatus();
    }

    public void touchInstance(String instanceId) throws InterruptedException, ExecutionException, TimeoutException {
        // if we instance was marked as alwaysOn we skip adding it during initialization
        if (!instanceState.containsKey(instanceId)) {
            return;
        }
        // update last touched time to prevent suspending
        instanceState.get(instanceId).lastTimeTouched = System.currentTimeMillis();
        String instanceStatus = getInstanceStatus(instanceId);
        LOGGER.info(String.format("Current instance '%s' status '%s'", instanceId, instanceStatus));
        //! TODO: think how to handle other instance states. Especially SUSPENDING state (when suspend request send but not handled)
        if (instanceStatus.equalsIgnoreCase(Status.SUSPENDED.toString())) {
            resumeInstance(instanceId);
        }
        //  Status.SUSPENDING
    }

    @Scheduled(initialDelayString="${extender.gcp.controller.check-period:60000}", fixedDelayString="${extender.gcp.controller.check-period:60000}")
    public void checkInstancesState() {
        for (Map.Entry<String, GCPInstanceState> entry : instanceState.entrySet()) {
            if (getInstanceStatus(entry.getKey()).equalsIgnoreCase(Status.RUNNING.toString()) 
                && entry.getValue().lastTimeTouched + timeBeforeSuspend <= System.currentTimeMillis()) {
                    try {
                        suspendInstance(entry.getKey());
                    } catch(TimeoutException exc) {
                        LOGGER.error(String.format("Suspend '%s' timeouted.", entry.getKey()), exc);
                    } catch(InterruptedException exc) {
                        LOGGER.error(String.format("Suspend '%s' interrupted.", entry.getKey()), exc);
                    } catch(ExecutionException exc) {
                        LOGGER.error(String.format("Suspend '%s' failed.", entry.getKey()), exc);
                    }
            }
        }
    }
}

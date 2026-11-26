package com.defold.extender.remote;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

/**
 * Controls the lifecycle of remote builder instances that can be suspended when idle and
 * woken on demand. Instance ids are {@link RemoteInstanceConfig#getInstanceId()} values.
 *
 * <p>{@code GCPInstanceService} implements this for GCE VMs. Other implementations (e.g. one
 * that scales Kubernetes builders from zero) can be provided as a Spring bean, typically from
 * an auto-configuration in a jar added via {@code loader.path}.
 */
public interface RemoteInstanceController {

    /** Whether the instance is managed by this controller (instances marked alwaysOn are not). */
    boolean isInstanceControlled(String instanceId);

    /**
     * Records activity for the instance and, if it is suspended, wakes it and blocks until it is
     * ready. Called before every request a build sends to the instance, so it doubles as a heartbeat.
     */
    void touchInstance(String instanceId) throws InterruptedException, ExecutionException, TimeoutException;

    boolean isInstanceRunning(String instanceId);

    boolean isInstanceSuspended(String instanceId);

    boolean isInstanceSuspending(String instanceId);

    boolean isInstanceStarting(String instanceId);
}

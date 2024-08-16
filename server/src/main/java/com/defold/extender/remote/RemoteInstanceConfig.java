package com.defold.extender.remote;

public class RemoteInstanceConfig {
    private String url;
    private String instanceId;
    private boolean alwaysOn;

    public RemoteInstanceConfig(String url, String instanceId, boolean alwaysOn) {
        this.url = url;
        this.instanceId = instanceId;
        this.alwaysOn = alwaysOn;
    }

    public String getUrl() {
        return url;
    }

    public String getInstanceId() {
        return instanceId;
    }

    public boolean getAlwaysOn() {
        return alwaysOn;
    }
}

package com.defold.extender.remote;

public class RemoteInstanceConfig {
    private String url;
    private String instance;

    public RemoteInstanceConfig(String id, String url) {
        this.instance = id;
        this.url = url;
    }

    public String getUrl() {
        return url;
    }

    public String getInstance() {
        return instance;
    }

    public void setInstance(String instance) {
        this.instance = instance;
    }

    public void setUrl(String url) {
        this.url = url;
    }
}

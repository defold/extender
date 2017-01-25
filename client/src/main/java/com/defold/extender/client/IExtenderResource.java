package com.defold.extender.client;

import java.io.IOException;

public interface IExtenderResource {

    byte[] sha1() throws IOException;

    String getAbsPath();

    String getPath();

    byte[] getContent() throws IOException;

    long getLastModified();

}

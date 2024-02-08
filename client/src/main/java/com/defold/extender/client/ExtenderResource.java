package com.defold.extender.client;

import java.io.IOException;

public interface ExtenderResource {

    String getPath();

    byte[] getContent() throws IOException;

    long getLastModified();

}

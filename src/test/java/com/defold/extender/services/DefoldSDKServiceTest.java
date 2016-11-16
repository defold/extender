package com.defold.extender.services;

import org.junit.Ignore;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;

public class DefoldSDKServiceTest {

    @Test
    @Ignore("SDK too large to download on every test round.")
    public void t() throws IOException, URISyntaxException {
        DefoldSdkService defoldSdkService = new DefoldSdkService("/tmp/defoldsdk");
        File sdk = defoldSdkService.getSdk("d420fc812558aa30f592907cf57a87d24c5c7569");
        System.out.println(sdk.getCanonicalFile());
    }
}

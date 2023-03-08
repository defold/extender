package com.defold.extender.services;

import com.defold.extender.ExtenderException;

import org.junit.Ignore;
import org.junit.Test;
import io.micrometer.core.instrument.MeterRegistry;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;

public class DefoldSDKServiceTest {

    @Test
    @Ignore("SDK too large to download on every test round.")
    public void t() throws IOException, URISyntaxException, ExtenderException {
        DefoldSdkService defoldSdkService = new DefoldSdkService("/tmp/defoldsdk", 3, true, mock(MeterRegistry.class));
        File sdk = defoldSdkService.getSdk("f7778a8f59ef2a8dda5d445f471368e8bd1cb1ac");
        System.out.println(sdk.getCanonicalFile());
    }

    @Test
    @Ignore("SDK too large to download on every test round.")
    public void onlyStoreTheNewest() throws IOException, URISyntaxException, ExtenderException {
        int cacheSize = 3;
        DefoldSdkService defoldSdkService = new DefoldSdkService("/tmp/defoldsdk", cacheSize, true, mock(MeterRegistry.class));

        String[] sdksToDownload = {
                "fe2b689302e79b7cf8c0bc7d934f23587b268c8a",
                "8f3e864464062e1b35c207521dc65dfd77899cdf",
                "e41438cca6cc1550d4a0131b8fc3858c2a4097f1",
                "7107bc8781535e83cbb30734b32d6b32a3039cd0",
                "f7778a8f59ef2a8dda5d445f471368e8bd1cb1ac"};

        // Download all SDK:s
        for (String sdkHash : sdksToDownload) {
            defoldSdkService.getSdk(sdkHash);
        }

        List<String> collect = Files.list(Paths.get("/tmp/defoldsdk")).map(path -> path.toFile().getName()).collect(Collectors.toList());

        assertEquals(cacheSize, collect.size());
        assertTrue(collect.contains("e41438cca6cc1550d4a0131b8fc3858c2a4097f1"));
        assertTrue(collect.contains("7107bc8781535e83cbb30734b32d6b32a3039cd0"));
        assertTrue(collect.contains("f7778a8f59ef2a8dda5d445f471368e8bd1cb1ac"));
    }

    @Test
    public void testGetSDK() throws IOException, URISyntaxException, ExtenderException {
        DefoldSdkService defoldSdkService = new DefoldSdkService("/tmp/defoldsdk", 3, true, mock(MeterRegistry.class));

        File dir = new File("/tmp/defoldsdk/notexist");
        assertFalse(Files.exists(dir.toPath()));

        {
            boolean thrown = false;
            try {
                defoldSdkService.getSdk("notexist");
            } catch (ExtenderException e) {
                thrown = true;
            }
            assertTrue(thrown);
        }
    }
}

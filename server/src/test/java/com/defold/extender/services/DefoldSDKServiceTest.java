package com.defold.extender.services;

import org.junit.Ignore;
import org.junit.Test;
import org.springframework.boot.actuate.metrics.CounterService;
import org.springframework.boot.actuate.metrics.GaugeService;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;

public class DefoldSDKServiceTest {

    @Test
    @Ignore("SDK too large to download on every test round.")
    public void t() throws IOException, URISyntaxException {
        DefoldSdkService defoldSdkService = new DefoldSdkService("/tmp/defoldsdk", 3, mock(CounterService.class), mock(GaugeService.class));
        File sdk = defoldSdkService.getSdk("d420fc812558aa30f592907cf57a87d24c5c7569");
        System.out.println(sdk.getCanonicalFile());
    }

    @Test
    @Ignore("SDK too large to download on every test round.")
    public void onlyStoreTheNewest() throws IOException, URISyntaxException {
        int cacheSize = 3;
        DefoldSdkService defoldSdkService = new DefoldSdkService("/tmp/defoldsdk", cacheSize, mock(CounterService.class), mock(GaugeService.class));

        String[] sdksToDownload = {
                "9a3c683d14e3b3e375fc494e83a32523d550771b",
                "a80fb493adfee373f9ce1a4a2e83eae6522e09e7",
                "b84ad18944f11460513c4e624428b3d299e2540e",
                "bfe93c1d3c17aba433676caef9ae119c3580fd00",
                "65074af87ccb9276d24279b8bd0d898d4ce21a1f"};

        // Download all SDK:s
        for (String sdkHash : sdksToDownload) {
            defoldSdkService.getSdk(sdkHash);
        }

        List<String> collect = Files.list(Paths.get("/tmp/defoldsdk")).map(path -> path.toFile().getName()).collect(Collectors.toList());

        assertEquals(cacheSize, collect.size());
        assertTrue(collect.contains("b84ad18944f11460513c4e624428b3d299e2540e"));
        assertTrue(collect.contains("bfe93c1d3c17aba433676caef9ae119c3580fd00"));
        assertTrue(collect.contains("65074af87ccb9276d24279b8bd0d898d4ce21a1f"));
    }
}

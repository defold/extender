package com.defold.extender.services.spm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.defold.extender.services.spm.SwiftPackageManagerService.PrefetchResult;

public class SpmPrefetchFailureTest {

    private static String curlOutput(String stderr, String status) {
        return stderr + "\n" + SwiftPackageManagerService.HTTP_STATUS_PREFIX + status + "\n";
    }

    @Test
    public void readsTheStatusCurlWroteOut() {
        assertEquals(404, SwiftPackageManagerService.httpStatus(
            curlOutput("curl: (56) The requested URL returned error: 404", "404")));
        assertEquals(0, SwiftPackageManagerService.httpStatus(curlOutput("", "000")));
        assertEquals(-1, SwiftPackageManagerService.httpStatus("curl: (6) Could not resolve host: example.invalid"));
        assertEquals(-1, SwiftPackageManagerService.httpStatus(null));
    }

    @Test
    public void theStatusLineStaysOutOfTheMessage() {
        assertEquals("curl: (56) The requested URL returned error: 404", SwiftPackageManagerService.withoutHttpStatus(
            curlOutput("curl: (56) The requested URL returned error: 404", "404")));
    }

    @Test
    public void probesTheSmartHttpEndpointOfTheRepository() {
        assertEquals("https://github.com/acme/Pkg.git/info/refs?service=git-upload-pack",
            SwiftPackageManagerService.infoRefsUrl("https://github.com/acme/Pkg.git"));
        assertEquals("https://github.com/acme/Pkg/info/refs?service=git-upload-pack",
            SwiftPackageManagerService.infoRefsUrl("https://github.com/acme/Pkg/"));
    }

    @Test
    public void aRepositoryTheProbeCannotAskAboutIsNotMissing() {
        assertFalse(SwiftPackageManagerService.repositoryIsMissing("https://no-such-host.invalid/acme/Pkg.git"));
        assertFalse(SwiftPackageManagerService.repositoryIsMissing("http://github.com/acme/Pkg.git"));
        assertFalse(SwiftPackageManagerService.repositoryIsMissing("git@github.com:acme/Pkg.git"));
    }

    @Test
    public void anArchiveTheUrlDoesNotServeIsTheManifestsFault() {
        // curl 8.7 exits with 56, not 22, for an HTTP/2 error answer under --fail
        assertTrue(SwiftPackageManagerService.archiveIsUnavailable(
            new PrefetchResult(56, curlOutput("curl: (56) The requested URL returned error: 404", "404"))));
        assertTrue(SwiftPackageManagerService.archiveIsUnavailable(
            new PrefetchResult(22, curlOutput("curl: (22) The requested URL returned error: 410", "410"))));
        assertTrue(SwiftPackageManagerService.archiveIsUnavailable(
            new PrefetchResult(SwiftPackageManagerService.CURL_FILE_TOO_LARGE,
                curlOutput("curl: (63) Exceeded the maximum allowed file size", "200"))));
    }

    @Test
    public void anArchiveTheServerCouldNotDeliverIsAServerFault() {
        assertFalse(SwiftPackageManagerService.archiveIsUnavailable(
            new PrefetchResult(22, curlOutput("curl: (22) The requested URL returned error: 503", "503"))));
        // forges answer rate limited clients with 403 as well
        assertFalse(SwiftPackageManagerService.archiveIsUnavailable(
            new PrefetchResult(22, curlOutput("curl: (22) The requested URL returned error: 403", "403"))));
        assertFalse(SwiftPackageManagerService.archiveIsUnavailable(
            new PrefetchResult(6, curlOutput("curl: (6) Could not resolve host: example.invalid", "000"))));
        assertFalse(SwiftPackageManagerService.archiveIsUnavailable(new PrefetchResult(-1, "timed out")));
    }
}

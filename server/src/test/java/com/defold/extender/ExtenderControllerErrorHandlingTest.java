package com.defold.extender;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.File;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.api.parallel.Isolated;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.defold.extender.log.Markers;
import com.defold.extender.progress.BuildProgressService;
import com.defold.extender.remote.RemoteEngineBuilder;
import com.defold.extender.remote.RemoteHostConfiguration;
import com.defold.extender.services.DataCacheService;
import com.defold.extender.services.DefoldSdkService;
import com.defold.extender.services.HealthReporterService;
import com.defold.extender.services.UserUpdateService;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

// Isolated: the appender sees every event of the shared ExtenderController logger
@Isolated
@Execution(ExecutionMode.SAME_THREAD)
public class ExtenderControllerErrorHandlingTest {
    @TempDir
    File jobResultLocation;

    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private Logger logger;
    private DataCacheService dataCacheService;
    private MockMvc mockMvc;

    @BeforeEach
    public void setUp() {
        logger = (Logger) LoggerFactory.getLogger(ExtenderController.class);
        appender.start();
        logger.addAppender(appender);

        dataCacheService = mock(DataCacheService.class);
        ExtenderController controller = new ExtenderController(
                mock(DefoldSdkService.class),
                dataCacheService,
                mock(UserUpdateService.class),
                new SimpleMeterRegistry(),
                mock(AsyncBuilder.class),
                mock(RemoteEngineBuilder.class),
                mock(RemoteHostConfiguration.class),
                mock(HealthReporterService.class),
                mock(BuildProgressService.class),
                false,
                "1024mb",
                jobResultLocation.getAbsolutePath());
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @AfterEach
    public void tearDown() {
        logger.detachAppender(appender);
    }

    private ILoggingEvent lastEvent() {
        assertTrue(!appender.list.isEmpty());
        return appender.list.get(appender.list.size() - 1);
    }

    private MockMultipartFile someUpload() {
        return new MockMultipartFile("main.cpp", "main.cpp", "text/plain", "int main() {}".getBytes());
    }

    @Test
    public void invalidRequestIsAClientErrorWithoutStackTrace() throws Exception {
        mockMvc.perform(post("/build_async/x86_64-linux/abc"))
                .andExpect(status().isUnprocessableEntity());

        ILoggingEvent event = lastEvent();
        assertEquals(Level.WARN, event.getLevel());
        assertTrue(event.getMarkerList().contains(Markers.CLIENT_ERROR));
        assertNull(event.getThrowableProxy());
    }

    @Test
    public void invalidJobIdIsAClientError() throws Exception {
        mockMvc.perform(get("/job_status").param("jobId", "../../etc"))
                .andExpect(status().isUnprocessableEntity());

        assertEquals(Level.WARN, lastEvent().getLevel());
    }

    @Test
    public void unsupportedPlatformIsAClientErrorWithoutStackTrace() throws Exception {
        mockMvc.perform(multipart("/build_async/NOT-A-PLATFORM!/abc").file(someUpload()))
                .andExpect(status().isNotImplemented());

        ILoggingEvent event = lastEvent();
        assertEquals(Level.WARN, event.getLevel());
        assertTrue(event.getMarkerList().contains(Markers.CLIENT_ERROR));
        assertNull(event.getThrowableProxy());
    }

    @Test
    public void serverSideExtenderExceptionIsAServerErrorWithStackTrace() throws Exception {
        when(dataCacheService.getCachedFiles(any(File.class))).thenThrow(new ExtenderException("Failed downloading from cache"));

        mockMvc.perform(multipart("/build_async/x86_64-linux/abc").file(someUpload()))
                .andExpect(status().isInternalServerError());

        ILoggingEvent event = lastEvent();
        assertEquals(Level.ERROR, event.getLevel());
        assertTrue(event.getMarkerList().contains(Markers.SERVER_ERROR));
        assertNotNull(event.getThrowableProxy());
    }
}

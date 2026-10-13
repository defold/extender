package com.defold.extender;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;

import org.eclipse.jetty.io.EofException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.api.parallel.Isolated;
import org.slf4j.LoggerFactory;

import com.defold.extender.log.Markers;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

// Isolated: the appender sees every event of the shared AsyncBuilder logger
@Isolated
@Execution(ExecutionMode.SAME_THREAD)
public class AsyncBuilderFailureReportTest {
    private Logger logger;
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    @BeforeEach
    public void attach() {
        logger = (Logger) LoggerFactory.getLogger(AsyncBuilder.class);
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    public void detach() {
        logger.detachAppender(appender);
    }

    private ILoggingEvent onlyEvent() {
        assertEquals(1, appender.list.size());
        return appender.list.get(0);
    }

    @Test
    public void userBuildFailureIsLoggedAsInfoWithoutStackTrace() {
        BuildFailure kind = AsyncBuilder.reportFailure(
                new BuildFailedException("main.cpp:3: error: expected ';'"), "job123", "arm64-android", "abc");

        assertEquals(BuildFailure.USER, kind);
        ILoggingEvent event = onlyEvent();
        assertEquals(Level.INFO, event.getLevel());
        assertTrue(event.getMarkerList().contains(Markers.BUILD_ERROR));
        assertNull(event.getThrowableProxy());
        assertTrue(event.getFormattedMessage().contains("job123"));
        assertTrue(event.getFormattedMessage().contains("main.cpp:3: error: expected ';'"));
    }

    @Test
    public void userBuildFailureLogKeepsOnlyTheTailOfLongOutput() {
        String output = "x".repeat(10_000) + "the actual error";

        AsyncBuilder.reportFailure(new BuildFailedException(output), "job", "x86_64-linux", "abc");

        String message = onlyEvent().getFormattedMessage();
        assertTrue(message.contains("the actual error"));
        assertTrue(message.length() < 3_000);
    }

    @Test
    public void clientDisconnectIsLoggedAsWarningWithoutStackTrace() {
        BuildFailure kind = AsyncBuilder.reportFailure(new EofException("closed"), "job", "x86_64-linux", "abc");

        assertEquals(BuildFailure.ABORTED, kind);
        ILoggingEvent event = onlyEvent();
        assertEquals(Level.WARN, event.getLevel());
        assertTrue(event.getMarkerList().contains(Markers.CLIENT_ERROR));
        assertNull(event.getThrowableProxy());
    }

    @Test
    public void serverFailureKeepsErrorLevelAndStackTrace() {
        BuildFailure kind = AsyncBuilder.reportFailure(new IOException("disk full"), "job", "x86_64-linux", "abc");

        assertEquals(BuildFailure.SERVER, kind);
        ILoggingEvent event = onlyEvent();
        assertEquals(Level.ERROR, event.getLevel());
        assertTrue(event.getMarkerList().contains(Markers.SERVER_ERROR));
        assertNotNull(event.getThrowableProxy());
    }

    @Test
    public void plainExtenderExceptionIsAServerFailure() {
        BuildFailure kind = AsyncBuilder.reportFailure(
                new ExtenderException("Regex failed to find any engine jars"), "job", "armv7-android", "abc");

        assertEquals(BuildFailure.SERVER, kind);
        assertFalse(onlyEvent().getMarkerList().contains(Markers.BUILD_ERROR));
    }
}

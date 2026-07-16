package com.defold.extender.remote;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;

import org.junit.jupiter.api.Test;

import com.defold.extender.ExtenderException;

public class RemoteBuildExceptionTest {

    @Test
    public void theMessageOnlyConstructorLeavesTheCauseUnset() {
        RemoteBuildException exception = new RemoteBuildException("Failed to add files to multipart request");

        assertEquals("Failed to add files to multipart request", exception.getMessage());
        assertNull(exception.getCause());
    }

    @Test
    public void theCauseIsPreservedSoTheUnderlyingIoFailureStaysDiagnosable() {
        ExtenderException cause = new ExtenderException("Failed to create source code archive");
        RemoteBuildException exception = new RemoteBuildException("Failed to add files to multipart request", cause);

        assertEquals("Failed to add files to multipart request", exception.getMessage());
        assertSame(cause, exception.getCause());
    }

    @Test
    public void itIsUncheckedSoBuildAsyncCanThrowItWithoutDeclaringIt() {
        // buildAsync only declares FileNotFoundException and IOException. If RemoteBuildException
        // ever became checked, the multipart failure path would stop compiling.
        assertTrue(RuntimeException.class.isAssignableFrom(RemoteBuildException.class));
    }

    @Test
    public void aCheckedCauseIsAcceptedByTheTwoArgumentConstructor() {
        IOException cause = new IOException("disk full");

        assertSame(cause, new RemoteBuildException("Failed to add files to multipart request", cause).getCause());
    }
}

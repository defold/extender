package com.defold.extender;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

public class ExtenderExceptionTest {

    @Test
    public void hugeOutputIsKeptButMessageIsBounded() {
        String output = "first line\n" + "z".repeat(5_000_000) + "\nlast line";
        ExtenderException e = new ExtenderException(output);

        assertEquals(output, e.getOutput());
        assertTrue(e.getMessage().length() <= ExtenderException.MAX_MESSAGE_LENGTH);
        assertTrue(e.getMessage().startsWith("first line"));
        assertTrue(e.getMessage().endsWith("last line"));
    }

    @Test
    public void shortOutputIsTheMessage() {
        ExtenderException e = new ExtenderException("Only one app.manifest allowed!");
        assertEquals("Only one app.manifest allowed!", e.getMessage());
    }
}

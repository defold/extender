package com.defold.extender.process;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import org.junit.jupiter.api.Test;

public class CommandLineTokenizerTest {
    @Test
    public void testParseIgnoresRepeatedWhitespace() {
        assertIterableEquals(
            List.of("clang", "-c", "file.m"),
            CommandLineTokenizer.parse("clang   -c\tfile.m")
        );
    }

    @Test
    public void testParseEscapedWhitespace() {
        assertIterableEquals(
            List.of("clang", "-DCLS_SDK_NAME=Crashlytics SDK iOS", "file.m"),
            CommandLineTokenizer.parse("clang -DCLS_SDK_NAME=Crashlytics\\ SDK\\ iOS file.m")
        );
    }

    @Test
    public void testParseQuotes() {
        assertIterableEquals(
            List.of("clang", "path with spaces/file.m"),
            CommandLineTokenizer.parse("clang \"path with spaces/file.m\"")
        );
    }

    @Test
    public void testParsePrefixQuotes() {
        assertIterableEquals(
            List.of("clang", "-Ipath with spaces"),
            CommandLineTokenizer.parse("clang -I\"path with spaces\"")
        );
    }

    @Test
    public void testParseQuotedDefineValue() {
        assertIterableEquals(
            List.of("clang", "-DDLIB_LOG_DOMAIN=\"FIREBASEEXT\""),
            CommandLineTokenizer.parse("clang -DDLIB_LOG_DOMAIN=\"FIREBASEEXT\"")
        );
    }

    @Test
    public void testParseQuotedDefineValueWithWhitespace() {
        assertIterableEquals(
            List.of("clang", "-DCLS_SDK_NAME=\"Crashlytics SDK iOS\""),
            CommandLineTokenizer.parse("clang -DCLS_SDK_NAME=\"Crashlytics SDK iOS\"")
        );
    }

    @Test
    public void testParseEscapedQuotes() {
        assertIterableEquals(
            List.of("clang", "-DNAME=\"Crashlytics\""),
            CommandLineTokenizer.parse("clang -DNAME=\\\"Crashlytics\\\"")
        );
    }

    @Test
    public void testParsePreservesQuotedValuesInsideAssignmentLists() {
        assertIterableEquals(
            List.of("em++", "-s", "EXPORTED_RUNTIME_METHODS=[\"ccall\",\"callMain\",\"UTF8ToString\"]"),
            CommandLineTokenizer.parse("em++ -s EXPORTED_RUNTIME_METHODS=[\"ccall\",\"callMain\",\"UTF8ToString\"]")
        );
    }

    @Test
    public void testPreserveEscapedWhitespace() {
        assertIterableEquals(
            List.of("CLS_SDK_NAME=Crashlytics\\ SDK\\ iOS"),
            CommandLineTokenizer.splitPreservingEscapedWhitespace("CLS_SDK_NAME=Crashlytics\\ SDK\\ iOS")
        );
    }

    @Test
    public void testPreserveQuotedWhitespaceAsEscapedWhitespace() {
        assertIterableEquals(
            List.of("path\\ with\\ spaces/file.m"),
            CommandLineTokenizer.splitPreservingEscapedWhitespace("\"path with spaces/file.m\"")
        );
    }

    @Test
    public void testEscapeWhitespacePreservesExistingEscapes() {
        assertEquals(
            "Crashlytics\\ SDK\\ iOS",
            CommandLineTokenizer.escapeWhitespace("Crashlytics\\ SDK iOS")
        );
    }

    @Test
    public void testDanglingEscapeThrows() {
        assertThrows(IllegalArgumentException.class, () -> CommandLineTokenizer.parse("clang file\\"));
    }

    @Test
    public void testUnclosedQuoteThrows() {
        assertThrows(IllegalArgumentException.class, () -> CommandLineTokenizer.parse("clang \"file"));
    }
}

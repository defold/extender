package com.defold.extender.services;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

// The type sets are what gradle-failure-report.gradle recorded with Gradle 9.9.1, minus java.lang.*
public class GradleFailureReportTest {
    private static final Set<String> MODULE_NOT_FOUND = Set.of(
        "org.gradle.api.GradleException",
        "org.gradle.internal.exceptions.DefaultMultiCauseException",
        "org.gradle.internal.resolve.ModuleVersionNotFoundException",
        "org.gradle.internal.resolve.ModuleVersionResolveException");

    private static final Set<String> NO_MATCHING_VARIANT = Set.of(
        "org.gradle.api.GradleException",
        "org.gradle.internal.component.resolution.failure.exception.AbstractResolutionFailureException",
        "org.gradle.internal.component.resolution.failure.exception.VariantSelectionByAttributesException",
        "org.gradle.internal.resolve.ModuleVersionResolveException");

    private static final Set<String> VERSION_CONFLICT = Set.of(
        "org.gradle.api.GradleException",
        "org.gradle.internal.component.resolution.failure.exception.AbstractResolutionFailureException",
        "org.gradle.internal.component.resolution.failure.exception.ComponentSelectionException",
        "org.gradle.internal.resolve.ModuleVersionResolveException");

    private static final Set<String> REPOSITORY_UNREACHABLE = Set.of(
        "java.io.IOException",
        "java.net.ConnectException",
        "java.net.SocketException",
        "org.gradle.api.GradleException",
        "org.gradle.api.resources.ResourceException",
        "org.gradle.internal.resolve.ModuleVersionResolveException");

    // compile 'group:name:version' in an extension's dependencies block
    private static final Set<String> UNKNOWN_DSL_METHOD = Set.of(
        "groovy.lang.GroovyRuntimeException",
        "groovy.lang.MissingMethodException",
        "org.gradle.api.GradleException",
        "org.gradle.api.GradleScriptException",
        "org.gradle.api.ProjectConfigurationException",
        "org.gradle.internal.metaobject.AbstractDynamicObject$CustomMessageMissingMethodException");

    private static final Set<String> SCRIPT_SYNTAX_ERROR = Set.of(
        "org.codehaus.groovy.GroovyException",
        "org.codehaus.groovy.control.CompilationFailedException",
        "org.codehaus.groovy.control.MultipleCompilationErrorsException",
        "org.gradle.api.GradleException",
        "org.gradle.api.ProjectConfigurationException",
        "org.gradle.groovy.scripts.ScriptCompilationException");

    // the Android Gradle plugin could not be downloaded for the build script classpath
    private static final Set<String> BUILD_SCRIPT_CLASSPATH_UNREACHABLE = Set.of(
        "java.io.IOException",
        "java.net.ConnectException",
        "org.gradle.api.GradleException",
        "org.gradle.api.ProjectConfigurationException",
        "org.gradle.api.internal.artifacts.ivyservice.TypedResolveException",
        "org.gradle.api.resources.ResourceException",
        "org.gradle.internal.resolve.ModuleVersionResolveException");

    @Test
    public void projectErrorsAreUserErrors() {
        assertTrue(GradleFailureReport.isUserError(List.of(MODULE_NOT_FOUND)));
        assertTrue(GradleFailureReport.isUserError(List.of(NO_MATCHING_VARIANT)));
        assertTrue(GradleFailureReport.isUserError(List.of(VERSION_CONFLICT, VERSION_CONFLICT)));
        assertTrue(GradleFailureReport.isUserError(List.of(UNKNOWN_DSL_METHOD)));
        assertTrue(GradleFailureReport.isUserError(List.of(SCRIPT_SYNTAX_ERROR)));
    }

    @Test
    public void unreachableRepositoriesAreServerErrors() {
        assertFalse(GradleFailureReport.isUserError(List.of(REPOSITORY_UNREACHABLE)));
        assertFalse(GradleFailureReport.isUserError(List.of(BUILD_SCRIPT_CLASSPATH_UNREACHABLE)));
    }

    @Test
    public void anOutageNextToAMissingModuleIsAServerError() {
        assertFalse(GradleFailureReport.isUserError(List.of(MODULE_NOT_FOUND, REPOSITORY_UNREACHABLE)));
    }

    @Test
    public void unknownFailuresAreServerErrors() {
        assertFalse(GradleFailureReport.isUserError(List.of(Set.of("org.gradle.api.GradleException"))));
        assertFalse(GradleFailureReport.isUserError(List.of()));
    }

    @Test
    public void readsTheReportTheInitScriptWrites(@TempDir Path dir) throws Exception {
        Path report = Files.writeString(dir.resolve("gradle-failures.json"),
            "[{\"subject\":\"com.example:missing:1.0\",\"types\":["
            + "\"org.gradle.internal.resolve.ModuleVersionNotFoundException\","
            + "\"org.gradle.internal.resolve.ModuleVersionResolveException\"]}]");
        assertTrue(GradleFailureReport.isUserError(report.toFile()));

        Files.writeString(report, "[{\"subject\":\"com.example:any:1.0\",\"types\":["
            + "\"org.gradle.internal.resolve.ModuleVersionNotFoundException\","
            + "\"org.gradle.api.resources.ResourceException\"]}]");
        assertFalse(GradleFailureReport.isUserError(report.toFile()));
    }

    @Test
    public void aMissingOrBrokenReportIsAServerError(@TempDir Path dir) throws Exception {
        assertFalse(GradleFailureReport.isUserError(new File(dir.toFile(), "gradle-failures.json")));
        Path report = Files.writeString(dir.resolve("gradle-failures.json"), "[{\"subject\":");
        assertFalse(GradleFailureReport.isUserError(report.toFile()));
        Files.writeString(report, "{\"types\":[\"groovy.lang.MissingMethodException\"]}");
        assertFalse(GradleFailureReport.isUserError(report.toFile()));
    }
}

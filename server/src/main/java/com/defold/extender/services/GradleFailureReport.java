package com.defold.extender.services;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The report gradle-failure-report.gradle writes when the dependency resolution build fails: for
 * each failure, the exception types behind it (every cause and its superclasses).
 */
final class GradleFailureReport {
    private static final Logger LOGGER = LoggerFactory.getLogger(GradleFailureReport.class);

    private static final Set<String> USER_ERROR_TYPES = Set.of(
        // every repository answered that the module version does not exist
        "org.gradle.internal.resolve.ModuleVersionNotFoundException",
        // the module offers no variant the build can use, or the version constraints conflict
        "org.gradle.internal.component.resolution.failure.exception.AbstractResolutionFailureException",
        // the build script is a fixed template, so one that does not evaluate fails on the
        // dependencies or repositories an extension put into it
        "groovy.lang.MissingMethodException",
        "groovy.lang.MissingPropertyException",
        "org.codehaus.groovy.control.CompilationFailedException");

    // a repository that could not be read: Gradle reports a module as missing only when every
    // repository answered, so these never come with a project error that is certain
    private static final Set<String> INFRASTRUCTURE_TYPES = Set.of(
        "org.gradle.api.resources.ResourceException",
        "java.io.IOException");

    private GradleFailureReport() {}

    /** False when the report is missing or unreadable: the build then failed for some other reason. */
    static boolean isUserError(File report) {
        if (!report.isFile()) {
            return false;
        }
        try {
            return isUserError(parse(report));
        } catch (IOException | ParseException e) {
            LOGGER.warn("Unreadable Gradle failure report {}", report, e);
            return false;
        }
    }

    static boolean isUserError(List<Set<String>> failures) {
        return !failures.isEmpty() && failures.stream().allMatch(types ->
            !Collections.disjoint(types, USER_ERROR_TYPES) && Collections.disjoint(types, INFRASTRUCTURE_TYPES));
    }

    private static List<Set<String>> parse(File report) throws IOException, ParseException {
        Object parsed = new JSONParser().parse(Files.readString(report.toPath(), StandardCharsets.UTF_8));
        List<Set<String>> failures = new ArrayList<>();
        if (!(parsed instanceof JSONArray entries)) {
            return failures;
        }
        for (Object entry : entries) {
            Set<String> types = new HashSet<>();
            if (entry instanceof JSONObject failure && failure.get("types") instanceof JSONArray names) {
                for (Object name : names) {
                    if (name instanceof String type) {
                        types.add(type);
                    }
                }
            }
            failures.add(types);
        }
        return failures;
    }
}

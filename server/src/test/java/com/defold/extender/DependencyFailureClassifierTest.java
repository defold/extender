package com.defold.extender;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.defold.extender.DependencyFailureClassifier.Tool;

public class DependencyFailureClassifierTest {

    @Test
    public void gradleMissingArtifactIsAUserError() {
        String output = """
                * What went wrong:
                Execution failed for task ':resolveDependencies'.
                > Could not resolve all files for configuration ':releaseRuntimeClasspath'.
                   > Could not find com.example:missing-sdk:1.0.0.
                     Searched in the following locations:
                       - https://dl.google.com/dl/android/maven2/com/example/missing-sdk/1.0.0/missing-sdk-1.0.0.pom
                """;
        assertTrue(DependencyFailureClassifier.isUserError(Tool.GRADLE, output));
    }

    @Test
    public void gradleInvalidDependencyDeclarationIsAUserError() {
        String output = """
                * What went wrong:
                A problem occurred evaluating root project 'resolver'.
                > Could not find method implementaton() for arguments [com.google.android.gms:play-services-ads:23.0.0] on object of type org.gradle.api.internal.artifacts.dsl.dependencies.DefaultDependencyHandler.
                """;
        assertTrue(DependencyFailureClassifier.isUserError(Tool.GRADLE, output));
    }

    @Test
    public void gradleRepositoryOutageIsAServerError() {
        String output = """
                > Could not resolve all files for configuration ':releaseRuntimeClasspath'.
                   > Could not resolve com.google.firebase:firebase-analytics:22.1.0.
                      > Could not get resource 'https://dl.google.com/dl/android/maven2/com/google/firebase/firebase-analytics/22.1.0/firebase-analytics-22.1.0.pom'.
                         > Could not GET 'https://dl.google.com/dl/android/maven2/com/google/firebase/firebase-analytics/22.1.0/firebase-analytics-22.1.0.pom'. Received status code 502 from server: Bad Gateway
                """;
        assertFalse(DependencyFailureClassifier.isUserError(Tool.GRADLE, output));
    }

    @Test
    public void gradleUnknownHostIsAServerErrorEvenWhenAnArtifactIsMissing() {
        String output = """
                   > Could not find com.example:sdk:1.0.0.
                   > Could not resolve com.google.firebase:firebase-analytics:22.1.0.
                      > Could not GET 'https://repo.maven.apache.org/maven2/'. > repo.maven.apache.org: Could not resolve host
                """;
        assertFalse(DependencyFailureClassifier.isUserError(Tool.GRADLE, output));
    }

    @Test
    public void cocoaPodsUnknownPodIsAUserError() {
        assertTrue(DependencyFailureClassifier.isUserError(Tool.COCOAPODS,
                "Analyzing dependencies\n[!] Unable to find a specification for `FirebaseAnalyticz`\n"));
    }

    @Test
    public void cocoaPodsVersionConflictIsAUserError() {
        String output = """
                [!] CocoaPods could not find compatible versions for pod "GoogleUtilities/Environment":
                  In Podfile:
                    FirebaseAnalytics (= 10.0.0) was resolved to 10.0.0, which depends on
                      GoogleUtilities/Environment (~> 7.8)
                """;
        assertTrue(DependencyFailureClassifier.isUserError(Tool.COCOAPODS, output));
    }

    @Test
    public void cocoaPodsCdnFailureIsAServerError() {
        String output = """
                [!] CDN: trunk URL couldn't be downloaded: https://cdn.cocoapods.org/all_pods_versions_a_7_5.txt Response: Timeout was reached
                """;
        assertFalse(DependencyFailureClassifier.isUserError(Tool.COCOAPODS, output));
    }

    @Test
    public void spmMissingProductIsAUserError() {
        String output = """
                xcodebuild: error: Could not resolve package dependencies:
                  product 'FirebaseAnalyticz' required by package 'extensionfirebase' target 'ExtensionFirebase' not found in package 'firebase-ios-sdk'.
                """;
        assertTrue(DependencyFailureClassifier.isUserError(Tool.SPM, output));
    }

    @Test
    public void spmUnsatisfiableVersionIsAUserError() {
        String output = """
                xcodebuild: error: Could not resolve package dependencies:
                  Dependencies could not be resolved because root depends on 'firebase-ios-sdk' 99.0.0..<100.0.0.
                'firebase-ios-sdk' 99.0.0..<100.0.0 cannot be used because no versions of 'firebase-ios-sdk' match the requirement 99.0.0..<100.0.0.
                """;
        assertTrue(DependencyFailureClassifier.isUserError(Tool.SPM, output));
    }

    @Test
    public void spmMisspelledRepositoryIsAUserError() {
        String output = """
                xcodebuild: error: Could not resolve package dependencies:
                  Failed to clone repository https://github.com/firebase/firebase-ios-sdx:
                    Cloning into bare repository '/tmp/spm/repositories/firebase-ios-sdx-1a2b3c'...
                    remote: Repository not found.
                    fatal: repository 'https://github.com/firebase/firebase-ios-sdx/' not found
                """;
        assertTrue(DependencyFailureClassifier.isUserError(Tool.SPM, output));
    }

    @Test
    public void spmCloneNetworkFailureIsAServerError() {
        String output = """
                xcodebuild: error: Could not resolve package dependencies:
                  Failed to clone repository https://github.com/firebase/firebase-ios-sdk:
                    fatal: unable to access 'https://github.com/firebase/firebase-ios-sdk/': Could not resolve host: github.com
                """;
        assertFalse(DependencyFailureClassifier.isUserError(Tool.SPM, output));
    }

    @Test
    public void unrecognisedOutputIsAServerError() {
        assertFalse(DependencyFailureClassifier.isUserError(Tool.GRADLE, "Daemon disappeared unexpectedly"));
        assertFalse(DependencyFailureClassifier.isUserError(Tool.COCOAPODS, null));
    }

    @Test
    public void patternsOfOneToolDoNotApplyToAnother() {
        assertFalse(DependencyFailureClassifier.isUserError(Tool.SPM, "[!] Unable to find a specification for `Foo`"));
    }
}

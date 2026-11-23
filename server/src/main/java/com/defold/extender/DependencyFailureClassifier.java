package com.defold.extender;

import java.util.List;
import java.util.regex.Pattern;

// Tells dependency resolution failures caused by the project (unknown packages, version
// conflicts) apart from infrastructure problems. Anything unrecognised counts as infrastructure.
public final class DependencyFailureClassifier {
    public enum Tool {
        GRADLE(
            Pattern.compile("Could not find [\\w.\\-]+:[\\w.\\-]+"),
            Pattern.compile("Could not find method "),
            Pattern.compile("No matching variant of ")),
        COCOAPODS(
            Pattern.compile("Unable to find a specification for "),
            Pattern.compile("could not find compatible versions for pod "),
            Pattern.compile("Invalid `Podfile` file")),
        SPM(
            Pattern.compile("product '[^']+' required by package .* not found"),
            Pattern.compile("Dependencies could not be resolved because "),
            Pattern.compile("fatal: repository '[^']+' not found"));

        private final List<Pattern> userErrors;

        Tool(Pattern... userErrors) {
            this.userErrors = List.of(userErrors);
        }
    }

    private static final Pattern NETWORK = Pattern.compile(
            "(?i)could not resolve host|unknownhostexception|connection (refused|reset|timed out)"
            + "|timed out|timeout was reached|network is unreachable|received status code 5\\d\\d"
            + "|ssl\\w*exception|unable to access '|couldn't be downloaded");

    private DependencyFailureClassifier() {}

    public static boolean isUserError(Tool tool, String output) {
        if (output == null || NETWORK.matcher(output).find()) {
            return false;
        }
        return tool.userErrors.stream().anyMatch(p -> p.matcher(output).find());
    }
}

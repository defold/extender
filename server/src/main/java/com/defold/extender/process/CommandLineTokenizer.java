package com.defold.extender.process;

import java.util.ArrayList;
import java.util.List;

public final class CommandLineTokenizer {
    private CommandLineTokenizer() {}

    public static List<String> parse(String command) {
        return tokenize(command, false);
    }

    public static List<String> splitPreservingEscapedWhitespace(String arguments) {
        return tokenize(arguments, true);
    }

    public static String escapeWhitespace(String argument) {
        StringBuilder result = new StringBuilder();
        boolean escaping = false;

        for (int i = 0; i < argument.length(); ++i) {
            char c = argument.charAt(i);
            if (escaping) {
                result.append('\\');
                result.append(c);
                escaping = false;
            } else if (c == '\\') {
                escaping = true;
            } else if (Character.isWhitespace(c)) {
                result.append('\\');
                result.append(c);
            } else {
                result.append(c);
            }
        }

        if (escaping) {
            result.append('\\');
        }

        return result.toString();
    }

    private static List<String> tokenize(String input, boolean preserveEscapes) {
        List<String> result = new ArrayList<>();
        if (input == null || input.trim().isEmpty()) {
            return result;
        }

        StringBuilder token = new StringBuilder();
        boolean inToken = false;
        boolean escaping = false;
        char quote = 0;
        boolean literalQuote = false;

        for (int i = 0; i < input.length(); ++i) {
            char c = input.charAt(i);

            if (escaping) {
                appendEscaped(token, c, preserveEscapes);
                inToken = true;
                escaping = false;
                continue;
            }

            if (c == '\\') {
                escaping = true;
                inToken = true;
                continue;
            }

            if (quote != 0) {
                if (c == quote) {
                    if (literalQuote) {
                        token.append(c);
                    }
                    quote = 0;
                    literalQuote = false;
                } else {
                    appendLiteral(token, c, preserveEscapes);
                }
                inToken = true;
                continue;
            }

            if (c == '\'' || c == '"') {
                literalQuote = isLiteralQuote(token, preserveEscapes);
                if (literalQuote) {
                    token.append(c);
                }
                quote = c;
                inToken = true;
                continue;
            }

            if (Character.isWhitespace(c)) {
                if (inToken) {
                    result.add(token.toString());
                    token.setLength(0);
                    inToken = false;
                }
                continue;
            }

            token.append(c);
            inToken = true;
        }

        if (escaping) {
            throw new IllegalArgumentException("Dangling escape in command line: " + input);
        }
        if (quote != 0) {
            throw new IllegalArgumentException("Unclosed quote in command line: " + input);
        }
        if (inToken) {
            result.add(token.toString());
        }

        return result;
    }

    private static void appendEscaped(StringBuilder token, char c, boolean preserveEscapes) {
        if (preserveEscapes) {
            token.append('\\');
            token.append(c);
        } else if (isEscapable(c)) {
            token.append(c);
        } else {
            token.append('\\');
            token.append(c);
        }
    }

    private static void appendLiteral(StringBuilder token, char c, boolean preserveEscapes) {
        if (preserveEscapes && Character.isWhitespace(c)) {
            token.append('\\');
            token.append(c);
        } else {
            token.append(c);
        }
    }

    private static boolean isEscapable(char c) {
        return Character.isWhitespace(c) || c == '\\' || c == '\'' || c == '"';
    }

    private static boolean isLiteralQuote(StringBuilder token, boolean preserveEscapes) {
        if (preserveEscapes) {
            return false;
        }

        String currentToken = token.toString();
        if (currentToken.startsWith("-D") && currentToken.indexOf("=") != -1) {
            return true;
        }

        int assignmentIndex = currentToken.indexOf("=");
        return assignmentIndex != -1 && assignmentIndex < currentToken.length() - 1;
    }
}

package com.defold.extender;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Applies the server-side policy for untrusted R8/ProGuard-compatible rule files. */
final class R8RulePolicy {
    // Match exact lowercase prefixes anywhere in the raw text. R8 only accepts these lowercase
    // spellings. Deliberate false positives in comments, strings, or malformed tokens keep this
    // check independent of R8's evolving grammar.
    private static final List<String> BLOCKED_OPTION_PREFIXES = List.of(
            "include",
            "basedirectory",
            "injars",
            "outjars",
            "libraryjars",
            "applymapping",
            "obfuscationdictionary",
            "classobfuscationdictionary",
            "packageobfuscationdictionary",
            "print",
            "dump",
            "protomapping",
            "laststageoutput");

    static final class Budget {
        private final R8Configuration configuration;
        private int fileCount;
        private long totalBytes;

        Budget() {
            this(new R8Configuration());
        }

        Budget(R8Configuration configuration) {
            this.configuration = Objects.requireNonNull(configuration);
        }

        private void beginFile(String source, long declaredSize) throws ExtenderException {
            ++fileCount;
            if (fileCount > configuration.getMaxRuleFiles()) {
                throw new ExtenderException(String.format(
                        "Too many R8 rule files (maximum %d), while reading %s",
                        configuration.getMaxRuleFiles(),
                        source));
            }
            if (declaredSize > configuration.getMaxRuleFileBytes()) {
                throw new ExtenderException(String.format(
                        "R8 rule file is too large (maximum %d bytes): %s",
                        configuration.getMaxRuleFileBytes(),
                        source));
            }
            if (declaredSize >= 0
                    && totalBytes + declaredSize > configuration.getMaxTotalRuleBytes()) {
                throw new ExtenderException(String.format(
                        "R8 rule files are too large in total (maximum %d bytes), while reading %s",
                        configuration.getMaxTotalRuleBytes(),
                        source));
            }
        }

        private void addBytes(String source, long fileBytes, int bytesRead) throws ExtenderException {
            if (fileBytes > configuration.getMaxRuleFileBytes()) {
                throw new ExtenderException(String.format(
                        "R8 rule file is too large (maximum %d bytes): %s",
                        configuration.getMaxRuleFileBytes(),
                        source));
            }
            totalBytes += bytesRead;
            if (totalBytes > configuration.getMaxTotalRuleBytes()) {
                throw new ExtenderException(String.format(
                        "R8 rule files are too large in total (maximum %d bytes), while reading %s",
                        configuration.getMaxTotalRuleBytes(),
                        source));
            }
        }

        private long getMaxRuleFileBytes() {
            return configuration.getMaxRuleFileBytes();
        }
    }

    private R8RulePolicy() {}

    static File createEmptyBaseDirectory(File parent) throws ExtenderException {
        try {
            Files.createDirectories(parent.toPath());
            return Files.createTempDirectory(parent.toPath(), "r8-rule-base-").toFile();
        } catch (IOException e) {
            throw new ExtenderException(e, "Failed to create the R8 rule sandbox directory");
        }
    }

    static void requireEmptyBaseDirectory(File emptyBase) throws ExtenderException {
        Path basePath = emptyBase.toPath();
        if (!Files.isDirectory(basePath)) {
            throw new ExtenderException("R8 rule sandbox is not a directory: " + emptyBase);
        }
        try (Stream<Path> entries = Files.list(basePath)) {
            if (entries.findAny().isPresent()) {
                throw new ExtenderException("R8 rule sandbox is not empty: " + emptyBase);
            }
        } catch (IOException e) {
            throw new ExtenderException(e, "Failed to inspect the R8 rule sandbox " + emptyBase);
        }
    }

    static byte[] readAndValidate(ZipFile zipFile, ZipEntry entry, Budget budget)
            throws ExtenderException {
        String source = zipFile.getName() + "!/" + entry.getName();
        try (InputStream input = zipFile.getInputStream(entry)) {
            return normalizeAndValidate(
                    readBounded(input, entry.getSize(), source, budget),
                    source);
        } catch (IOException e) {
            throw new ExtenderException(e, "Failed to read R8 consumer rules from " + source);
        }
    }

    static byte[] readAndValidate(File ruleFile, Budget budget) throws ExtenderException {
        String source = ruleFile.getAbsolutePath();
        try (InputStream input = new FileInputStream(ruleFile)) {
            return normalizeAndValidate(
                    readBounded(input, ruleFile.length(), source, budget),
                    source);
        } catch (IOException e) {
            throw new ExtenderException(e, "Failed to read R8 rule file " + source);
        }
    }

    static void account(File trustedRuleFile, Budget budget) throws ExtenderException {
        String source = trustedRuleFile.getAbsolutePath();
        try (InputStream input = new FileInputStream(trustedRuleFile)) {
            readBounded(input, trustedRuleFile.length(), source, budget);
        } catch (IOException e) {
            throw new ExtenderException(e, "Failed to read R8 rule file " + source);
        }
    }

    static void writeSanitized(File target, byte[] contents, File emptyBase)
            throws ExtenderException {
        requireEmptyBaseDirectory(emptyBase);
        try {
            String base = emptyBase.getCanonicalPath();
            char quote;
            if (!base.contains("\"") && base.indexOf('\n') < 0 && base.indexOf('\r') < 0) {
                quote = '"';
            } else if (!base.contains("'") && base.indexOf('\n') < 0 && base.indexOf('\r') < 0) {
                quote = '\'';
            } else {
                throw new ExtenderException("R8 rule sandbox path cannot be quoted safely: " + emptyBase);
            }

            byte[] prefix = String.format("-basedirectory %c%s%c\n", quote, base, quote)
                    .getBytes(StandardCharsets.UTF_8);
            ByteArrayOutputStream output = new ByteArrayOutputStream(prefix.length + contents.length);
            output.write(prefix);
            output.write(contents);
            Files.createDirectories(target.getParentFile().toPath());
            Files.write(target.toPath(), output.toByteArray());
        } catch (IOException e) {
            throw new ExtenderException(e, "Failed to write sanitized R8 rules to " + target);
        }
    }

    private static byte[] readBounded(
            InputStream input,
            long declaredSize,
            String source,
            Budget budget) throws IOException, ExtenderException {
        budget.beginFile(source, declaredSize);
        ByteArrayOutputStream output = new ByteArrayOutputStream(
                (int) Math.min(
                        Math.min(Math.max(declaredSize, 0), budget.getMaxRuleFileBytes()),
                        Integer.MAX_VALUE));
        byte[] buffer = new byte[8192];
        long fileBytes = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            fileBytes += read;
            budget.addBytes(source, fileBytes, read);
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private static byte[] normalizeAndValidate(byte[] contents, String source)
            throws ExtenderException {
        final String decoded;
        try {
            decoded = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(contents))
                    .toString();
        } catch (CharacterCodingException e) {
            throw new ExtenderException(e, "R8 rule file is not valid UTF-8: " + source);
        }
        String rules = decoded.startsWith("\uFEFF") ? decoded.substring(1) : decoded;
        validateRules(rules, source);
        return rules.getBytes(StandardCharsets.UTF_8);
    }

    static void validateRules(String rules, String source) throws ExtenderException {
        int line = 1;
        for (int index = 0; index < rules.length(); ++index) {
            char current = rules.charAt(index);
            if (current == '\n') {
                ++line;
                continue;
            }
            if (current == '-') {
                for (String blockedPrefix : BLOCKED_OPTION_PREFIXES) {
                    if (rules.startsWith(blockedPrefix, index + 1)) {
                        throw blockedDirective(source, line, "-" + blockedPrefix);
                    }
                }
            } else if (current == '@' && hasPathLikeOperand(rules, index + 1)) {
                throw blockedDirective(source, line, "path-like @file include");
            }
        }
    }

    private static boolean hasPathLikeOperand(String rules, int start) {
        int index = start;
        while (index < rules.length()) {
            while (index < rules.length() && Character.isWhitespace(rules.charAt(index))) {
                ++index;
            }
            if (index >= rules.length() || rules.charAt(index) != '#') {
                break;
            }
            while (index < rules.length()
                    && rules.charAt(index) != '\n'
                    && rules.charAt(index) != '\r') {
                ++index;
            }
        }
        if (index >= rules.length()) {
            return false;
        }

        char first = rules.charAt(index);
        if (first == '/' || first == '\\' || first == '.' || first == '~'
                || first == '\'' || first == '"' || first == '<') {
            return true;
        }

        for (; index < rules.length() && !Character.isWhitespace(rules.charAt(index)); ++index) {
            char current = rules.charAt(index);
            if (current == '/' || current == '\\' || current == ':'
                    || current == '<' || current == '>') {
                return true;
            }
            if (current == '.' && index + 1 < rules.length() && rules.charAt(index + 1) == '.') {
                return true;
            }
        }
        return false;
    }

    private static ExtenderException blockedDirective(String source, int line, String directive) {
        return new ExtenderException(String.format(
                "R8 rule file %s:%d uses forbidden filesystem directive %s",
                source,
                line,
                directive));
    }
}

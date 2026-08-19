package com.defold.extender;

import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.DataInputStream;
import java.io.File;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Builds Android dex files with R8 and owns all R8 rule discovery and assembly. */
final class R8Builder {
    private static final Logger LOGGER = LoggerFactory.getLogger(R8Builder.class);

    static final String RULES_WITHOUT_JAR = "r8_rules_without_jar";

    private static final boolean DM_DEBUG_DISABLE_R8 = System.getenv("DM_DEBUG_DISABLE_R8") != null;
    private static final String APP_RULES_PATH = "_app/app.keep";
    private static final String JAR_LEGACY_RULE_PREFIX = "META-INF/proguard/";
    private static final String JAR_R8_RULE_PREFIX = "META-INF/com.android.tools/r8";
    private static final String GENERATED_EXTENSION_KEEP_ATTRIBUTES =
            "-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod,MethodParameters,Exceptions";
    static final int MAX_GENERATED_EXTENSION_CLASSES = 32 * 1024;
    static final int MAX_CLASSFILE_HEADER_BYTES = 4 * 1024 * 1024;
    static final long MAX_TOTAL_CLASSFILE_HEADER_BYTES = 64L * 1024L * 1024L;
    static final class ExtensionContext {
        final List<String> ruleFiles = new ArrayList<>();
        final List<String> protectedJars = new ArrayList<>();
    }

    static final class BuildOutput {
        final File[] dexFiles;
        final File mappingFile;

        BuildOutput(File[] dexFiles, File mappingFile) {
            this.dexFiles = dexFiles;
            this.mappingFile = mappingFile;
        }
    }

    @FunctionalInterface
    interface CommandExecutor {
        void execute(String commandTemplate, Map<String, Object> context) throws ExtenderException;
    }

    private final File uploadDir;
    private final File buildDir;
    private final PlatformConfig platformConfig;
    private final List<File> androidPackages;
    private final Map<String, Object> commandContext;
    private final int minAndroidSdkVersion;
    private final TemplateExecutor templateExecutor;
    private final CommandExecutor commandExecutor;

    R8Builder(
            File uploadDir,
            File buildDir,
            PlatformConfig platformConfig,
            List<File> androidPackages,
            Map<String, Object> commandContext,
            int minAndroidSdkVersion,
            TemplateExecutor templateExecutor,
            CommandExecutor commandExecutor) {
        this.uploadDir = uploadDir;
        this.buildDir = buildDir;
        this.platformConfig = platformConfig;
        this.androidPackages = androidPackages;
        this.commandContext = commandContext;
        this.minAndroidSdkVersion = minAndroidSdkVersion;
        this.templateExecutor = templateExecutor;
        this.commandExecutor = commandExecutor;
    }

    static ExtensionContext createExtensionContext(
            File extensionDir,
            List<String> extensionLibJars,
            String ruleSourceRegex) {
        ExtensionContext context = new ExtensionContext();
        File manifestDir = new File(extensionDir, "manifests/android");
        if (manifestDir.isDirectory() && ruleSourceRegex != null && !ruleSourceRegex.isBlank()) {
            Collection<File> candidates = FileUtils.listFiles(manifestDir, null, true);
            List<File> ruleFiles = ExtenderUtil.filterFiles(candidates, ruleSourceRegex);
            ruleFiles.sort((left, right) -> left.getAbsolutePath().compareTo(right.getAbsolutePath()));
            for (File ruleFile : ruleFiles) {
                context.ruleFiles.add(ruleFile.getAbsolutePath());
            }
        }

        if (context.ruleFiles.isEmpty()) {
            context.protectedJars.addAll(extensionLibJars);
        }
        return context;
    }

    static boolean isRulesOnlyPlaceholder(String path) {
        return path.endsWith(RULES_WITHOUT_JAR);
    }

    static List<String> getCompiledJars(Map<String, ExtensionContext> extensionJarMap) {
        return extensionJarMap.keySet().stream()
                .filter(path -> !isRulesOnlyPlaceholder(path))
                .sorted()
                .collect(Collectors.toList());
    }

    static List<String> getAndroidPackageJars(File androidPackage) {
        Set<String> jars = new TreeSet<>();
        File classesJar = new File(androidPackage, "classes.jar");
        if (classesJar.isFile()) {
            jars.add(classesJar.getAbsolutePath());
        }
        File libsDir = new File(androidPackage, "libs");
        File[] libraryJars = libsDir.listFiles(file -> file.isFile() && file.getName().endsWith(".jar"));
        if (libraryJars != null) {
            for (File libraryJar : libraryJars) {
                jars.add(libraryJar.getAbsolutePath());
            }
        }
        return new ArrayList<>(jars);
    }

    private static final class R8SemanticVersion {
        private final int major;
        private final int minor;
        private final int patch;
        private final String prerelease;

        private R8SemanticVersion(int major, int minor, int patch, String prerelease) {
            this.major = major;
            this.minor = minor;
            this.patch = patch;
            this.prerelease = prerelease;
        }

        static R8SemanticVersion parse(String value) {
            int firstDot = value.indexOf('.');
            if (firstDot <= 0) {
                throw new IllegalArgumentException("Invalid R8 semantic version " + value);
            }
            int secondDot = value.indexOf('.', firstDot + 1);
            if (secondDot <= firstDot + 1) {
                throw new IllegalArgumentException("Invalid R8 semantic version " + value);
            }
            int prereleaseStart = value.indexOf('-', secondDot + 1);
            int patchEnd = prereleaseStart < 0 ? value.length() : prereleaseStart;
            if (patchEnd <= secondDot + 1) {
                throw new IllegalArgumentException("Invalid R8 semantic version " + value);
            }
            try {
                return new R8SemanticVersion(
                        Integer.parseInt(value.substring(0, firstDot)),
                        Integer.parseInt(value.substring(firstDot + 1, secondDot)),
                        Integer.parseInt(value.substring(secondDot + 1, patchEnd)),
                        prereleaseStart < 0 ? null : value.substring(prereleaseStart + 1));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Invalid R8 semantic version " + value, e);
            }
        }

        int compareNumbers(R8SemanticVersion other) {
            int comparison = Integer.compare(major, other.major);
            if (comparison == 0) {
                comparison = Integer.compare(minor, other.minor);
            }
            if (comparison == 0) {
                comparison = Integer.compare(patch, other.patch);
            }
            return comparison;
        }

        boolean isNewer(R8SemanticVersion other) {
            return compareNumbers(other) > 0;
        }

        boolean isNewerOrEqual(R8SemanticVersion other) {
            return isNewer(other) || (compareNumbers(other) == 0
                    && (prerelease == null ? other.prerelease == null : prerelease.equals(other.prerelease)));
        }
    }

    static int compareVersions(String left, String right) {
        return R8SemanticVersion.parse(left).compareNumbers(R8SemanticVersion.parse(right));
    }

    static boolean isEmbeddedRuleEntryName(String entryName) {
        if (entryName.startsWith(JAR_LEGACY_RULE_PREFIX)) {
            return true;
        }
        if (!entryName.startsWith(JAR_R8_RULE_PREFIX)) {
            return false;
        }
        String suffix = entryName.substring(JAR_R8_RULE_PREFIX.length());
        return suffix.startsWith("/")
                || suffix.startsWith("-from-")
                || suffix.startsWith("-upto-");
    }

    private static int indexOfEither(String value, char first, char second) {
        int firstIndex = value.indexOf(first);
        int secondIndex = value.indexOf(second);
        if (firstIndex < 0) {
            return secondIndex;
        }
        if (secondIndex < 0) {
            return firstIndex;
        }
        return Math.min(firstIndex, secondIndex);
    }

    private static boolean isApplicableR8RuleEntry(String entryName, String r8Version) {
        if (!entryName.startsWith(JAR_R8_RULE_PREFIX)) {
            return false;
        }
        String suffix = entryName.substring(JAR_R8_RULE_PREFIX.length());
        if (suffix.startsWith("/")) {
            return true;
        }
        if (!suffix.startsWith("-from-") && !suffix.startsWith("-upto-")) {
            return false;
        }

        try {
            R8SemanticVersion from = new R8SemanticVersion(0, 0, 0, null);
            R8SemanticVersion upTo = null;
            if (suffix.startsWith("-from-")) {
                suffix = suffix.substring("-from-".length());
                int versionEnd = indexOfEither(suffix, '-', '/');
                if (versionEnd < 0) {
                    return false;
                }
                from = R8SemanticVersion.parse(suffix.substring(0, versionEnd));
                suffix = suffix.substring(versionEnd);
            }
            if (suffix.startsWith("-upto-")) {
                suffix = suffix.substring("-upto-".length());
                int versionEnd = suffix.indexOf('/');
                if (versionEnd < 0) {
                    return false;
                }
                upTo = R8SemanticVersion.parse(suffix.substring(0, versionEnd));
            }
            R8SemanticVersion compilerVersion = R8SemanticVersion.parse(r8Version);
            return compilerVersion.isNewerOrEqual(from)
                    && (upTo == null || upTo.isNewer(compilerVersion));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    static boolean isApplicableRuleDirectory(String directory, String r8Version) {
        if (!directory.startsWith("r8")) {
            return false;
        }
        return isApplicableR8RuleEntry(
                JAR_R8_RULE_PREFIX + directory.substring("r8".length()) + "/rules.keep",
                r8Version);
    }

    static List<String> selectEmbeddedRuleEntries(File jar, String r8Version)
            throws IOException, ExtenderException {
        List<String> targetedRules = new ArrayList<>();
        List<String> legacyRules = new ArrayList<>();
        Set<String> candidateNames = new LinkedHashSet<>();
        int candidateCount = 0;

        try (ZipFile zipFile = new ZipFile(jar)) {
            Enumeration<? extends ZipEntry> entries = zipFile.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) {
                    continue;
                }

                String entryName = entry.getName();
                if (!isEmbeddedRuleEntryName(entryName)) {
                    continue;
                }
                if (!candidateNames.add(entryName)) {
                    throw new ExtenderException("Duplicate embedded R8 rule entry " + entryName + " in " + jar);
                }
                if (++candidateCount > R8RulePolicy.MAX_RULE_FILES) {
                    throw new ExtenderException(String.format(
                            "Too many embedded R8 rule files in %s (maximum %d)",
                            jar,
                            R8RulePolicy.MAX_RULE_FILES));
                }
                if (entryName.startsWith(JAR_LEGACY_RULE_PREFIX)) {
                    legacyRules.add(entryName);
                    continue;
                }
                if (isApplicableR8RuleEntry(entryName, r8Version)) {
                    targetedRules.add(entryName);
                }
            }
        }

        Collections.sort(targetedRules);
        Collections.sort(legacyRules);
        return targetedRules.isEmpty() ? legacyRules : targetedRules;
    }

    static boolean isRequested(File uploadDir) {
        return new File(uploadDir, APP_RULES_PATH).isFile();
    }

    static void validateConfiguration(String r8Cmd, String r8Version) throws ExtenderException {
        if (r8Cmd == null || r8Cmd.isBlank() || r8Version == null || r8Version.isBlank()) {
            throw new ExtenderException("R8 shrinking was requested, but this Defold SDK does not provide r8Cmd and r8Version");
        }
        try {
            compareVersions(r8Version, r8Version);
        } catch (IllegalArgumentException e) {
            throw new ExtenderException("Invalid r8Version in this Defold SDK: " + r8Version);
        }
    }

    private void validateResolvedR8Environment(Map<String, Object> context) throws ExtenderException {
        for (String name : List.of("R8", "R8_VERSION")) {
            if (!platformConfig.env.containsKey(name)) {
                continue;
            }
            Object value = context.get("env." + name);
            if (!(value instanceof String) || ((String) value).isBlank()) {
                validateConfiguration(null, null);
            }
        }
    }

    private static boolean containsTargetedRules(List<String> entries) {
        return entries.stream().anyMatch(entry -> entry.startsWith(JAR_R8_RULE_PREFIX));
    }

    static List<String> collectConsumerRules(
            List<String> allJars,
            List<File> androidPackages,
            String r8Version,
            File rulesRoot) throws ExtenderException {
        File emptyRuleBase = R8RulePolicy.createEmptyBaseDirectory(
                rulesRoot.getAbsoluteFile().getParentFile());
        return collectConsumerRules(
                allJars,
                androidPackages,
                r8Version,
                rulesRoot,
                new R8RulePolicy.Budget(),
                emptyRuleBase);
    }

    private static List<String> collectConsumerRules(
            List<String> allJars,
            List<File> androidPackages,
            String r8Version,
            File rulesRoot,
            R8RulePolicy.Budget ruleBudget,
            File emptyRuleBase) throws ExtenderException {
        try {
            Files.createDirectories(rulesRoot.toPath());
        } catch (IOException e) {
            throw new ExtenderException(e, "Failed to create R8 consumer rules directory " + rulesRoot);
        }

        List<String> sortedJars = new ArrayList<>(new LinkedHashSet<>(allJars));
        Collections.sort(sortedJars);
        Map<String, Boolean> jarHasTargetedRules = new HashMap<>();
        List<String> consumerRules = new ArrayList<>();
        int artifactIndex = 0;

        for (String jarPath : sortedJars) {
            File jar = new File(jarPath);
            if (!jar.isFile() || !jar.getName().endsWith(".jar")) {
                continue;
            }

            try (ZipFile zipFile = new ZipFile(jar)) {
                List<String> selectedEntries = selectEmbeddedRuleEntries(jar, r8Version);
                jarHasTargetedRules.put(jar.getCanonicalPath(), containsTargetedRules(selectedEntries));
                if (selectedEntries.isEmpty()) {
                    continue;
                }

                File artifactRulesDir = new File(rulesRoot, String.format("jar-%04d", artifactIndex++));
                for (int ruleIndex = 0; ruleIndex < selectedEntries.size(); ++ruleIndex) {
                    String entryName = selectedEntries.get(ruleIndex);
                    ZipEntry entry = zipFile.getEntry(entryName);
                    if (entry == null) {
                        throw new ExtenderException("Missing embedded R8 rule entry " + entryName + " in " + jar);
                    }
                    byte[] contents = R8RulePolicy.readAndValidate(zipFile, entry, ruleBudget);
                    File extractedRule = new File(
                            artifactRulesDir,
                            String.format("rule-%04d.keep", ruleIndex));
                    R8RulePolicy.writeSanitized(extractedRule, contents, emptyRuleBase);
                    consumerRules.add(extractedRule.getAbsolutePath());
                }
            } catch (IOException e) {
                throw new ExtenderException(e, "Failed to read R8 consumer rules from " + jarPath);
            }
        }

        List<File> sortedPackages = new ArrayList<>(androidPackages);
        sortedPackages.sort((left, right) -> left.getAbsolutePath().compareTo(right.getAbsolutePath()));
        for (File androidPackage : sortedPackages) {
            if (!androidPackage.isDirectory() || !androidPackage.getName().endsWith(".aar")) {
                continue;
            }

            File legacyRules = new File(androidPackage, "proguard.txt");
            File classesJar = new File(androidPackage, "classes.jar");
            try {
                boolean hasTargetedRules = classesJar.isFile()
                        && jarHasTargetedRules.getOrDefault(classesJar.getCanonicalPath(), false);
                if (legacyRules.isFile() && !hasTargetedRules) {
                    File extractedRules = new File(rulesRoot, String.format("aar-%04d.keep", artifactIndex++));
                    byte[] contents = R8RulePolicy.readAndValidate(legacyRules, ruleBudget);
                    R8RulePolicy.writeSanitized(extractedRules, contents, emptyRuleBase);
                    consumerRules.add(extractedRules.getAbsolutePath());
                }
            } catch (IOException e) {
                throw new ExtenderException(e, "Failed to collect R8 consumer rules from " + androidPackage);
            }
        }

        return consumerRules;
    }

    private static boolean hasEmbeddedRuleEntries(File jar) throws IOException {
        try (org.apache.commons.compress.archivers.zip.ZipFile zipFile =
                     org.apache.commons.compress.archivers.zip.ZipFile.builder().setFile(jar).get()) {
            Enumeration<ZipArchiveEntry> entries = zipFile.getEntriesInPhysicalOrder();
            while (entries.hasMoreElements()) {
                ZipArchiveEntry entry = entries.nextElement();
                if (!entry.isDirectory() && isEmbeddedRuleEntryName(entry.getName())) {
                    return true;
                }
            }
            return false;
        }
    }

    static File stripEmbeddedRuleEntries(File jar, File strippedJar) throws ExtenderException {
        try {
            if (!hasEmbeddedRuleEntries(jar)) {
                return jar;
            }
            if (strippedJar.toPath().getParent() != null) {
                Files.createDirectories(strippedJar.toPath().getParent());
            }
            try (org.apache.commons.compress.archivers.zip.ZipFile zipFile =
                         org.apache.commons.compress.archivers.zip.ZipFile.builder().setFile(jar).get();
                 ZipArchiveOutputStream output = new ZipArchiveOutputStream(strippedJar)) {
                Enumeration<ZipArchiveEntry> entries = zipFile.getEntriesInPhysicalOrder();
                while (entries.hasMoreElements()) {
                    ZipArchiveEntry entry = entries.nextElement();
                    if (!entry.isDirectory() && isEmbeddedRuleEntryName(entry.getName())) {
                        continue;
                    }
                    try (InputStream rawInput = zipFile.getRawInputStream(entry)) {
                        if (rawInput == null) {
                            throw new IOException("Missing raw ZIP data for " + entry.getName());
                        }
                        output.addRawArchiveEntry(new ZipArchiveEntry(entry), rawInput);
                    }
                }
            }
            return strippedJar;
        } catch (IOException e) {
            throw new ExtenderException(e, "Failed to remove embedded R8 rules from " + jar);
        }
    }

    private static List<String> prepareProgramJars(List<String> allJars, File strippedJarsDir)
            throws ExtenderException {
        List<String> originalProgramJars = allJars.stream()
                .filter(jar -> new File(jar).isFile())
                .distinct()
                .sorted()
                .collect(Collectors.toList());
        List<String> programJars = new ArrayList<>(originalProgramJars.size());
        for (int index = 0; index < originalProgramJars.size(); ++index) {
            File originalJar = new File(originalProgramJars.get(index));
            File strippedJar = new File(strippedJarsDir, String.format("program-%04d.jar", index));
            programJars.add(stripEmbeddedRuleEntries(originalJar, strippedJar).getPath());
        }
        return programJars;
    }

    private List<String> getProtectedJars(Map<String, ExtensionContext> extensionJarMap) {
        Set<String> protectedJars = new TreeSet<>();
        for (Map.Entry<String, ExtensionContext> extensionEntry : extensionJarMap.entrySet()) {
            ExtensionContext context = extensionEntry.getValue();
            if (context == null || !context.ruleFiles.isEmpty()) {
                continue;
            }

            String extensionJar = extensionEntry.getKey();
            if (!isRulesOnlyPlaceholder(extensionJar)) {
                protectedJars.add(extensionJar);
            }
            protectedJars.addAll(context.protectedJars);
        }
        return new ArrayList<>(protectedJars);
    }

    private static final class ClassFileReadBudget {
        private long totalBytes;
    }

    private static final class BoundedClassFileInputStream extends FilterInputStream {
        private final ClassFileReadBudget budget;
        private int classBytes;

        BoundedClassFileInputStream(InputStream input, ClassFileReadBudget budget) {
            super(input);
            this.budget = budget;
        }

        private int allowedBytes(int requested) throws IOException {
            if (requested == 0) {
                return 0;
            }
            int classRemaining = MAX_CLASSFILE_HEADER_BYTES - classBytes;
            if (classRemaining <= 0) {
                throw new IOException(String.format(
                        "Class file header exceeds the %d-byte read limit",
                        MAX_CLASSFILE_HEADER_BYTES));
            }
            long totalRemaining = MAX_TOTAL_CLASSFILE_HEADER_BYTES - budget.totalBytes;
            if (totalRemaining <= 0) {
                throw new IOException(String.format(
                        "Class file headers exceed the %d-byte aggregate read limit",
                        MAX_TOTAL_CLASSFILE_HEADER_BYTES));
            }
            return (int) Math.min(requested, Math.min(classRemaining, totalRemaining));
        }

        private void account(int count) {
            if (count > 0) {
                classBytes += count;
                budget.totalBytes += count;
            }
        }

        @Override
        public int read() throws IOException {
            allowedBytes(1);
            int value = super.read();
            if (value >= 0) {
                account(1);
            }
            return value;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            int allowed = allowedBytes(length);
            if (allowed == 0) {
                return 0;
            }
            int count = super.read(bytes, offset, allowed);
            account(count);
            return count;
        }
    }

    private static String readClassInternalName(
            ZipFile zipFile,
            ZipEntry entry,
            ClassFileReadBudget budget) throws IOException {
        try (DataInputStream input = new DataInputStream(new BoundedClassFileInputStream(
                zipFile.getInputStream(entry),
                budget))) {
            if (input.readInt() != 0xCAFEBABE) {
                throw new IOException("Invalid class file magic");
            }
            input.readUnsignedShort(); // minor_version
            input.readUnsignedShort(); // major_version

            int constantPoolCount = input.readUnsignedShort();
            if (constantPoolCount <= 1) {
                throw new IOException("Invalid class file constant pool count");
            }
            byte[] tags = new byte[constantPoolCount];
            String[] utf8Values = new String[constantPoolCount];
            int[] classNameIndexes = new int[constantPoolCount];
            for (int index = 1; index < constantPoolCount; ++index) {
                int tag = input.readUnsignedByte();
                tags[index] = (byte) tag;
                switch (tag) {
                    case 1:
                        utf8Values[index] = input.readUTF();
                        break;
                    case 3:
                    case 4:
                        input.readInt();
                        break;
                    case 5:
                    case 6:
                        input.readLong();
                        if (++index >= constantPoolCount) {
                            throw new IOException("Long or double overruns the class file constant pool");
                        }
                        break;
                    case 7:
                        classNameIndexes[index] = input.readUnsignedShort();
                        break;
                    case 8:
                    case 16:
                    case 19:
                    case 20:
                        input.readUnsignedShort();
                        break;
                    case 9:
                    case 10:
                    case 11:
                    case 12:
                    case 17:
                    case 18:
                        input.readInt();
                        break;
                    case 15:
                        input.readUnsignedByte();
                        input.readUnsignedShort();
                        break;
                    default:
                        throw new IOException("Unknown class file constant pool tag " + tag);
                }
            }

            input.readUnsignedShort(); // access_flags
            int thisClass = input.readUnsignedShort();
            input.readUnsignedShort(); // super_class
            if (thisClass <= 0 || thisClass >= constantPoolCount || tags[thisClass] != 7) {
                throw new IOException("Invalid this_class constant pool index");
            }
            int nameIndex = classNameIndexes[thisClass];
            if (nameIndex <= 0 || nameIndex >= constantPoolCount || tags[nameIndex] != 1) {
                throw new IOException("Invalid class name constant pool index");
            }
            return utf8Values[nameIndex];
        }
    }

    private static boolean isR8LiteralSimpleNameCodePoint(int codePoint) {
        boolean isDexSimpleNameCodePoint = (codePoint >= 'A' && codePoint <= 'Z')
                || (codePoint >= 'a' && codePoint <= 'z')
                || (codePoint >= '0' && codePoint <= '9')
                || codePoint == '$'
                || codePoint == '-'
                || codePoint == '_'
                || (codePoint >= 0x00A1 && codePoint <= 0x1FFF)
                || (codePoint >= 0x2010 && codePoint <= 0x2027)
                || (codePoint >= 0x2030 && codePoint <= 0xD7FF)
                || (codePoint >= 0xE000 && codePoint <= 0xFEFE)
                || (codePoint >= 0xFF00 && codePoint <= 0xFFEF)
                || (codePoint >= 0x10000 && codePoint <= 0x10FFFF);
        boolean isUnicodeSpace = codePoint == ' '
                || codePoint == 0x00A0
                || codePoint == 0x1680
                || (codePoint >= 0x2000 && codePoint <= 0x200A)
                || codePoint == 0x202F
                || codePoint == 0x205F
                || codePoint == 0x3000;
        return isDexSimpleNameCodePoint && !isUnicodeSpace;
    }

    private static String toR8ClassPattern(String internalName) throws IOException {
        if (internalName == null || internalName.isEmpty()) {
            throw new IOException("Class file has an empty internal name");
        }

        StringBuilder pattern = new StringBuilder(internalName.length());
        boolean segmentIsEmpty = true;
        for (int offset = 0; offset < internalName.length();) {
            int codePoint = internalName.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (codePoint == '/') {
                if (segmentIsEmpty || offset == internalName.length()) {
                    throw new IOException("Class file has an empty internal-name segment");
                }
                pattern.append('.');
                segmentIsEmpty = true;
            } else {
                if (codePoint == '.' || codePoint == ';' || codePoint == '[') {
                    throw new IOException(String.format(
                            "Class file internal name contains forbidden character U+%04X",
                            codePoint));
                }
                pattern.appendCodePoint(isR8LiteralSimpleNameCodePoint(codePoint) ? codePoint : '?');
                segmentIsEmpty = false;
            }
        }
        return pattern.toString();
    }

    static File writeProtectedJarKeepRules(List<String> jarPaths, File outputFile)
            throws IOException, ExtenderException {
        Set<String> classNames = new TreeSet<>();
        int classEntryCount = 0;
        ClassFileReadBudget classFileReadBudget = new ClassFileReadBudget();
        long generatedBytes = jarPaths.isEmpty()
                ? 0
                : GENERATED_EXTENSION_KEEP_ATTRIBUTES.getBytes(StandardCharsets.UTF_8).length + 1L;
        for (String jarPath : jarPaths) {
            File jar = new File(jarPath);
            if (!jar.isFile()) {
                continue;
            }

            try (ZipFile zipFile = new ZipFile(jar)) {
                Enumeration<? extends ZipEntry> entries = zipFile.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    String entryName = entry.getName();
                    if (entry.isDirectory()
                            || !entryName.endsWith(".class")
                            || entryName.startsWith("META-INF/")) {
                        continue;
                    }
                    if (++classEntryCount > MAX_GENERATED_EXTENSION_CLASSES) {
                        throw new ExtenderException(String.format(
                                "Too many classes need generated R8 keep rules (maximum %d)",
                                MAX_GENERATED_EXTENSION_CLASSES));
                    }

                    String internalName;
                    try {
                        internalName = readClassInternalName(zipFile, entry, classFileReadBudget);
                    } catch (IOException e) {
                        throw new ExtenderException(e, String.format(
                                "Failed to read class file %s from %s: %s",
                                entryName,
                                jar,
                                e.getMessage()));
                    }
                    if (internalName.equals("module-info")) {
                        continue;
                    }

                    String className;
                    try {
                        className = toR8ClassPattern(internalName);
                    } catch (IOException e) {
                        throw new ExtenderException(e, String.format(
                                "Invalid class name in %s from %s: %s",
                                entryName,
                                jar,
                                e.getMessage()));
                    }
                    if (classNames.add(className)) {
                        String rule = String.format("-keep class %s { *; }\n", className);
                        generatedBytes += rule.getBytes(StandardCharsets.UTF_8).length;
                        if (generatedBytes > R8RulePolicy.MAX_RULE_FILE_BYTES) {
                            throw new ExtenderException(String.format(
                                    "Generated R8 keep rules are too large (maximum %d bytes)",
                                    R8RulePolicy.MAX_RULE_FILE_BYTES));
                        }
                    }
                }
            }
        }

        StringBuilder rules = new StringBuilder((int) generatedBytes);
        if (!jarPaths.isEmpty()) {
            rules.append(GENERATED_EXTENSION_KEEP_ATTRIBUTES).append('\n');
        }
        for (String className : classNames) {
            rules.append("-keep class ").append(className).append(" { *; }\n");
        }
        Files.writeString(outputFile.toPath(), rules, StandardCharsets.UTF_8);
        return outputFile;
    }

    static String escapeDoubleQuotedCommandValue(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    static Map<String, Object> createR8CommandContext(Map<String, Object> context) {
        Map<String, Object> escaped = new HashMap<>(context);
        for (String key : List.of(
                "env.R8",
                "env.LIBRARYJAR",
                "classes_dex_dir",
                "mainDexList",
                "mapping")) {
            Object value = escaped.get(key);
            if (value instanceof String) {
                escaped.put(key, escapeDoubleQuotedCommandValue((String) value));
            }
        }
        for (String key : List.of("jars", "rules", "mainDexRules")) {
            Object value = escaped.get(key);
            if (value instanceof List<?>) {
                List<String> escapedValues = ((List<?>) value).stream()
                        .map(Object::toString)
                        .map(R8Builder::escapeDoubleQuotedCommandValue)
                        .collect(Collectors.toList());
                escaped.put(key, escapedValues);
            }
        }
        return escaped;
    }

    BuildOutput build(
            List<String> allJars,
            Map<String, ExtensionContext> extensionJarMap,
            File mainDexList,
            File aaptGeneratedRules,
            File aaptMainDexRules) throws ExtenderException {
        File appRules = new File(uploadDir, APP_RULES_PATH);
        if (!isRequested(uploadDir)) {
            LOGGER.info("No app.keep file present. Skipping R8 step.");
            return null;
        }
        if (DM_DEBUG_DISABLE_R8) {
            LOGGER.info("R8 support disabled by environment flag DM_DEBUG_DISABLE_R8");
            return null;
        }

        Map<String, Object> context = new HashMap<>(commandContext);
        if (platformConfig.r8Cmd == null || platformConfig.r8Cmd.isBlank()
                || platformConfig.r8Version == null || platformConfig.r8Version.isBlank()) {
            validateConfiguration(platformConfig.r8Cmd, platformConfig.r8Version);
        }
        validateResolvedR8Environment(context);
        String r8Version;
        try {
            r8Version = templateExecutor.executeOnceWithoutLogging(platformConfig.r8Version, context);
        } catch (RuntimeException e) {
            throw new ExtenderException(e, "R8 shrinking was requested, but its configuration could not be resolved");
        }
        validateConfiguration(platformConfig.r8Cmd, r8Version);
        if (aaptGeneratedRules == null || !aaptGeneratedRules.isFile()) {
            throw new ExtenderException(
                    "R8 shrinking was requested, but aapt2 did not generate aapt-generated.keep");
        }
        if (minAndroidSdkVersion < 21) {
            if (mainDexList == null || !mainDexList.isFile()) {
                throw new ExtenderException(
                        "R8 shrinking was requested below API 21, but the engine main-dex rules are missing");
            }
            if (aaptMainDexRules == null || !aaptMainDexRules.isFile()) {
                throw new ExtenderException(
                        "R8 shrinking was requested below API 21, but aapt2 did not generate aapt-main-dex-generated.keep");
            }
        }

        LOGGER.info("Building classes.dex using R8 {}", r8Version);

        Set<String> ruleFiles = new LinkedHashSet<>();
        R8RulePolicy.Budget ruleBudget = new R8RulePolicy.Budget();
        File emptyRuleBase = R8RulePolicy.createEmptyBaseDirectory(buildDir);
        File sanitizedRulesDir = new File(buildDir, "r8-sanitized-rules");
        File sanitizedAppRules = new File(sanitizedRulesDir, "app.keep");
        R8RulePolicy.writeSanitized(
                sanitizedAppRules,
                R8RulePolicy.readAndValidate(appRules, ruleBudget),
                emptyRuleBase);
        ruleFiles.add(sanitizedAppRules.getAbsolutePath());
        File sanitizedAaptRules = new File(sanitizedRulesDir, "aapt-generated.keep");
        R8RulePolicy.writeSanitized(
                sanitizedAaptRules,
                R8RulePolicy.readAndValidate(aaptGeneratedRules, ruleBudget),
                emptyRuleBase);
        ruleFiles.add(sanitizedAaptRules.getAbsolutePath());

        List<String> mainDexRules = new ArrayList<>();
        if (minAndroidSdkVersion < 21) {
            File sanitizedAaptMainDexRules = new File(
                    sanitizedRulesDir,
                    "aapt-main-dex-generated.keep");
            R8RulePolicy.writeSanitized(
                    sanitizedAaptMainDexRules,
                    R8RulePolicy.readAndValidate(aaptMainDexRules, ruleBudget),
                    emptyRuleBase);
            mainDexRules.add(mainDexList.getAbsolutePath());
            mainDexRules.add(sanitizedAaptMainDexRules.getAbsolutePath());
        }

        Set<String> seenUntrustedRules = new LinkedHashSet<>();
        int sanitizedExtensionRuleIndex = 0;
        List<String> extensionJars = new ArrayList<>(extensionJarMap.keySet());
        Collections.sort(extensionJars);
        for (String extensionJar : extensionJars) {
            ExtensionContext extensionContext = extensionJarMap.get(extensionJar);
            if (extensionContext != null) {
                List<String> extensionRules = new ArrayList<>(extensionContext.ruleFiles);
                Collections.sort(extensionRules);
                for (String extensionRule : extensionRules) {
                    if (seenUntrustedRules.add(extensionRule)) {
                        File sanitizedExtensionRule = new File(
                                sanitizedRulesDir,
                                String.format("extension-%04d.keep", sanitizedExtensionRuleIndex++));
                        R8RulePolicy.writeSanitized(
                                sanitizedExtensionRule,
                                R8RulePolicy.readAndValidate(new File(extensionRule), ruleBudget),
                                emptyRuleBase);
                        ruleFiles.add(sanitizedExtensionRule.getAbsolutePath());
                    }
                }
            }
        }

        File consumerRulesDir = new File(buildDir, "r8-consumer-rules");
        ruleFiles.addAll(collectConsumerRules(
                allJars,
                androidPackages,
                r8Version,
                consumerRulesDir,
                ruleBudget,
                emptyRuleBase));

        List<String> protectedJars = getProtectedJars(extensionJarMap);
        if (!protectedJars.isEmpty()) {
            File generatedRules = new File(buildDir, "generated-extension-rules.keep");
            try {
                writeProtectedJarKeepRules(protectedJars, generatedRules);
            } catch (IOException e) {
                throw new ExtenderException(e, "Failed to generate R8 rules for unconfigured extension jars");
            }
            R8RulePolicy.account(generatedRules, ruleBudget);
            ruleFiles.add(generatedRules.getAbsolutePath());
        }

        List<String> programJars = prepareProgramJars(
                allJars,
                new File(buildDir, "r8-program-jars"));
        File mappingFile = new File(buildDir, "mapping.txt");

        context.put("classes_dex_dir", buildDir.getAbsolutePath());
        context.put("jars", programJars);
        context.put("rules", new ArrayList<>(ruleFiles));
        context.put("mainDexRules", mainDexRules);
        context.put("useMainDexRules", !mainDexRules.isEmpty());
        context.put("mapping", mappingFile.getAbsolutePath());
        context.put("minAndroidSdkVersion", minAndroidSdkVersion);
        R8RulePolicy.requireEmptyBaseDirectory(emptyRuleBase);
        String r8Command;
        try {
            r8Command = templateExecutor.executeOnceWithoutLogging(
                    platformConfig.r8Cmd,
                    createR8CommandContext(context));
        } catch (RuntimeException e) {
            throw new ExtenderException(e, "R8 shrinking was requested, but its configuration could not be resolved");
        }
        commandExecutor.execute(r8Command, context);

        File[] dexFiles = ExtenderUtil.listFilesMatching(buildDir, "^classes(|[0-9]+)\\.dex$");
        Arrays.sort(dexFiles, (left, right) -> left.getName().compareTo(right.getName()));
        if (dexFiles.length == 0 || !mappingFile.isFile()) {
            throw new ExtenderException("R8 completed without producing classes.dex and mapping.txt");
        }
        return new BuildOutput(dexFiles, mappingFile);
    }
}

package com.defold.extender;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Server-side resource limits for R8 rule processing and generated keep rules. */
@Component
@ConfigurationProperties(prefix = "extender.r8")
public class R8Configuration {
    private int maxGeneratedExtensionClasses = 32 * 1024;
    private int maxClassfileHeaderBytes = 4 * 1024 * 1024;
    private long maxTotalClassfileHeaderBytes = 64L * 1024L * 1024L;
    private int maxRuleFiles = 1024;
    private long maxRuleFileBytes = 1024L * 1024L;
    private long maxTotalRuleBytes = 16L * 1024L * 1024L;

    public int getMaxGeneratedExtensionClasses() {
        return maxGeneratedExtensionClasses;
    }

    public void setMaxGeneratedExtensionClasses(int maxGeneratedExtensionClasses) {
        this.maxGeneratedExtensionClasses = requirePositive(
                maxGeneratedExtensionClasses,
                "max-generated-extension-classes");
    }

    public int getMaxClassfileHeaderBytes() {
        return maxClassfileHeaderBytes;
    }

    public void setMaxClassfileHeaderBytes(int maxClassfileHeaderBytes) {
        this.maxClassfileHeaderBytes = requirePositive(
                maxClassfileHeaderBytes,
                "max-classfile-header-bytes");
    }

    public long getMaxTotalClassfileHeaderBytes() {
        return maxTotalClassfileHeaderBytes;
    }

    public void setMaxTotalClassfileHeaderBytes(long maxTotalClassfileHeaderBytes) {
        this.maxTotalClassfileHeaderBytes = requirePositive(
                maxTotalClassfileHeaderBytes,
                "max-total-classfile-header-bytes");
    }

    public int getMaxRuleFiles() {
        return maxRuleFiles;
    }

    public void setMaxRuleFiles(int maxRuleFiles) {
        this.maxRuleFiles = requirePositive(maxRuleFiles, "max-rule-files");
    }

    public long getMaxRuleFileBytes() {
        return maxRuleFileBytes;
    }

    public void setMaxRuleFileBytes(long maxRuleFileBytes) {
        this.maxRuleFileBytes = requirePositive(maxRuleFileBytes, "max-rule-file-bytes");
    }

    public long getMaxTotalRuleBytes() {
        return maxTotalRuleBytes;
    }

    public void setMaxTotalRuleBytes(long maxTotalRuleBytes) {
        this.maxTotalRuleBytes = requirePositive(maxTotalRuleBytes, "max-total-rule-bytes");
    }

    private static int requirePositive(int value, String property) {
        if (value <= 0) {
            throw new IllegalArgumentException("extender.r8." + property + " must be positive");
        }
        return value;
    }

    private static long requirePositive(long value, String property) {
        if (value <= 0) {
            throw new IllegalArgumentException("extender.r8." + property + " must be positive");
        }
        return value;
    }
}

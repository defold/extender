package com.defold.extender.utils;

import java.io.File;
import java.util.List;

import com.defold.extender.ExtenderException;
import com.defold.extender.process.ProcessUtils;

public class FrameworkUtil {
    /**
     * Check if a framework is dynamically linked
     * Cocoapods (written in Ruby) uses the "macho" gem to do the same thing:
     * https://github.com/CocoaPods/CocoaPods/blob/master/lib/cocoapods/xcode/linkage_analyzer.rb#L16
     * https://github.com/Homebrew/ruby-macho/
     * @param framework The framework to check
     * @return true if dynamically linked
     */
    public static boolean isDynamicallyLinked(File framework) throws ExtenderException {
        String filename = framework.getName();
        // static library
        if (filename.endsWith(".a")) {
            return false;
        }
        if (framework.isDirectory() && filename.endsWith(".framework")) {
            String frameworkName = filename.replace(".framework", "");
            File frameworkBinary = new File(framework, frameworkName);
            String output = ProcessUtils.execCommand(List.of(
                "file",
                frameworkBinary.getAbsolutePath()), null, null);
            if (output.contains("dynamically linked shared library")) {
                return true;
            }
        }
        return false;
    }
}

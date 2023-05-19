package com.defold.extender;

import java.io.File;
import java.io.IOException;
import com.defold.extender.ProcessExecutor;
import com.defold.extender.ExtenderException;


public class FrameworkUtil {
    private static String execCommand(String command, File cwd) throws ExtenderException {
        ProcessExecutor pe = new ProcessExecutor();

        if (cwd != null) {
            pe.setCwd(cwd);
        }

        try {
            if (pe.execute(command) != 0) {
                throw new ExtenderException(pe.getOutput());
            }
        } catch (IOException | InterruptedException e) {
            throw new ExtenderException(e, pe.getOutput());
        }

        return pe.getOutput();
    }
    private static String execCommand(String command) throws ExtenderException {
        return execCommand(command, null);
    }

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
            String output = execCommand("file " + frameworkBinary.getAbsolutePath());
            if (output.contains("dynamically linked shared library")) {
                return true;
            }
        }
        return false;
    }
}

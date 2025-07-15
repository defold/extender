package com.defold.extender.process;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.defold.extender.ExtenderException;

public class ProcessUtils {
    public static String execCommand(String command) throws ExtenderException {
        return execCommand(command, null);
    }

    public static String execCommand(String command, File cwd) throws ExtenderException {
        return execCommand(command, cwd, null);
    }

    public static String execCommand(String command, File cwd, Map<String, String> env) throws ExtenderException {
        List<String> args = Arrays.stream(command.split(" "))
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
        return execCommand(args, cwd, env);
    }

    public static String execCommand(List<String> args, File cwd, Map<String, String> env) throws ExtenderException {
        ProcessExecutor pe = new ProcessExecutor();

        if (cwd != null) {
            pe.setCwd(cwd);
        }
        if (env != null) {
            pe.putEnv(env);
        }

        try {
            if (pe.execute(args) != 0) {
                throw new ExtenderException(pe.getOutput());
            }
        } catch (IOException | InterruptedException e) {
            throw new ExtenderException(e, pe.getOutput());
        }

        return pe.getOutput();
    }
}

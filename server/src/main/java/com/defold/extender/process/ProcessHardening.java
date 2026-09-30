package com.defold.extender.process;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;

/**
 * Hardening of the server process itself against its own sandboxed children. They run as the
 * same uid, and Landlock cannot hide {@code /proc} from them, so a build could read
 * {@code /proc/<server pid>/environ} and recover the secrets {@code env-deny-patterns} keeps
 * out of its own environment. A non-dumpable process is unreadable there (and in
 * {@code maps}, {@code fd}, {@code cwd}, {@code root}) to anything without CAP_SYS_PTRACE.
 */
final class ProcessHardening {
    private static final int PR_SET_DUMPABLE = 4;
    private static final int PR_GET_DUMPABLE = 3;

    private ProcessHardening() {}

    static boolean isLinux() {
        return System.getProperty("os.name", "").toLowerCase().contains("linux");
    }

    /** Clears the dumpable flag, returning what prctl(PR_GET_DUMPABLE) reports afterwards. */
    static int makeNonDumpable() throws Throwable {
        MethodHandle prctl = Linker.nativeLinker().downcallHandle(
            Linker.nativeLinker().defaultLookup().find("prctl").orElseThrow(),
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG,
                ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG));
        int rc = (int) prctl.invokeExact(PR_SET_DUMPABLE, 0L, 0L, 0L, 0L);
        if (rc != 0) {
            throw new IllegalStateException("prctl(PR_SET_DUMPABLE, 0) failed with " + rc);
        }
        return (int) prctl.invokeExact(PR_GET_DUMPABLE, 0L, 0L, 0L, 0L);
    }
}

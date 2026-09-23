/*
 * extender-sandbox: unprivileged launcher that confines one build subprocess.
 *
 *   extender-sandbox [--job DIR] [--ro PATH]... [--rw PATH]... [--rwx PATH]... [--net none|all]
 *                    [--cpu SEC] [--nproc N] [--fsize BYTES] [--nofile N] [--strict]
 *                    -- CMD [ARGS...]
 *   extender-sandbox --probe          print "landlock_abi=<n> seccomp=<yes|no>"
 *   extender-sandbox --check-sockets  print which socket families work here (self-test aid)
 *
 * The parent stays outside the sandbox as a tiny init: it is a child subreaper, forwards a
 * SIGTERM/SIGINT/SIGHUP as SIGKILL to the command's process group, and after the command
 * exits freezes and kills every process still below it, so nothing outlives the command.
 *
 * --job names the job directory: the build can plant links anywhere inside it, so a --rw or
 * --rwx path inside it is only granted when the descriptor it opens still names a place
 * inside it.
 *
 * The child, before execvp(): own process group, rlimits, PR_SET_NO_NEW_PRIVS, a Landlock
 * ruleset (filesystem allowlist; TCP and IPC scoping where the kernel supports them) and a
 * seccomp filter (no socket() outside AF_UNIX unless --net all; no ptrace, mounts,
 * namespaces, keyrings, bpf, ...).
 *
 * Everything works without capabilities inside a default Docker container: the Landlock and
 * seccomp syscalls are in Docker's default seccomp allowlist, namespaces are not, which is why
 * this is not bubblewrap.
 *
 * Landlock UAPI structures and constants are declared locally so the file builds against any
 * libc/kernel headers; the ABI is probed at run time and unsupported rights are masked
 * (the pattern documented in the kernel's samples/landlock/sandboxer.c).
 */
#define _GNU_SOURCE
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <signal.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/prctl.h>
#include <sys/resource.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <unistd.h>

#include <linux/audit.h>
#include <linux/filter.h>
#include <linux/seccomp.h>

/* ---- Landlock UAPI (kernel >= 5.13; local copies so older headers work) ---- */

#ifndef __NR_landlock_create_ruleset
#define __NR_landlock_create_ruleset 444
#endif
#ifndef __NR_landlock_add_rule
#define __NR_landlock_add_rule 445
#endif
#ifndef __NR_landlock_restrict_self
#define __NR_landlock_restrict_self 446
#endif

#define LL_CREATE_RULESET_VERSION (1U << 0)
#define LL_RULE_PATH_BENEATH 1

#define LL_ACCESS_FS_EXECUTE (1ULL << 0)
#define LL_ACCESS_FS_WRITE_FILE (1ULL << 1)
#define LL_ACCESS_FS_READ_FILE (1ULL << 2)
#define LL_ACCESS_FS_READ_DIR (1ULL << 3)
#define LL_ACCESS_FS_REMOVE_DIR (1ULL << 4)
#define LL_ACCESS_FS_REMOVE_FILE (1ULL << 5)
#define LL_ACCESS_FS_MAKE_CHAR (1ULL << 6)
#define LL_ACCESS_FS_MAKE_DIR (1ULL << 7)
#define LL_ACCESS_FS_MAKE_REG (1ULL << 8)
#define LL_ACCESS_FS_MAKE_SOCK (1ULL << 9)
#define LL_ACCESS_FS_MAKE_FIFO (1ULL << 10)
#define LL_ACCESS_FS_MAKE_BLOCK (1ULL << 11)
#define LL_ACCESS_FS_MAKE_SYM (1ULL << 12)
#define LL_ACCESS_FS_REFER (1ULL << 13)    /* ABI 2 */
#define LL_ACCESS_FS_TRUNCATE (1ULL << 14) /* ABI 3 */
#define LL_ACCESS_FS_IOCTL_DEV (1ULL << 15) /* ABI 5 */

#define LL_ACCESS_NET_BIND_TCP (1ULL << 0)    /* ABI 4 */
#define LL_ACCESS_NET_CONNECT_TCP (1ULL << 1) /* ABI 4 */

#define LL_SCOPE_ABSTRACT_UNIX_SOCKET (1ULL << 0) /* ABI 6 */
#define LL_SCOPE_SIGNAL (1ULL << 1)               /* ABI 6 */

struct ll_ruleset_attr {
    uint64_t handled_access_fs;
    uint64_t handled_access_net;
    uint64_t scoped;
};

struct ll_path_beneath_attr {
    uint64_t allowed_access;
    int32_t parent_fd;
} __attribute__((packed));

#define LL_ACCESS_FS_ABI1 \
    (LL_ACCESS_FS_EXECUTE | LL_ACCESS_FS_WRITE_FILE | LL_ACCESS_FS_READ_FILE | LL_ACCESS_FS_READ_DIR | \
     LL_ACCESS_FS_REMOVE_DIR | LL_ACCESS_FS_REMOVE_FILE | LL_ACCESS_FS_MAKE_CHAR | LL_ACCESS_FS_MAKE_DIR | \
     LL_ACCESS_FS_MAKE_REG | LL_ACCESS_FS_MAKE_SOCK | LL_ACCESS_FS_MAKE_FIFO | LL_ACCESS_FS_MAKE_BLOCK | \
     LL_ACCESS_FS_MAKE_SYM)

/* Rights that make sense on a regular file (a rule on a file must not carry directory rights). */
#define LL_ACCESS_FILE \
    (LL_ACCESS_FS_EXECUTE | LL_ACCESS_FS_WRITE_FILE | LL_ACCESS_FS_READ_FILE | LL_ACCESS_FS_TRUNCATE | \
     LL_ACCESS_FS_IOCTL_DEV)

#define LL_READ_ONLY (LL_ACCESS_FS_EXECUTE | LL_ACCESS_FS_READ_FILE | LL_ACCESS_FS_READ_DIR)

/* Writable: everything except creating device nodes and executing. */
#define LL_READ_WRITE \
    (LL_ACCESS_FS_READ_FILE | LL_ACCESS_FS_READ_DIR | LL_ACCESS_FS_WRITE_FILE | LL_ACCESS_FS_REMOVE_DIR | \
     LL_ACCESS_FS_REMOVE_FILE | LL_ACCESS_FS_MAKE_DIR | LL_ACCESS_FS_MAKE_REG | LL_ACCESS_FS_MAKE_SOCK | \
     LL_ACCESS_FS_MAKE_FIFO | LL_ACCESS_FS_MAKE_SYM | LL_ACCESS_FS_REFER | LL_ACCESS_FS_TRUNCATE | \
     LL_ACCESS_FS_IOCTL_DEV)

static long ll_create_ruleset(const struct ll_ruleset_attr *attr, size_t size, uint32_t flags) {
    return syscall(__NR_landlock_create_ruleset, attr, size, flags);
}

static long ll_add_rule(int ruleset_fd, int rule_type, const void *rule_attr, uint32_t flags) {
    return syscall(__NR_landlock_add_rule, ruleset_fd, rule_type, rule_attr, flags);
}

static long ll_restrict_self(int ruleset_fd, uint32_t flags) {
    return syscall(__NR_landlock_restrict_self, ruleset_fd, flags);
}

/* ---- seccomp ---- */

#ifndef __NR_seccomp
#define __NR_seccomp 317
#endif
#ifndef SECCOMP_SET_MODE_FILTER
#define SECCOMP_SET_MODE_FILTER 1
#endif
#ifndef SECCOMP_GET_ACTION_AVAIL
#define SECCOMP_GET_ACTION_AVAIL 2
#endif
#ifndef SECCOMP_RET_ERRNO
#define SECCOMP_RET_ERRNO 0x00050000U
#endif
#ifndef SECCOMP_RET_ALLOW
#define SECCOMP_RET_ALLOW 0x7fff0000U
#endif
#ifndef AUDIT_ARCH_I386
#define AUDIT_ARCH_I386 0x40000003
#endif
#ifndef AUDIT_ARCH_X86_64
#define AUDIT_ARCH_X86_64 0xc000003e
#endif
#ifndef AUDIT_ARCH_AARCH64
#define AUDIT_ARCH_AARCH64 0xc00000b7
#endif

#define X32_SYSCALL_BIT 0x40000000

/*
 * Syscalls a build tool never needs and an attacker would want. Numbers per architecture;
 * the native table is checked against the libc headers at compile time below.
 */
struct arch_table {
    uint32_t audit_arch;
    int32_t socket;     /* -1: none */
    int32_t socketcall; /* i386 only */
    int32_t denied[32];
    int denied_count;
};

static const struct arch_table ARCH_X86_64 = {
    .audit_arch = AUDIT_ARCH_X86_64,
    .socket = 41,
    .socketcall = -1,
    .denied = {101 /*ptrace*/, 310 /*process_vm_readv*/, 311 /*process_vm_writev*/, 165 /*mount*/,
               166 /*umount2*/, 272 /*unshare*/, 308 /*setns*/, 155 /*pivot_root*/, 161 /*chroot*/,
               250 /*keyctl*/, 248 /*add_key*/, 249 /*request_key*/, 321 /*bpf*/, 298 /*perf_event_open*/,
               323 /*userfaultfd*/, 425 /*io_uring_setup*/, 246 /*kexec_load*/, 169 /*reboot*/,
               167 /*swapon*/, 175 /*init_module*/, 313 /*finit_module*/, 176 /*delete_module*/,
               428 /*open_tree*/, 429 /*move_mount*/, 430 /*fsopen*/, 431 /*fsconfig*/, 432 /*fsmount*/,
               433 /*fspick*/, 442 /*mount_setattr*/},
    .denied_count = 29,
};

static const struct arch_table ARCH_AARCH64 = {
    .audit_arch = AUDIT_ARCH_AARCH64,
    .socket = 198,
    .socketcall = -1,
    .denied = {117 /*ptrace*/, 270 /*process_vm_readv*/, 271 /*process_vm_writev*/, 40 /*mount*/,
               39 /*umount2*/, 97 /*unshare*/, 268 /*setns*/, 41 /*pivot_root*/, 51 /*chroot*/,
               219 /*keyctl*/, 217 /*add_key*/, 218 /*request_key*/, 280 /*bpf*/, 241 /*perf_event_open*/,
               282 /*userfaultfd*/, 425 /*io_uring_setup*/, 104 /*kexec_load*/, 142 /*reboot*/,
               224 /*swapon*/, 105 /*init_module*/, 273 /*finit_module*/, 106 /*delete_module*/,
               428 /*open_tree*/, 429 /*move_mount*/, 430 /*fsopen*/, 431 /*fsconfig*/, 432 /*fsmount*/,
               433 /*fspick*/, 442 /*mount_setattr*/},
    .denied_count = 29,
};

/* 32-bit x86: the wine images enable i386 multiarch, so its helpers must not be killed. */
static const struct arch_table ARCH_I386 = {
    .audit_arch = AUDIT_ARCH_I386,
    .socket = 359,
    .socketcall = 102,
    .denied = {26 /*ptrace*/, 347 /*process_vm_readv*/, 348 /*process_vm_writev*/, 21 /*mount*/,
               22 /*umount*/, 52 /*umount2*/, 310 /*unshare*/, 346 /*setns*/, 217 /*pivot_root*/,
               61 /*chroot*/, 288 /*keyctl*/, 286 /*add_key*/, 287 /*request_key*/, 357 /*bpf*/,
               336 /*perf_event_open*/, 374 /*userfaultfd*/, 425 /*io_uring_setup*/, 283 /*kexec_load*/,
               88 /*reboot*/, 87 /*swapon*/, 128 /*init_module*/, 350 /*finit_module*/, 129 /*delete_module*/,
               428 /*open_tree*/, 429 /*move_mount*/, 430 /*fsopen*/, 431 /*fsconfig*/, 432 /*fsmount*/,
               433 /*fspick*/, 442 /*mount_setattr*/},
    .denied_count = 30,
};

/* Compile-time check of the native table against the real syscall numbers. */
#if defined(__x86_64__)
_Static_assert(__NR_socket == 41 && __NR_ptrace == 101 && __NR_mount == 165 && __NR_unshare == 272 &&
                   __NR_setns == 308 && __NR_bpf == 321 && __NR_userfaultfd == 323 &&
                   __NR_process_vm_readv == 310 && __NR_perf_event_open == 298 && __NR_keyctl == 250,
               "x86_64 syscall table mismatch");
#elif defined(__aarch64__)
_Static_assert(__NR_socket == 198 && __NR_ptrace == 117 && __NR_mount == 40 && __NR_unshare == 97 &&
                   __NR_setns == 268 && __NR_bpf == 280 && __NR_userfaultfd == 282 &&
                   __NR_process_vm_readv == 270 && __NR_perf_event_open == 241 && __NR_keyctl == 219,
               "aarch64 syscall table mismatch");
#else
#error "extender-sandbox supports x86_64 and aarch64 builds only"
#endif

#define BPF_MAX 512

struct bpf_program {
    struct sock_filter insns[BPF_MAX];
    int len;
};

static void bpf_emit(struct bpf_program *prog, struct sock_filter insn) {
    if (prog->len >= BPF_MAX) {
        fprintf(stderr, "extender-sandbox: seccomp program too large\n");
        exit(127);
    }
    prog->insns[prog->len++] = insn;
}

#define EMIT_STMT(p, code, k) bpf_emit((p), (struct sock_filter)BPF_STMT((code), (k)))
#define EMIT_JUMP(p, code, k, jt, jf) bpf_emit((p), (struct sock_filter)BPF_JUMP((code), (k), (jt), (jf)))
#define RET_ERRNO(e) (SECCOMP_RET_ERRNO | ((e) & 0xffffU))

/* One per-architecture section: deny list, then the socket family check, then allow. */
static void bpf_emit_arch(struct bpf_program *prog, const struct arch_table *t, int net_none) {
    EMIT_STMT(prog, BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, nr));
    if (t->audit_arch == AUDIT_ARCH_X86_64) {
        /* x32 ABI shares the arch value but not the numbers: refuse it. */
        EMIT_JUMP(prog, BPF_JMP | BPF_JGE | BPF_K, X32_SYSCALL_BIT, 0, 1);
        EMIT_STMT(prog, BPF_RET | BPF_K, RET_ERRNO(EPERM));
    }
    for (int i = 0; i < t->denied_count; i++) {
        EMIT_JUMP(prog, BPF_JMP | BPF_JEQ | BPF_K, (uint32_t)t->denied[i], 0, 1);
        EMIT_STMT(prog, BPF_RET | BPF_K, RET_ERRNO(EPERM));
    }
    if (net_none) {
        if (t->socketcall >= 0) {
            /* socketcall(SYS_SOCKET=1, args): the family lives in user memory, so refuse it whole. */
            EMIT_JUMP(prog, BPF_JMP | BPF_JEQ | BPF_K, (uint32_t)t->socketcall, 0, 4);
            EMIT_STMT(prog, BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, args[0]));
            EMIT_JUMP(prog, BPF_JMP | BPF_JEQ | BPF_K, 1, 0, 1);
            EMIT_STMT(prog, BPF_RET | BPF_K, RET_ERRNO(EAFNOSUPPORT));
            EMIT_STMT(prog, BPF_RET | BPF_K, SECCOMP_RET_ALLOW);
            EMIT_STMT(prog, BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, nr));
        }
        /* socket(domain, ...): only AF_UNIX may be created. */
        EMIT_JUMP(prog, BPF_JMP | BPF_JEQ | BPF_K, (uint32_t)t->socket, 0, 4);
        EMIT_STMT(prog, BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, args[0]));
        EMIT_JUMP(prog, BPF_JMP | BPF_JEQ | BPF_K, AF_UNIX, 0, 1);
        EMIT_STMT(prog, BPF_RET | BPF_K, SECCOMP_RET_ALLOW);
        EMIT_STMT(prog, BPF_RET | BPF_K, RET_ERRNO(EAFNOSUPPORT));
    }
    EMIT_STMT(prog, BPF_RET | BPF_K, SECCOMP_RET_ALLOW);
}

static void bpf_build(struct bpf_program *prog, int net_none) {
    const struct arch_table *tables[] = {&ARCH_X86_64, &ARCH_AARCH64, &ARCH_I386};
    const int n = 3;
    struct bpf_program sections[3];
    memset(prog, 0, sizeof(*prog));
    for (int i = 0; i < n; i++) {
        memset(&sections[i], 0, sizeof(sections[i]));
        bpf_emit_arch(&sections[i], tables[i], net_none);
    }

    /* Dispatch on the architecture; anything else gets EPERM, never SIGKILL. */
    EMIT_STMT(prog, BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, arch));
    int dispatch_len = 2 * n + 1;
    int offset = 0;
    for (int i = 0; i < n; i++) {
        /* JA k continues at (index of the JA) + 1 + k; the sections follow the dispatcher */
        int ja_index = 2 + 2 * i;
        int target = 1 + dispatch_len + offset;
        EMIT_JUMP(prog, BPF_JMP | BPF_JEQ | BPF_K, tables[i]->audit_arch, 0, 1);
        EMIT_STMT(prog, BPF_JMP | BPF_JA, (uint32_t)(target - ja_index - 1));
        offset += sections[i].len;
    }
    EMIT_STMT(prog, BPF_RET | BPF_K, RET_ERRNO(EPERM));
    for (int i = 0; i < n; i++) {
        for (int j = 0; j < sections[i].len; j++) {
            bpf_emit(prog, sections[i].insns[j]);
        }
    }
}

static int seccomp_available(void) {
    uint32_t action = SECCOMP_RET_ERRNO;
    return syscall(__NR_seccomp, SECCOMP_GET_ACTION_AVAIL, 0, &action) == 0;
}

static int apply_seccomp(int net_none) {
    struct bpf_program prog;
    bpf_build(&prog, net_none);
    struct sock_fprog fprog = {.len = (unsigned short)prog.len, .filter = prog.insns};
    return syscall(__NR_seccomp, SECCOMP_SET_MODE_FILTER, 0, &fprog);
}

/* ---- Landlock ---- */

static int landlock_abi(void) {
    long abi = ll_create_ruleset(NULL, 0, LL_CREATE_RULESET_VERSION);
    return abi < 0 ? 0 : (int)abi;
}

struct paths {
    const char *items[256];
    int count;
};

static void paths_add(struct paths *p, const char *path) {
    if (p->count >= (int)(sizeof(p->items) / sizeof(p->items[0]))) {
        fprintf(stderr, "extender-sandbox: too many paths\n");
        exit(64);
    }
    p->items[p->count++] = path;
}

static const char *job_dir = NULL;
static char job_dir_real[PATH_MAX];

static int has_dir_prefix(const char *path, const char *dir) {
    size_t n = strlen(dir);
    return strncmp(path, dir, n) == 0 && (path[n] == '/' || path[n] == '\0');
}

/* The path the kernel resolved for fd lies inside the job directory. */
static int fd_inside_job(int fd) {
    char link[64];
    char resolved[PATH_MAX];
    snprintf(link, sizeof(link), "/proc/self/fd/%d", fd);
    ssize_t len = readlink(link, resolved, sizeof(resolved) - 1);
    if (len < 0) {
        return 0;
    }
    resolved[len] = '\0';
    return has_dir_prefix(resolved, job_dir_real);
}

static int add_path_rules(int ruleset_fd, const struct paths *paths, uint64_t access, uint64_t handled,
                          int confine_to_job) {
    for (int i = 0; i < paths->count; i++) {
        int fd = open(paths->items[i], O_PATH | O_CLOEXEC);
        if (fd < 0) {
            /* Lists are shared between images; a directory a given image lacks is simply not granted. */
            continue;
        }
        if (confine_to_job && job_dir != NULL && has_dir_prefix(paths->items[i], job_dir) && !fd_inside_job(fd)) {
            close(fd);
            fprintf(stderr, "extender-sandbox: %s resolves outside the job directory %s; not granting it\n",
                    paths->items[i], job_dir);
            return -1;
        }
        struct stat st;
        uint64_t allowed = access & handled;
        if (fstat(fd, &st) == 0 && !S_ISDIR(st.st_mode)) {
            allowed &= LL_ACCESS_FILE;
        }
        struct ll_path_beneath_attr attr = {.allowed_access = allowed, .parent_fd = fd};
        int rc = 0;
        if (allowed != 0) {
            rc = (int)ll_add_rule(ruleset_fd, LL_RULE_PATH_BENEATH, &attr, 0);
        }
        int saved = errno;
        close(fd);
        if (rc != 0) {
            errno = saved;
            fprintf(stderr, "extender-sandbox: landlock rule for %s: %s\n", paths->items[i], strerror(errno));
            return -1;
        }
    }
    return 0;
}

/*
 * A rule on a single file is accepted by landlock_add_rule but grants nothing on some
 * filesystems (9p behind Docker Desktop bind mounts). Say so, because the tool then fails
 * three layers up with a message that does not mention the sandbox.
 */
static void check_file_grants(const struct paths *ro) {
    for (int i = 0; i < ro->count; i++) {
        struct stat st;
        if (stat(ro->items[i], &st) != 0 || S_ISDIR(st.st_mode)) {
            continue;
        }
        int fd = open(ro->items[i], O_RDONLY | O_CLOEXEC);
        if (fd < 0 && errno == EACCES) {
            fprintf(stderr, "extender-sandbox: landlock rule for %s was accepted but does not grant access; "
                            "grant its directory instead\n", ro->items[i]);
        } else if (fd >= 0) {
            close(fd);
        }
    }
}

static int apply_landlock(int abi, const struct paths *ro, const struct paths *rw, const struct paths *rwx,
                          int net_none) {
    uint64_t handled_fs = LL_ACCESS_FS_ABI1;
    if (abi >= 2) handled_fs |= LL_ACCESS_FS_REFER;
    if (abi >= 3) handled_fs |= LL_ACCESS_FS_TRUNCATE;
    if (abi >= 5) handled_fs |= LL_ACCESS_FS_IOCTL_DEV;

    struct ll_ruleset_attr attr = {.handled_access_fs = handled_fs, .handled_access_net = 0, .scoped = 0};
    size_t size = offsetof(struct ll_ruleset_attr, handled_access_net);
    if (abi >= 4) {
        if (net_none) {
            /* Handled with no rules: every TCP bind/connect is denied. */
            attr.handled_access_net = LL_ACCESS_NET_BIND_TCP | LL_ACCESS_NET_CONNECT_TCP;
        }
        size = offsetof(struct ll_ruleset_attr, scoped);
    }
    if (abi >= 6) {
        /* No signals to, and no abstract unix sockets of, processes outside the sandbox. */
        attr.scoped = LL_SCOPE_ABSTRACT_UNIX_SOCKET | LL_SCOPE_SIGNAL;
        size = sizeof(attr);
    }

    int ruleset_fd = (int)ll_create_ruleset(&attr, size, 0);
    if (ruleset_fd < 0) {
        fprintf(stderr, "extender-sandbox: landlock_create_ruleset: %s\n", strerror(errno));
        return -1;
    }
    int rc = 0;
    if (add_path_rules(ruleset_fd, ro, LL_READ_ONLY, handled_fs, 0) != 0) rc = -1;
    if (rc == 0 && add_path_rules(ruleset_fd, rw, LL_READ_WRITE, handled_fs, 1) != 0) rc = -1;
    if (rc == 0 && add_path_rules(ruleset_fd, rwx, LL_READ_WRITE | LL_ACCESS_FS_EXECUTE, handled_fs, 1) != 0) rc = -1;
    if (rc == 0 && ll_restrict_self(ruleset_fd, 0) != 0) {
        fprintf(stderr, "extender-sandbox: landlock_restrict_self: %s\n", strerror(errno));
        rc = -1;
    }
    close(ruleset_fd);
    return rc;
}

/* ---- rlimits ---- */

static int set_limit(int resource, long long value, const char *name) {
    if (value <= 0) {
        return 0;
    }
    rlim_t wanted = (rlim_t)value;
    struct rlimit current;
    /* An unprivileged process cannot raise a hard limit; a lower host limit is simply kept. */
    if (getrlimit(resource, &current) == 0 && current.rlim_max != RLIM_INFINITY && wanted > current.rlim_max) {
        wanted = current.rlim_max;
    }
    struct rlimit lim = {.rlim_cur = wanted, .rlim_max = wanted};
    if (setrlimit(resource, &lim) != 0) {
        fprintf(stderr, "extender-sandbox: setrlimit(%s=%lld): %s\n", name, value, strerror(errno));
        return -1;
    }
    return 0;
}

/* ---- parent: tiny init ---- */

static volatile sig_atomic_t received_signal = 0;
static pid_t child_pid = 0;

static void on_signal(int sig) {
    received_signal = sig;
    if (child_pid > 0) {
        kill(-child_pid, SIGKILL);
    }
}

struct proc_entry {
    pid_t pid;
    pid_t ppid;
    char state;
};

struct proc_table {
    struct proc_entry *items;
    size_t count;
    size_t capacity;
};

static void read_processes(struct proc_table *table) {
    table->count = 0;
    DIR *proc = opendir("/proc");
    if (proc == NULL) {
        return;
    }
    struct dirent *entry;
    while ((entry = readdir(proc)) != NULL) {
        /* only pid entries, and only ones that fit: a truncated path would stat the wrong process */
        if (strspn(entry->d_name, "0123456789") != strlen(entry->d_name) || entry->d_name[0] == '\0') {
            continue;
        }
        char path[64];
        if (snprintf(path, sizeof(path), "/proc/%s/stat", entry->d_name) >= (int)sizeof(path)) {
            continue;
        }
        FILE *f = fopen(path, "r");
        if (f == NULL) {
            continue;
        }
        char line[512];
        char *ok = fgets(line, sizeof(line), f);
        fclose(f);
        if (ok == NULL) {
            continue;
        }
        /* "<pid> (<comm>) <state> <ppid> ..." — comm may contain spaces, so scan from the last ')'. */
        char *end = strrchr(line, ')');
        if (end == NULL) {
            continue;
        }
        int ppid = -1;
        char state;
        if (sscanf(end + 1, " %c %d", &state, &ppid) != 2) {
            continue;
        }
        if (table->count == table->capacity) {
            size_t capacity = table->capacity == 0 ? 1024 : table->capacity * 2;
            struct proc_entry *items = realloc(table->items, capacity * sizeof(*items));
            if (items == NULL) {
                break;
            }
            table->items = items;
            table->capacity = capacity;
        }
        table->items[table->count++] = (struct proc_entry){.pid = (pid_t)atoi(entry->d_name), .ppid = ppid, .state = state};
    }
    closedir(proc);
}

static int compare_pids(const void *a, const void *b) {
    pid_t x = ((const struct proc_entry *)a)->pid;
    pid_t y = ((const struct proc_entry *)b)->pid;
    return (x > y) - (x < y);
}

static long find_process(const struct proc_table *table, pid_t pid) {
    struct proc_entry key = {.pid = pid};
    struct proc_entry *found = bsearch(&key, table->items, table->count, sizeof(key), compare_pids);
    return found == NULL ? -1 : (long)(found - table->items);
}

/* Sets below[i] for every process under this one, however deep. */
static void mark_descendants(struct proc_table *table, char *below) {
    pid_t self = getpid();
    qsort(table->items, table->count, sizeof(*table->items), compare_pids);
    memset(below, 0, table->count);
    /* a parent may have a higher pid than its child, so sweep until nothing changes */
    for (int changed = 1; changed;) {
        changed = 0;
        for (size_t i = 0; i < table->count; i++) {
            if (below[i]) {
                continue;
            }
            long parent = find_process(table, table->items[i].ppid);
            if (table->items[i].ppid == self || (parent >= 0 && below[parent])) {
                below[i] = 1;
                changed = 1;
            }
        }
    }
}

/*
 * Kills every process below this one: the command's own tree and everything reparented here
 * by the subreaper rule, setsid'd daemons included. Stopped processes cannot fork, so the tree
 * is frozen first, sweep after sweep until a sweep finds nothing left running, and only then
 * killed; a fork-and-exit chain would otherwise stay a generation ahead of the killing.
 */
static void reap_everything(void) {
    struct proc_table table = {0};
    char *below = NULL;
    for (int round = 0; round < 10000; round++) {
        read_processes(&table);
        char *grown = realloc(below, table.count > 0 ? table.count : 1);
        if (grown == NULL) {
            break;
        }
        below = grown;
        mark_descendants(&table, below);

        int running = 0;
        for (size_t i = 0; i < table.count; i++) {
            if (below[i] && table.items[i].state != 'T' && table.items[i].state != 't' && table.items[i].state != 'Z'
                && table.items[i].state != 'X') {
                kill(table.items[i].pid, SIGSTOP);
                running++;
            }
        }
        if (running > 0) {
            /* let the stops land, then look again for anything forked meanwhile */
            usleep(1000);
            continue;
        }
        for (size_t i = 0; i < table.count; i++) {
            if (below[i]) {
                kill(table.items[i].pid, SIGKILL);
            }
        }

        int status;
        pid_t pid;
        int reaped = 0;
        while ((pid = waitpid(-1, &status, WNOHANG)) > 0) {
            reaped++;
        }
        if (pid < 0 && errno == ECHILD) {
            free(table.items);
            free(below);
            return;
        }
        if (reaped == 0) {
            /* A child that is still dying: give it a moment before scanning again. */
            usleep(10000);
        }
    }
    fprintf(stderr, "extender-sandbox: processes of the command are still running after teardown\n");
    free(table.items);
    free(below);
}

/* ---- self-test aid ---- */

static void check_sockets(void) {
    const struct {
        int family;
        const char *name;
    } families[] = {{AF_UNIX, "unix"}, {AF_INET, "inet"}, {AF_INET6, "inet6"}};
    for (size_t i = 0; i < sizeof(families) / sizeof(families[0]); i++) {
        int fd = socket(families[i].family, SOCK_STREAM | SOCK_CLOEXEC, 0);
        if (fd >= 0) {
            close(fd);
            printf("%s=ok%s", families[i].name, i + 1 < 3 ? " " : "\n");
        } else {
            printf("%s=%s%s", families[i].name,
                   errno == EAFNOSUPPORT ? "EAFNOSUPPORT" : errno == EPERM ? "EPERM" : strerror(errno),
                   i + 1 < 3 ? " " : "\n");
        }
    }
}

static void usage(void) {
    fprintf(stderr,
            "usage: extender-sandbox [--job DIR] [--ro PATH]... [--rw PATH]... [--rwx PATH]... [--net none|all]\n"
            "                        [--cpu SEC] [--nproc N] [--fsize BYTES] [--nofile N] [--strict]\n"
            "                        -- CMD [ARGS...]\n"
            "       extender-sandbox --probe\n");
    exit(64);
}

static long long parse_number(const char *value, const char *flag) {
    char *end = NULL;
    errno = 0;
    long long n = strtoll(value, &end, 10);
    if (errno != 0 || end == value || *end != '\0' || n < 0) {
        fprintf(stderr, "extender-sandbox: invalid value for %s: %s\n", flag, value);
        exit(64);
    }
    return n;
}

int main(int argc, char **argv) {
    struct paths ro = {.count = 0}, rw = {.count = 0}, rwx = {.count = 0};
    int net_none = 1;
    int strict = 0;
    long long cpu = 0, nproc = 0, fsize = 0, nofile = 0;
    int cmd_index = -1;

    for (int i = 1; i < argc; i++) {
        const char *arg = argv[i];
        if (strcmp(arg, "--probe") == 0) {
            printf("landlock_abi=%d seccomp=%s\n", landlock_abi(), seccomp_available() ? "yes" : "no");
            return 0;
        }
        if (strcmp(arg, "--check-sockets") == 0) {
            check_sockets();
            return 0;
        }
        if (strcmp(arg, "--") == 0) {
            cmd_index = i + 1;
            break;
        }
        if (strcmp(arg, "--strict") == 0) {
            strict = 1;
            continue;
        }
        if (i + 1 >= argc) {
            usage();
        }
        const char *value = argv[++i];
        if (strcmp(arg, "--job") == 0) {
            if (realpath(value, job_dir_real) == NULL) {
                fprintf(stderr, "extender-sandbox: job directory %s: %s\n", value, strerror(errno));
                return 127;
            }
            job_dir = value;
        } else if (strcmp(arg, "--ro") == 0) {
            paths_add(&ro, value);
        } else if (strcmp(arg, "--rw") == 0) {
            paths_add(&rw, value);
        } else if (strcmp(arg, "--rwx") == 0) {
            paths_add(&rwx, value);
        } else if (strcmp(arg, "--net") == 0) {
            if (strcmp(value, "none") == 0) {
                net_none = 1;
            } else if (strcmp(value, "all") == 0) {
                net_none = 0;
            } else {
                usage();
            }
        } else if (strcmp(arg, "--cpu") == 0) {
            cpu = parse_number(value, arg);
        } else if (strcmp(arg, "--nproc") == 0) {
            nproc = parse_number(value, arg);
        } else if (strcmp(arg, "--fsize") == 0) {
            fsize = parse_number(value, arg);
        } else if (strcmp(arg, "--nofile") == 0) {
            nofile = parse_number(value, arg);
        } else {
            usage();
        }
    }
    if (cmd_index < 0 || cmd_index >= argc) {
        usage();
    }

    if (prctl(PR_SET_CHILD_SUBREAPER, 1, 0, 0, 0) != 0) {
        fprintf(stderr, "extender-sandbox: PR_SET_CHILD_SUBREAPER: %s\n", strerror(errno));
        return 127;
    }

    struct sigaction sa;
    memset(&sa, 0, sizeof(sa));
    sa.sa_handler = on_signal;
    sigemptyset(&sa.sa_mask);
    sigaction(SIGTERM, &sa, NULL);
    sigaction(SIGINT, &sa, NULL);
    sigaction(SIGHUP, &sa, NULL);

    pid_t pid = fork();
    if (pid < 0) {
        fprintf(stderr, "extender-sandbox: fork: %s\n", strerror(errno));
        return 127;
    }

    if (pid == 0) {
        /* ---- child: confine, then exec ---- */
        setpgid(0, 0);
        signal(SIGTERM, SIG_DFL);
        signal(SIGINT, SIG_DFL);
        signal(SIGHUP, SIG_DFL);

        if (set_limit(RLIMIT_CPU, cpu, "RLIMIT_CPU") != 0 || set_limit(RLIMIT_NPROC, nproc, "RLIMIT_NPROC") != 0 ||
            set_limit(RLIMIT_FSIZE, fsize, "RLIMIT_FSIZE") != 0 ||
            set_limit(RLIMIT_NOFILE, nofile, "RLIMIT_NOFILE") != 0) {
            _exit(127);
        }
        if (prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0) != 0) {
            fprintf(stderr, "extender-sandbox: PR_SET_NO_NEW_PRIVS: %s\n", strerror(errno));
            _exit(127);
        }

        int abi = landlock_abi();
        if (abi > 0) {
            if (apply_landlock(abi, &ro, &rw, &rwx, net_none) != 0) {
                _exit(127);
            }
            check_file_grants(&ro);
        } else if (strict) {
            fprintf(stderr, "extender-sandbox: Landlock is unavailable on this kernel (strict mode)\n");
            _exit(127);
        }

        if (apply_seccomp(net_none) != 0) {
            if (strict) {
                fprintf(stderr, "extender-sandbox: seccomp filter rejected: %s (strict mode)\n", strerror(errno));
                _exit(127);
            }
            /*
             * Below Landlock ABI 4 the network denial lives entirely in this filter, and the
             * ptrace/mount/unshare/bpf/io_uring denials live in it at every ABI, so a command
             * running without it is far less confined than the server believes. Say so: the
             * launcher's stderr is merged into the build log.
             */
            fprintf(stderr, "extender-sandbox: seccomp filter rejected: %s; continuing without it "
                    "(no syscall denylist%s)\n", strerror(errno),
                    (net_none && abi < 4) ? ", network NOT denied" : "");
        }

        execvp(argv[cmd_index], &argv[cmd_index]);
        fprintf(stderr, "extender-sandbox: exec %s: %s\n", argv[cmd_index], strerror(errno));
        _exit(127);
    }

    /* ---- parent: wait, then make sure nothing survives ---- */
    child_pid = pid;
    setpgid(pid, pid); /* both sides do this; whichever runs first wins, EACCES after exec is fine */
    if (received_signal != 0) {
        kill(-pid, SIGKILL);
    }

    int status = 0;
    for (;;) {
        pid_t w = waitpid(pid, &status, 0);
        if (w == pid) {
            break;
        }
        if (w < 0 && errno == EINTR) {
            continue;
        }
        fprintf(stderr, "extender-sandbox: waitpid: %s\n", strerror(errno));
        status = 127 << 8;
        break;
    }

    kill(-pid, SIGKILL);
    reap_everything();

    if (received_signal != 0) {
        return 128 + received_signal;
    }
    if (WIFEXITED(status)) {
        return WEXITSTATUS(status);
    }
    if (WIFSIGNALED(status)) {
        return 128 + WTERMSIG(status);
    }
    return 127;
}

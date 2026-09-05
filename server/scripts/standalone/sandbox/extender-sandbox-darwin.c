/*
 * extender-sandbox (macOS): confines one build subprocess on the standalone builders.
 *
 *   extender-sandbox --profile SBPL [--cpu SEC] [--nproc N] [--fsize BYTES] [--nofile N]
 *                    [--strict] -- CMD [ARGS...]
 *   extender-sandbox --probe          prints "seatbelt=yes|no sandbox_exec=/usr/bin/sandbox-exec"
 *   extender-sandbox --check-sockets  prints which socket families can be created (self-test aid)
 *
 * Layers, from the outside in:
 *   1. process group    the command and everything it spawns share one process group, which the
 *                       parent kills when the command exits or when it receives SIGTERM/INT/HUP
 *   2. tag sweep        macOS has no subreaper and no way to forbid setsid() (Seatbelt has no such
 *                       operation), so a descendant that detaches reparents to launchd and escapes
 *                       the process-group kill. Instead the command is marked: this program creates
 *                       an empty file with a random name under KILLTAG_DIR and appends an allow rule
 *                       for exactly that file to the profile. On teardown the parent walks its own
 *                       uid's processes and kills the ones whose sandbox may read the tag file but
 *                       may NOT read the directory holding it. Both halves are needed: the first
 *                       picks out this command's tree (another command's profile grants another
 *                       file), the second throws out processes that are unsandboxed or carry a
 *                       permissive profile granting the whole directory, which is how the server's
 *                       own JVM and any desktop application are excluded. A sandboxed process cannot
 *                       re-sandbox itself (nested sandbox_apply is refused), so it cannot shed the
 *                       tag; and if the marking ever fails, the sweep matches nothing rather than
 *                       too much.
 *   3. rlimits          RLIMIT_CPU/NPROC/FSIZE/NOFILE (0 = untouched), clamped to the hard limit
 *   4. Seatbelt         the SBPL profile is applied by /usr/bin/sandbox-exec, which then execs the
 *                       command; the profile is inherited by every descendant and cannot be dropped
 *
 * The server renders the policy part of the profile (see SeatbeltProfile.java); this program stays
 * policy-free (it only appends the kill tag) so a profile change never needs a recompile.
 *
 * Build:  cc -O2 -Wall -Wextra -o extender-sandbox extender-sandbox-darwin.c
 */
#include <errno.h>
#include <fcntl.h>
#include <libproc.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/proc_info.h>
#include <sys/stat.h>
#include <sys/resource.h>
#include <arpa/inet.h>
#include <netinet/in.h>
#include <sys/socket.h>
#include <sys/wait.h>
#include <unistd.h>

#define SANDBOX_EXEC "/usr/bin/sandbox-exec"

/*
 * sandbox_check(pid, operation, type, ...) asks the kernel whether `pid`'s sandbox would allow an
 * operation; it returns 1 when denied, 0 when allowed (including for an unsandboxed process), and
 * -1 on error. The pid form is not in a public header. SANDBOX_CHECK_NO_REPORT keeps the probe out
 * of the unified log.
 */
extern int sandbox_check(pid_t pid, const char *operation, int type, ...);
#define SB_FILTER_PATH 1
#define SB_CHECK_NO_REPORT 0x40000000

/*
 * Holds one empty file per running command. No build profile grants this directory, which is what
 * makes "may read the file but not its directory" identify exactly one command's processes.
 */
#define KILLTAG_DIR "/private/tmp/.extender-sbtag"

static const char PROBE_PROFILE[] =
    "(version 1)(deny default)(import \"system.sb\")"
    "(allow process-exec (literal \"/usr/bin/true\"))(allow file-read* (subpath \"/usr/bin\"))";

/* ---- rlimits ---- */

static int set_limit(int resource, long long value, const char *name) {
    if (value <= 0) {
        return 0;
    }
    rlim_t wanted = (rlim_t)value;
    struct rlimit current;
    /* An unprivileged process cannot raise a hard limit (kern.maxprocperuid caps NPROC). */
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

/* ---- parent: process group owner ---- */

static volatile sig_atomic_t received_signal = 0;
static pid_t child_pid = 0;

static void on_signal(int sig) {
    received_signal = sig;
    if (child_pid > 0) {
        kill(-child_pid, SIGKILL);
    }
}

static int wait_for(pid_t pid) {
    int status = 0;
    for (;;) {
        pid_t w = waitpid(pid, &status, 0);
        if (w == pid) {
            return status;
        }
        if (w < 0 && errno == EINTR) {
            continue;
        }
        fprintf(stderr, "extender-sandbox: waitpid: %s\n", strerror(errno));
        return 127 << 8;
    }
}

static void reap_children(void) {
    for (int round = 0; round < 64; round++) {
        int status;
        pid_t pid;
        int reaped = 0;
        while ((pid = waitpid(-1, &status, WNOHANG)) > 0) {
            reaped++;
        }
        if (pid < 0 && errno == ECHILD) {
            return;
        }
        if (reaped == 0) {
            usleep(10000);
        }
    }
}

static int exit_status(int status) {
    if (WIFEXITED(status)) {
        return WEXITSTATUS(status);
    }
    if (WIFSIGNALED(status)) {
        return 128 + WTERMSIG(status);
    }
    return 127;
}

/* ---- tag sweep: find and kill every process running under this command's profile ---- */

/*
 * Creates the tag file for this command. A Seatbelt (literal ...) rule only ever matches a path
 * that resolves, so the file has to exist for as long as the command runs. Returns 0 on success;
 * on failure the caller runs without a tag and the sweep is skipped.
 */
static int make_kill_tag(char *buf, size_t size) {
    if (mkdir(KILLTAG_DIR, 0700) != 0 && errno != EEXIST) {
        return -1;
    }
    for (int attempt = 0; attempt < 4; attempt++) {
        snprintf(buf, size, KILLTAG_DIR "/%d-%08x%08x", (int)getpid(), arc4random(), arc4random());
        int fd = open(buf, O_CREAT | O_EXCL | O_WRONLY, 0600);
        if (fd >= 0) {
            close(fd);
            return 0;
        }
        if (errno != EEXIST) {
            return -1;
        }
    }
    return -1;
}

/* The tag rule goes last: in SBPL the last matching rule wins, so no deny in the policy can shadow it. */
static char *tagged_profile(const char *profile, const char *tag) {
    static const char prefix[] = "(allow file-read-data (literal \"";
    static const char suffix[] = "\"))";
    size_t len = strlen(profile) + sizeof(prefix) + strlen(tag) + sizeof(suffix);
    char *out = malloc(len);
    if (out == NULL) {
        return NULL;
    }
    snprintf(out, len, "%s%s%s%s", profile, prefix, tag, suffix);
    return out;
}

/*
 * Ours = may read the tag file, may not read the directory holding it. The tag check runs first
 * because it answers "denied" in one call for the bulk of the candidates, the trees of the other
 * commands running concurrently. The directory check then removes everything whose sandbox is
 * absent or permissive: for an unsandboxed process every check answers "allowed".
 */
static int process_is_ours(pid_t pid, const char *tag) {
    if (sandbox_check(pid, "file-read-data", SB_FILTER_PATH | SB_CHECK_NO_REPORT, tag) != 0) {
        return 0;
    }
    return sandbox_check(pid, "file-read-data", SB_FILTER_PATH | SB_CHECK_NO_REPORT, KILLTAG_DIR) == 1;
}

static int pid_seen(const pid_t *seen, int count, pid_t pid) {
    for (int i = 0; i < count; i++) {
        if (seen[i] == pid) {
            return 1;
        }
    }
    return 0;
}

/*
 * Freeze, then kill. Each round SIGSTOPs everything that matches; a process that forked between
 * rounds shows up in the next one, while stopped ones cannot fork any more, so the set of live
 * matches shrinks until a round finds nothing new. Then everything found gets SIGKILL. In the
 * common case, no escapee, the first round finds nothing and the whole sweep is one scan.
 *
 * Cost per round: one process listing plus one sandbox_check per process of this uid (two for the
 * few that are unsandboxed). Rounds are bounded, the tree by RLIMIT_NPROC, and nothing is shared
 * between launchers, so many concurrent commands tearing down at once just do their own scans.
 */
static void sweep_by_tag(const char *tag) {
    uid_t me = getuid();
    pid_t self = getpid();
    pid_t *seen = NULL;
    int seen_count = 0, seen_cap = 0;

    for (int round = 0; round < 32; round++) {
        /* the kernel does the uid filtering, so no per-process proc_pidinfo call is needed */
        int bytes = proc_listpids(PROC_UID_ONLY, me, NULL, 0);
        if (bytes <= 0) {
            break;
        }
        int cap = bytes / (int)sizeof(pid_t) + 128; /* slack: the table may grow between the two calls */
        pid_t *pids = calloc((size_t)cap, sizeof(pid_t));
        if (pids == NULL) {
            break;
        }
        int n = proc_listpids(PROC_UID_ONLY, me, pids, cap * (int)sizeof(pid_t));
        n = n > 0 ? n / (int)sizeof(pid_t) : 0;

        int added = 0;
        for (int i = 0; i < n; i++) {
            pid_t pid = pids[i];
            if (pid <= 1 || pid == self) {
                continue;
            }
            if (!process_is_ours(pid, tag)) {
                continue;
            }
            kill(pid, SIGSTOP); /* idempotent; a frozen process cannot fork */
            if (pid_seen(seen, seen_count, pid)) {
                continue;
            }
            if (seen_count == seen_cap) {
                int next = seen_cap == 0 ? 256 : seen_cap * 2;
                pid_t *grown = realloc(seen, (size_t)next * sizeof(pid_t));
                if (grown == NULL) {
                    break;
                }
                seen = grown;
                seen_cap = next;
            }
            seen[seen_count++] = pid;
            added++;
        }
        free(pids);
        if (added == 0) {
            break;
        }
        usleep(2000);
    }

    for (int i = 0; i < seen_count; i++) {
        kill(seen[i], SIGKILL);
    }
    if (seen_count > 0) {
        fprintf(stderr, "extender-sandbox: killed %d escaped process(es)\n", seen_count);
    }
    free(seen);
}

/* ---- probe / self-test aids ---- */

static int sandbox_exec_available(void) {
    return access(SANDBOX_EXEC, X_OK) == 0;
}

static int probe(void) {
    int available = 0;
    if (sandbox_exec_available()) {
        pid_t pid = fork();
        if (pid == 0) {
            int devnull = open("/dev/null", O_WRONLY);
            if (devnull >= 0) {
                dup2(devnull, 1);
                dup2(devnull, 2);
            }
            execl(SANDBOX_EXEC, "sandbox-exec", "-p", PROBE_PROFILE, "/usr/bin/true", (char *)NULL);
            _exit(127);
        }
        if (pid > 0) {
            available = exit_status(wait_for(pid)) == 0;
        }
    }
    printf("seatbelt=%s sandbox_exec=%s\n", available ? "yes" : "no", SANDBOX_EXEC);
    return 0;
}

static const char *errno_name(int err) {
    switch (err) {
    case EAFNOSUPPORT:
        return "EAFNOSUPPORT";
    case EPERM:
        return "EPERM";
    default:
        return strerror(err);
    }
}

/*
 * Seatbelt gates connect()/bind(), not socket(): a family counts as usable only when a
 * connect to the loopback discard port gets past the sandbox (ECONNREFUSED is fine).
 */
static void check_sockets(void) {
    const struct {
        int family;
        const char *name;
    } families[] = {{AF_UNIX, "unix"}, {AF_INET, "inet"}, {AF_INET6, "inet6"}};
    for (size_t i = 0; i < sizeof(families) / sizeof(families[0]); i++) {
        const char *sep = i + 1 < 3 ? " " : "\n";
        int fd = socket(families[i].family, SOCK_STREAM, 0);
        if (fd < 0) {
            printf("%s=%s%s", families[i].name, errno_name(errno), sep);
            continue;
        }
        int denied = 0;
        if (families[i].family == AF_INET) {
            struct sockaddr_in sin;
            memset(&sin, 0, sizeof(sin));
            sin.sin_family = AF_INET;
            sin.sin_port = htons(9);
            sin.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
            denied = connect(fd, (struct sockaddr *)&sin, sizeof(sin)) != 0 && errno == EPERM;
        } else if (families[i].family == AF_INET6) {
            struct sockaddr_in6 sin6;
            memset(&sin6, 0, sizeof(sin6));
            sin6.sin6_family = AF_INET6;
            sin6.sin6_port = htons(9);
            sin6.sin6_addr = in6addr_loopback;
            denied = connect(fd, (struct sockaddr *)&sin6, sizeof(sin6)) != 0 && errno == EPERM;
        }
        close(fd);
        printf("%s=%s%s", families[i].name, denied ? "EPERM" : "ok", sep);
    }
}

static void usage(void) {
    fprintf(stderr,
            "usage: extender-sandbox --profile SBPL [--cpu SEC] [--nproc N] [--fsize BYTES] [--nofile N]\n"
            "                        [--strict] -- CMD [ARGS...]\n"
            "       extender-sandbox --probe\n"
            "       extender-sandbox --check-sockets\n");
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
    const char *profile = NULL;
    int strict = 0;
    long long cpu = 0, nproc = 0, fsize = 0, nofile = 0;
    int cmd_index = -1;

    for (int i = 1; i < argc; i++) {
        const char *arg = argv[i];
        if (strcmp(arg, "--probe") == 0) {
            return probe();
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
        if (strcmp(arg, "--profile") == 0) {
            profile = value;
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
    if (cmd_index < 0 || cmd_index >= argc || profile == NULL || *profile == '\0') {
        usage();
    }

    int confine = sandbox_exec_available();
    if (!confine && strict) {
        fprintf(stderr, "extender-sandbox: %s is missing (strict mode)\n", SANDBOX_EXEC);
        return 127;
    }

    struct sigaction sa;
    memset(&sa, 0, sizeof(sa));
    sa.sa_handler = on_signal;
    sigemptyset(&sa.sa_mask);
    sigaction(SIGTERM, &sa, NULL);
    sigaction(SIGINT, &sa, NULL);
    sigaction(SIGHUP, &sa, NULL);

    /* created before fork: the child puts it in the profile, the parent sweeps by it */
    char kill_tag[128];
    int tagged = 0;
    if (confine) {
        tagged = make_kill_tag(kill_tag, sizeof(kill_tag)) == 0;
        if (!tagged) {
            /* without the tag a detached descendant could outlive the command unnoticed */
            fprintf(stderr, "extender-sandbox: cannot create the kill tag in %s: %s\n", KILLTAG_DIR, strerror(errno));
            if (strict) {
                return 127;
            }
        }
    }

    pid_t pid = fork();
    if (pid < 0) {
        fprintf(stderr, "extender-sandbox: fork: %s\n", strerror(errno));
        return 127;
    }

    if (pid == 0) {
        /* ---- child: own process group, limits, then sandbox-exec applies the profile and execs ---- */
        setpgid(0, 0);
        signal(SIGTERM, SIG_DFL);
        signal(SIGINT, SIG_DFL);
        signal(SIGHUP, SIG_DFL);

        if (set_limit(RLIMIT_CPU, cpu, "RLIMIT_CPU") != 0 || set_limit(RLIMIT_NPROC, nproc, "RLIMIT_NPROC") != 0 ||
            set_limit(RLIMIT_FSIZE, fsize, "RLIMIT_FSIZE") != 0 ||
            set_limit(RLIMIT_NOFILE, nofile, "RLIMIT_NOFILE") != 0) {
            _exit(127);
        }

        int cmd_argc = argc - cmd_index;
        if (confine) {
            char **sb_argv = calloc((size_t)cmd_argc + 4, sizeof(char *));
            if (sb_argv == NULL) {
                _exit(127);
            }
            const char *effective = profile;
            if (tagged) {
                effective = tagged_profile(profile, kill_tag);
                if (effective == NULL) {
                    _exit(127);
                }
            }
            sb_argv[0] = "sandbox-exec";
            sb_argv[1] = "-p";
            sb_argv[2] = (char *)effective;
            for (int i = 0; i < cmd_argc; i++) {
                sb_argv[3 + i] = argv[cmd_index + i];
            }
            sb_argv[3 + cmd_argc] = NULL;
            execv(SANDBOX_EXEC, sb_argv);
            fprintf(stderr, "extender-sandbox: exec %s: %s\n", SANDBOX_EXEC, strerror(errno));
            _exit(127);
        }
        /* degraded (non-strict, no sandbox-exec): still a process group with limits */
        execvp(argv[cmd_index], &argv[cmd_index]);
        fprintf(stderr, "extender-sandbox: exec %s: %s\n", argv[cmd_index], strerror(errno));
        _exit(127);
    }

    /* ---- parent: wait, then make sure the group is gone ---- */
    child_pid = pid;
    setpgid(pid, pid); /* both sides do this; whichever runs first wins, EACCES after exec is fine */
    if (received_signal != 0) {
        kill(-pid, SIGKILL);
    }

    int status = wait_for(pid);
    kill(-pid, SIGKILL);
    reap_children();
    if (tagged) {
        /* the group is gone and reaped; whatever still carries the tag has escaped it */
        sweep_by_tag(kill_tag);
        unlink(kill_tag);
    }

    if (received_signal != 0) {
        return 128 + received_signal;
    }
    return exit_status(status);
}

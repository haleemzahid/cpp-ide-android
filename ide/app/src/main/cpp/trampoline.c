// Debugger trampoline — the program lldb-dap launches for Debug runs.
//
// Compiles user C/C++ into a PIC shared library (libuser.so) that exports
// `run_user_main(argc, argv, in_fd, out_fd, err_fd)`. We dlopen that .so
// and call run_user_main. ptrace is process-based, so this binary is what
// lldb actually attaches to.
//
// Keep this microscopic: any work done here before user main runs becomes
// noise in the user's "first step" experience. Error paths set a distinct
// exit code but do not write to stderr — that would leak into the IDE's
// terminal panel where only the user's own stdout/stderr belongs.

#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <libgen.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <android/log.h>

#define TAG "cppide-trampoline"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)

typedef int (*run_user_main_t)(int argc, char** argv,
                               int in_fd, int out_fd, int err_fd);

/**
 * Derive nativeLibraryDir from argv[0] and prepend it to LD_LIBRARY_PATH
 * so user code linking libc++_shared.so can resolve it regardless of how
 * we were invoked.
 */
static void fix_ld_library_path(const char* argv0) {
    char buf[2048];
    strncpy(buf, argv0, sizeof(buf) - 1);
    buf[sizeof(buf) - 1] = 0;
    const char* dir = dirname(buf);

    const char* existing = getenv("LD_LIBRARY_PATH");
    char merged[4096];
    if (existing && *existing) {
        snprintf(merged, sizeof(merged), "%s:%s", dir, existing);
    } else {
        snprintf(merged, sizeof(merged), "%s", dir);
    }
    setenv("LD_LIBRARY_PATH", merged, 1);
}

int main(int argc, char** argv) {
    if (argc < 2) {
        LOGE("usage: %s <user.so>", argv[0] ? argv[0] : "(null)");
        return 2;
    }
    const char* so_path = argv[1];

    fix_ld_library_path(argv[0]);

    // Preload libc++_shared.so with RTLD_GLOBAL so user .so's later
    // dlopen finds it in the global namespace without searching
    // LD_LIBRARY_PATH.
    {
        char buf[2048];
        strncpy(buf, argv[0], sizeof(buf) - 1);
        buf[sizeof(buf) - 1] = 0;
        const char* dir = dirname(buf);
        char cpp_path[2048];
        snprintf(cpp_path, sizeof(cpp_path), "%s/libc++_shared.so", dir);
        dlopen(cpp_path, RTLD_NOW | RTLD_GLOBAL);
    }

    void* handle = dlopen(so_path, RTLD_NOW | RTLD_LOCAL);
    if (!handle) {
        LOGE("dlopen failed: %s", dlerror());
        return 3;
    }

    run_user_main_t fn = (run_user_main_t) dlsym(handle, "run_user_main");
    if (!fn) {
        LOGE("dlsym(run_user_main) failed: %s", dlerror());
        return 4;
    }

    // Signal the debugger that the user .so is loaded so it can refresh
    // its loaded-library list and bind pending breakpoints. Under ptrace
    // this stops the tracee; the tracer swallows the signal on resume.
    raise(SIGTRAP);

    // If the launcher passed CPPIDE_STDIN_FIFO, open it for reading and
    // hand the fd to run_user_main. lldb-dap 21's own stdin redirection
    // paths (`process launch -i`, `target.input-path`) both trigger an
    // "(empty)" regression in the launch validator, so we bypass lldb
    // and redirect inside the debugged process instead.
    int user_in_fd = STDIN_FILENO;
    const char* fifo_path = getenv("CPPIDE_STDIN_FIFO");
    if (fifo_path && *fifo_path) {
        int fifo_fd = open(fifo_path, O_RDONLY);
        if (fifo_fd >= 0) {
            user_in_fd = fifo_fd;
        } else {
            LOGE("open(%s) failed: errno=%d (%s)", fifo_path, errno, strerror(errno));
        }
    }

    int rc = fn(0, NULL, user_in_fd, STDOUT_FILENO, STDERR_FILENO);

    fflush(stdout);
    fflush(stderr);
    return rc;
}

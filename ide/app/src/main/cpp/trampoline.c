// Debugger trampoline — the actual process lldb-server attaches to
// when the user hits Debug.
//
// Our Run pipeline compiles user C/C++ into a PIC shared library
// (libuser.so) that exports `run_user_main(argc, argv, out_fd, err_fd)`
// as extern "C" — see runtime_shim.cpp. For release runs we dlopen
// that .so inside the IDE's own process. But ptrace is process-based;
// you can't single-step code that lives in the debugger's own address
// space. So for Debug runs we spawn *this* trampoline as a fresh
// forked child under `lldb-server gdbserver`, which PTRACE_TRACEME's
// it and stops it at entry. The trampoline then dlopens libuser.so
// and jumps to run_user_main — and lldb-server sees every instruction.
//
// Keep this file microscopic. Anything we do here before hitting
// user_main_fn becomes noise in the user's "first step" experience.

#include <dlfcn.h>
#include <libgen.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

// Matches runtime_shim.cpp's extern "C" export exactly.
typedef int (*run_user_main_t)(int argc, char** argv, int out_fd, int err_fd);

/**
 * Belt-and-suspenders: derive nativeLibraryDir from argv[0] (we're
 * running AS libTrampoline.so from that directory) and prepend it to
 * LD_LIBRARY_PATH ourselves. Without this, user code linking against
 * libc++_shared.so fails with "library not found" if whoever launched
 * us — lldb-server in our case — dropped or mangled the parent
 * environment. Doing it here means the trampoline Just Works regardless
 * of how we're invoked, even straight from adb shell.
 */
static void fix_ld_library_path(const char* argv0) {
    // argv0 is the full path to libTrampoline.so. Its dirname is
    // nativeLibraryDir, which holds libc++_shared.so + all the other
    // staged libs we ship.
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
    fprintf(stderr, "T> set LD_LIBRARY_PATH=%s\n", merged);
    fflush(stderr);
}

int main(int argc, char** argv) {
    // Stage-by-stage diagnostic prints so we can tell exactly where a
    // broken launch dies — without a debugger ironically. Each message
    // is prefixed so it's easy to grep in combined stdout/stderr.
    // All prints go to stderr so stdout stays clean for user output.
    fprintf(stderr, "T> entry argc=%d argv0=%s\n", argc,
            argv[0] ? argv[0] : "(null)");
    fflush(stderr);

    if (argc < 2) {
        fprintf(stderr, "T> usage: %s <user.so>\n", argv[0]);
        return 2;
    }
    const char* so_path = argv[1];
    fprintf(stderr, "T> so_path=%s\n", so_path);
    fflush(stderr);

    // Make the loader self-sufficient (see comment on the helper).
    fix_ld_library_path(argv[0]);

    // Explicit preload of libc++_shared.so with RTLD_GLOBAL. Bionic's
    // linker is inconsistent about honouring LD_LIBRARY_PATH updates
    // after startup, but a successful RTLD_GLOBAL dlopen puts the
    // library in the global namespace so the user .so's later dlopen
    // finds it by name without searching LD_LIBRARY_PATH at all.
    {
        char buf[2048];
        strncpy(buf, argv[0], sizeof(buf) - 1);
        buf[sizeof(buf) - 1] = 0;
        const char* dir = dirname(buf);
        char cpp_path[2048];
        snprintf(cpp_path, sizeof(cpp_path), "%s/libc++_shared.so", dir);
        void* cxx = dlopen(cpp_path, RTLD_NOW | RTLD_GLOBAL);
        if (cxx) {
            fprintf(stderr, "T> preloaded libc++_shared.so\n");
        } else {
            fprintf(stderr, "T> preload libc++_shared.so failed: %s (continuing)\n",
                    dlerror());
        }
        fflush(stderr);
    }

    // RTLD_NOW so undefined symbols fail loudly here rather than at a
    // random step later. RTLD_LOCAL so we don't pollute the global
    // namespace — purely hygiene; we're about to exit anyway.
    fprintf(stderr, "T> calling dlopen\n");
    fflush(stderr);
    void* handle = dlopen(so_path, RTLD_NOW | RTLD_LOCAL);
    if (!handle) {
        fprintf(stderr, "T> dlopen failed: %s\n", dlerror());
        return 3;
    }
    fprintf(stderr, "T> dlopen ok, handle=%p\n", handle);
    fflush(stderr);

    fprintf(stderr, "T> calling dlsym run_user_main\n");
    fflush(stderr);
    run_user_main_t fn = (run_user_main_t) dlsym(handle, "run_user_main");
    if (!fn) {
        fprintf(stderr, "T> dlsym failed: %s\n", dlerror());
        return 4;
    }
    fprintf(stderr, "T> dlsym ok, fn=%p\n", (void*)fn);
    fflush(stderr);

    // argc=0 / argv=NULL — user code that inspects argv gets a clean
    // empty environment. Real stdout/stderr are already wired to the
    // process pipes lldb-server gave us, so pass them straight through.
    fprintf(stderr, "T> calling run_user_main\n");
    fflush(stderr);
    int rc = fn(0, NULL, STDOUT_FILENO, STDERR_FILENO);
    fprintf(stderr, "T> run_user_main returned %d\n", rc);
    fflush(stderr);

    fflush(stdout);
    fflush(stderr);
    return rc;
}

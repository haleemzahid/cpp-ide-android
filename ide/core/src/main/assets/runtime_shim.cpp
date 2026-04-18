// C++ runtime shim — compiled in C++ mode alongside the user's sources.
//
// The user's `int main(...)` is renamed to `user_main_fn` via
// -Dmain=user_main_fn at preprocess time. Beginners usually write
// `int main()` (no args), but C++ also permits `int main(int, char**)`.
// Both cases compile in the user's file — we just don't know which one
// until link time, and we need the shim to reference the right overload.
//
// Trick: declare BOTH overloads as weak. The user defines exactly one;
// the other remains weak-undefined, which the dynamic linker resolves
// to NULL at load time (no "unresolved symbol" error under RTLD_NOW).
// At call time we pick whichever function pointer is non-null.
//
// `run_user_main` itself is extern "C" so dlsym("run_user_main") resolves
// to an unmangled symbol from the JNI bridge.

#include <stdio.h>
#include <stdlib.h>
#include <unistd.h>

// Overloaded, both weak. Exactly one gets defined by the user's code.
int user_main_fn() __attribute__((weak));
int user_main_fn(int argc, char** argv) __attribute__((weak));

extern "C" int run_user_main(int argc, char** argv, int in_fd, int out_fd, int err_fd) {
    int saved_stdin  = dup(STDIN_FILENO);
    int saved_stdout = dup(STDOUT_FILENO);
    int saved_stderr = dup(STDERR_FILENO);

    if (in_fd  >= 0) dup2(in_fd,  STDIN_FILENO);
    if (out_fd >= 0) dup2(out_fd, STDOUT_FILENO);
    if (err_fd >= 0) dup2(err_fd, STDERR_FILENO);

    // Unbuffered stdout/stderr so output is visible in the UI immediately.
    // stdin stays line-buffered — that's what cin >> x and getline expect,
    // and it matches desktop terminal behavior.
    setvbuf(stdout, NULL, _IONBF, 0);
    setvbuf(stderr, NULL, _IONBF, 0);

    // Pick whichever overload the user defined. Static casts disambiguate
    // which mangled symbol each pointer refers to, so the weak-NULL check
    // works per-overload.
    int (*f_void)() = static_cast<int (*)()>(user_main_fn);
    int (*f_args)(int, char**) = static_cast<int (*)(int, char**)>(user_main_fn);

    int rc;
    if (f_void) {
        rc = f_void();
    } else if (f_args) {
        rc = f_args(argc, argv);
    } else {
        fprintf(stderr, "run_user_main: no user main() defined\n");
        rc = 127;
    }

    fflush(stdout);
    fflush(stderr);

    if (saved_stdin >= 0) {
        dup2(saved_stdin, STDIN_FILENO);
        close(saved_stdin);
    }
    if (saved_stdout >= 0) {
        dup2(saved_stdout, STDOUT_FILENO);
        close(saved_stdout);
    }
    if (saved_stderr >= 0) {
        dup2(saved_stderr, STDERR_FILENO);
        close(saved_stderr);
    }

    return rc;
}

// C++ runtime shim — must be compiled in C++ mode so the extern
// declaration of user_main_fn gets C++ name mangling that matches the
// user's hello.cpp (which is also C++, with main renamed via
// -Dmain=user_main_fn at preprocess time).
//
// The run_user_main entry point is explicitly extern "C" so the JNI bridge's
// dlsym("run_user_main") resolves to an unmangled symbol.

#include <stdio.h>
#include <stdlib.h>
#include <unistd.h>

// NOT extern "C" — we want C++ mangling so this resolves to the same
// symbol the user's hello.cpp produces (_Z12user_main_fniPPc).
extern int user_main_fn(int argc, char** argv);

extern "C" int run_user_main(int argc, char** argv, int out_fd, int err_fd) {
    int saved_stdout = dup(STDOUT_FILENO);
    int saved_stderr = dup(STDERR_FILENO);

    if (out_fd >= 0) dup2(out_fd, STDOUT_FILENO);
    if (err_fd >= 0) dup2(err_fd, STDERR_FILENO);

    // Unbuffered so output is visible in the UI immediately.
    setvbuf(stdout, NULL, _IONBF, 0);
    setvbuf(stderr, NULL, _IONBF, 0);

    int rc = user_main_fn(argc, argv);

    fflush(stdout);
    fflush(stderr);

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

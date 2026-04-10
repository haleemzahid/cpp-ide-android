// The runtime shim lives inside every compiled user program. It:
//   1. Saves the process's real stdout/stderr fds
//   2. dup2()'s the caller-provided pipe fds onto stdout(1) and stderr(2)
//   3. Calls the user's main function (renamed to user_main_fn at compile time)
//   4. Flushes stdio and restores the original fds before returning
//
// The effect: the user's printf/cout/fprintf output gets piped back to our
// Kotlin UI via a ParcelFileDescriptor pipe the caller owns.
//
// This runs in-process via dlopen+dlsym — no execve, no child process.
// It's the core of how the IDE will "run" user programs on Android 15.

#include <stdio.h>
#include <stdlib.h>
#include <unistd.h>

// Declared by user_main.c, which is compiled with -Dmain=user_main_fn.
extern int user_main_fn(int argc, char** argv);

__attribute__((visibility("default")))
int run_user_main(int argc, char** argv, int out_fd, int err_fd) {
    int saved_stdout = dup(STDOUT_FILENO);
    int saved_stderr = dup(STDERR_FILENO);

    if (out_fd >= 0) dup2(out_fd, STDOUT_FILENO);
    if (err_fd >= 0) dup2(err_fd, STDERR_FILENO);

    // Make streams unbuffered so the UI sees output immediately, not only
    // after the program ends or a newline triggers line-buffer flush.
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

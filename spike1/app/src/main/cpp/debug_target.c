// The debuggee for Spike 2c. A tiny program that:
//   - prints its PID
//   - increments a global counter in a short loop
//   - prints each value
//   - exits with code 42
//
// Small enough that we can single-step a few instructions and still have
// the process running to observe. Compiled as PIE arm64 executable and
// shipped in jniLibs as libdebug_target.so.
#include <stdio.h>
#include <unistd.h>

int global_counter = 0;

static void step(void) {
    global_counter++;
    printf("counter=%d\n", global_counter);
}

int main(int argc, char** argv) {
    printf("debug_target starting pid=%d\n", (int)getpid());
    for (int i = 0; i < 5; i++) {
        step();
    }
    printf("debug_target exiting 42\n");
    return 42;
}

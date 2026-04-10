// Sample "user program" — the thing a developer using our IDE would write.
// In Spike 2a it's compiled AT BUILD TIME by NDK clang on our dev machine.
// In the real IDE it'll be compiled AT RUN TIME by bundled on-device clang.
//
// Note: we compile this file with -Dmain=user_main_fn so the symbol becomes
// user_main_fn, which the runtime_shim then calls. This avoids the ODR clash
// of having multiple "main"s in the .so and lets us invoke it as a regular
// function instead of the process entry point.

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

int main(int argc, char** argv) {
    printf("== user program starting ==\n");
    printf("argc = %d\n", argc);
    for (int i = 0; i < argc; i++) {
        printf("argv[%d] = %s\n", i, argv[i]);
    }

    // A little computation
    long sum = 0;
    for (int i = 1; i <= 100; i++) sum += i;
    printf("sum(1..100) = %ld\n", sum);

    // Fibonacci
    long a = 0, b = 1;
    for (int i = 0; i < 15; i++) {
        printf("fib(%d) = %ld\n", i, a);
        long t = a + b;
        a = b;
        b = t;
    }

    // Go to stderr too so we can verify the split
    fprintf(stderr, "(warning: this line went to stderr)\n");

    printf("== user program ended, returning 42 ==\n");
    return 42;
}

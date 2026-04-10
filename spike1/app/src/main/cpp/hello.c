// Native hello used to validate that a binary shipped in jniLibs/arm64-v8a
// can actually be execve()'d from Android's nativeLibraryDir. This is the
// foundational assumption of the whole C++ IDE project — if this doesn't run,
// we need a different strategy.
#include <stdio.h>
#include <unistd.h>
#include <sys/utsname.h>

int main(int argc, char** argv) {
    printf("hello from native arm64 binary\n");
    printf("argc=%d\n", argc);
    for (int i = 0; i < argc; i++) {
        printf("  argv[%d]=%s\n", i, argv[i]);
    }

    struct utsname u;
    if (uname(&u) == 0) {
        printf("uname: %s %s %s %s\n", u.sysname, u.release, u.version, u.machine);
    }

    printf("getpid=%d getuid=%d\n", (int)getpid(), (int)getuid());
    return 0;
}

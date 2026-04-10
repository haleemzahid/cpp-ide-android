// JNI bridge — the piece that lets Kotlin call into a dlopen'd user .so.
//
// This lib lives permanently in jniLibs/arm64-v8a/libjnibridge.so and is
// loaded once at class init via System.loadLibrary("jnibridge"). It exposes
// three native methods matching dev.cppide.core.jni.NativeBridge:
//
//   runUserProgram(libPath, outFd, errFd): Int
//     dlopen(libPath) -> dlsym("run_user_main") -> call with pipe fds
//
//   hasSymbol(libPath, symbol): Boolean
//     diagnostic — is the symbol resolvable in libPath?
//
//   ptraceTest(binaryPath): String
//     spike 2c primitive — fork+exec a target with PTRACE_TRACEME, drive it
//     through single-step and continue, return a readable report.
//
// Native method naming: JNI auto-resolves via
//   "Java_<pkg_with_underscores>_<Class>_<method>"
// so the package path dev.cppide.core.jni and class NativeBridge must match
// the Kotlin declarations exactly.

#include <jni.h>
#include <dlfcn.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <errno.h>
#include <sys/ptrace.h>
#include <sys/wait.h>
#include <sys/uio.h>
#include <sys/types.h>
#include <asm/ptrace.h>   // struct user_pt_regs on arm64
#include <elf.h>          // NT_PRSTATUS
#include <android/log.h>

#define LOG_TAG "cppide-core-bridge"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

typedef int (*run_user_main_fn)(int argc, char** argv, int out_fd, int err_fd);

JNIEXPORT jint JNICALL
Java_dev_cppide_core_jni_NativeBridge_runUserProgram(
        JNIEnv* env, jclass clazz,
        jstring jLibPath, jint outFd, jint errFd) {

    const char* libPath = (*env)->GetStringUTFChars(env, jLibPath, NULL);
    LOGI("dlopen(%s)", libPath);

    // Clear any stale dlerror state
    dlerror();

    void* handle = dlopen(libPath, RTLD_NOW | RTLD_LOCAL);
    if (!handle) {
        const char* err = dlerror();
        LOGE("dlopen failed: %s", err ? err : "(null)");
        (*env)->ReleaseStringUTFChars(env, jLibPath, libPath);
        return -1001;
    }

    dlerror();
    run_user_main_fn fn = (run_user_main_fn) dlsym(handle, "run_user_main");
    if (!fn) {
        const char* err = dlerror();
        LOGE("dlsym(run_user_main) failed: %s", err ? err : "(null)");
        dlclose(handle);
        (*env)->ReleaseStringUTFChars(env, jLibPath, libPath);
        return -1002;
    }

    LOGI("calling run_user_main(outFd=%d, errFd=%d)", outFd, errFd);

    char* fake_argv[] = { (char*)"user_program", NULL };
    int rc = fn(1, fake_argv, outFd, errFd);

    LOGI("user main returned %d", rc);

    dlclose(handle);
    (*env)->ReleaseStringUTFChars(env, jLibPath, libPath);
    return rc;
}

JNIEXPORT jboolean JNICALL
Java_dev_cppide_core_jni_NativeBridge_hasSymbol(
        JNIEnv* env, jclass clazz,
        jstring jLibPath, jstring jSymbol) {

    const char* libPath = (*env)->GetStringUTFChars(env, jLibPath, NULL);
    const char* symbol  = (*env)->GetStringUTFChars(env, jSymbol, NULL);

    void* handle = dlopen(libPath, RTLD_NOW | RTLD_LOCAL);
    jboolean found = JNI_FALSE;
    if (handle) {
        dlerror();
        void* sym = dlsym(handle, symbol);
        if (sym) found = JNI_TRUE;
        dlclose(handle);
    }

    (*env)->ReleaseStringUTFChars(env, jLibPath, libPath);
    (*env)->ReleaseStringUTFChars(env, jSymbol, symbol);
    return found;
}

// ---------- Spike 2c: ptrace a forked child ----------
//
// Proves that on Android 15 an app process can fork+exec a child and
// control it via ptrace: attach, read registers, single-step, continue,
// observe exit. This is the debugger-debuggee primitive the whole lldb
// integration will be built on.
//
// Strategy:
//   1. pipe() to capture child stdout/stderr into a buffer
//   2. fork(); in the child: dup2 the pipe onto stdout/stderr,
//      ptrace(PTRACE_TRACEME), exec the target binary.
//   3. In the parent: waitpid() — child stops at the first instruction of
//      the target (SIGTRAP delivered by exec because TRACEME was set).
//   4. Read the initial register set via PTRACE_GETREGSET + NT_PRSTATUS
//      (PTRACE_GETREGS is not available on arm64 — you must use iovec).
//   5. Single-step a few instructions, reading PC each time.
//   6. Continue, wait for final exit, report exit code.
//
// Result is returned as a human-readable multi-line string that the
// SpikeRunner can append to the UI log.

#define APPEND(fmt, ...) do { \
    if (out_len < out_cap - 1) { \
        out_len += snprintf(out + out_len, out_cap - out_len, fmt, ##__VA_ARGS__); \
        if (out_len >= out_cap) out_len = out_cap - 1; \
    } \
} while (0)

JNIEXPORT jstring JNICALL
Java_dev_cppide_core_jni_NativeBridge_ptraceTest(
        JNIEnv* env, jclass clazz, jstring jBinaryPath) {

    const char* binary = (*env)->GetStringUTFChars(env, jBinaryPath, NULL);

    size_t out_cap = 4096;
    char* out = (char*)calloc(1, out_cap);
    size_t out_len = 0;

    APPEND("target: %s\n", binary);

    int stdout_pipe[2];
    if (pipe(stdout_pipe) < 0) {
        APPEND("pipe() failed: errno=%d (%s)\n", errno, strerror(errno));
        goto finish;
    }

    pid_t child = fork();
    if (child < 0) {
        APPEND("fork() failed: errno=%d (%s)\n", errno, strerror(errno));
        close(stdout_pipe[0]); close(stdout_pipe[1]);
        goto finish;
    }

    if (child == 0) {
        // ---- child ----
        close(stdout_pipe[0]);
        dup2(stdout_pipe[1], STDOUT_FILENO);
        dup2(stdout_pipe[1], STDERR_FILENO);
        close(stdout_pipe[1]);

        if (ptrace(PTRACE_TRACEME, 0, 0, 0) < 0) {
            fprintf(stderr, "PTRACE_TRACEME failed: %d\n", errno);
            _exit(98);
        }
        execl(binary, binary, (char*)NULL);
        // only reached on exec failure
        fprintf(stderr, "execl failed: %d\n", errno);
        _exit(99);
    }

    // ---- parent ----
    close(stdout_pipe[1]);
    APPEND("child pid=%d\n", (int)child);

    int status = 0;
    if (waitpid(child, &status, 0) != child) {
        APPEND("initial waitpid failed: errno=%d\n", errno);
        goto finish;
    }

    if (!WIFSTOPPED(status)) {
        APPEND("child not stopped: raw_status=0x%x\n", status);
        if (WIFEXITED(status)) APPEND("  exited=%d\n", WEXITSTATUS(status));
        if (WIFSIGNALED(status)) APPEND("  signaled=%d\n", WTERMSIG(status));
        goto finish;
    }
    APPEND("child stopped at exec, signal=%d (SIGTRAP=5)\n", WSTOPSIG(status));

    // Read registers via GETREGSET + NT_PRSTATUS (required on arm64)
    struct user_pt_regs regs;
    struct iovec iov = { &regs, sizeof(regs) };
    if (ptrace(PTRACE_GETREGSET, child, (void*)NT_PRSTATUS, &iov) < 0) {
        APPEND("GETREGSET failed: errno=%d (%s)\n", errno, strerror(errno));
    } else {
        APPEND("initial PC=0x%llx  SP=0x%llx  PSTATE=0x%llx\n",
               (unsigned long long)regs.pc,
               (unsigned long long)regs.sp,
               (unsigned long long)regs.pstate);
    }

    // Single-step 3 instructions and watch PC advance
    for (int i = 0; i < 3; i++) {
        if (ptrace(PTRACE_SINGLESTEP, child, 0, 0) < 0) {
            APPEND("SINGLESTEP %d failed: errno=%d\n", i, errno);
            break;
        }
        if (waitpid(child, &status, 0) != child) {
            APPEND("waitpid after step %d failed\n", i);
            break;
        }
        if (!WIFSTOPPED(status)) {
            APPEND("unexpected status after step %d: 0x%x\n", i, status);
            break;
        }
        if (ptrace(PTRACE_GETREGSET, child, (void*)NT_PRSTATUS, &iov) == 0) {
            APPEND("  after step %d: PC=0x%llx\n", i, (unsigned long long)regs.pc);
        }
    }

    // Let the child run to completion
    if (ptrace(PTRACE_CONT, child, 0, 0) < 0) {
        APPEND("PTRACE_CONT failed: errno=%d\n", errno);
    }

    // Wait for final exit
    while (1) {
        if (waitpid(child, &status, 0) != child) {
            APPEND("final waitpid failed: errno=%d\n", errno);
            break;
        }
        if (WIFEXITED(status)) {
            APPEND("child exited, code=%d\n", WEXITSTATUS(status));
            break;
        }
        if (WIFSIGNALED(status)) {
            APPEND("child killed by signal %d\n", WTERMSIG(status));
            break;
        }
        // Still stopped for another reason — continue it.
        if (WIFSTOPPED(status)) {
            ptrace(PTRACE_CONT, child, 0, 0);
        }
    }

    // Drain the stdout pipe (non-blocking read — child already exited)
    {
        char buf[1024];
        ssize_t n = read(stdout_pipe[0], buf, sizeof(buf) - 1);
        if (n > 0) {
            buf[n] = 0;
            APPEND("--- child stdout ---\n%s", buf);
        }
    }
    close(stdout_pipe[0]);

finish:
    (*env)->ReleaseStringUTFChars(env, jBinaryPath, binary);
    jstring result = (*env)->NewStringUTF(env, out);
    free(out);
    return result;
}

#undef APPEND

JNIEXPORT jint JNI_OnLoad(JavaVM* vm, void* reserved) {
    LOGI("libjnibridge.so loaded");
    return JNI_VERSION_1_6;
}

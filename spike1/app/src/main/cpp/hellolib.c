// A minimal, *real* shared library used to test whether dlopen() of a .so
// written to filesDir is permitted on modern Android. Unlike libnativehello.so
// (which is a renamed PIE executable), this is a genuine shared object that
// the dynamic linker is willing to map.
//
// The library has no exported functions we actually call — we only care
// whether System.load()/dlopen() succeeds without throwing.

#include <stdio.h>

__attribute__((visibility("default")))
int hello_lib_value = 42;

__attribute__((visibility("default")))
int hello_lib_ping(void) {
    // Note: printf goes to Android's stdout which is /dev/null by default in
    // app processes. We only care about the return value.
    return hello_lib_value;
}

// JNI_OnLoad gets called automatically by the Android loader when the lib is
// dlopen'd via System.load/loadLibrary. We use it as a beacon — if this runs,
// dlopen succeeded.
#include <jni.h>
#include <android/log.h>

JNIEXPORT jint JNI_OnLoad(JavaVM* vm, void* reserved) {
    __android_log_print(ANDROID_LOG_INFO, "cppide-spike1",
        "libhellolib.so: JNI_OnLoad called — dlopen succeeded, value=%d",
        hello_lib_value);
    return JNI_VERSION_1_6;
}

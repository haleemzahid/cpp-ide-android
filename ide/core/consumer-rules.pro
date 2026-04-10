# Keep JNI entry points that the native bridge expects.
-keepclasseswithmembernames class dev.cppide.core.jni.NativeBridge {
    native <methods>;
}

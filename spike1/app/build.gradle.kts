import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "dev.cppide.spike1"
    compileSdk = 35
    ndkVersion = "27.1.12297006"

    defaultConfig {
        applicationId = "dev.cppide.spike1"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"

        // We only care about 64-bit ARM for this spike — that's what every modern phone runs.
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            // Keep native libs as real files so we can execve() them.
            isJniDebuggable = true
        }
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }

    // CRITICAL for the spike: force native libs to be extracted to a real directory on device,
    // not mmap'd from the APK. Only real files on disk can be execve()'d.
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.kotlinx.coroutines.android)
    debugImplementation(libs.androidx.ui.tooling)
}

// --------------------------------------------------------------------------
// Custom task: cross-compile app/src/main/cpp/hello.c into jniLibs as a
// standalone Android arm64 executable, renamed libnativehello.so so that
// AGP's packager treats it like a native library and copies it into
// nativeLibraryDir (where binaries are actually executable on Android 10+).
// --------------------------------------------------------------------------
val buildNativeHello by tasks.registering(Exec::class) {
    group = "build"
    description = "Cross-compiles hello.c to jniLibs/arm64-v8a/libnativehello.so using NDK clang"

    // Resolve NDK path: prefer local.properties ndk.dir, then env var, then sdk.dir + ndk/<version>.
    val props = Properties().apply {
        val f = rootProject.file("local.properties")
        if (f.exists()) f.inputStream().use { load(it) }
    }
    val sdkDir = props.getProperty("sdk.dir")
        ?: System.getenv("ANDROID_HOME")
        ?: System.getenv("ANDROID_SDK_ROOT")
        ?: "C:/Program Files (x86)/Android/android-sdk"
    val ndkDir = props.getProperty("ndk.dir")
        ?: System.getenv("ANDROID_NDK_HOME")
        ?: "$sdkDir/ndk/27.1.12297006"

    val osName = System.getProperty("os.name").lowercase()
    val hostTag = when {
        osName.contains("windows") -> "windows-x86_64"
        osName.contains("mac") || osName.contains("darwin") -> "darwin-x86_64"
        else -> "linux-x86_64"
    }
    val binExt = if (osName.contains("windows")) ".cmd" else ""
    val clang = file("$ndkDir/toolchains/llvm/prebuilt/$hostTag/bin/aarch64-linux-android26-clang$binExt")

    val srcFile = file("src/main/cpp/hello.c")
    val outDir = file("src/main/jniLibs/arm64-v8a")
    val outFile = file("$outDir/libnativehello.so")

    inputs.file(srcFile)
    inputs.property("clangPath", clang.absolutePath)
    outputs.file(outFile)

    doFirst {
        if (!clang.exists()) {
            throw GradleException(
                "NDK clang not found at:\n  ${clang.absolutePath}\n" +
                "Install NDK 27.1.12297006 via Android SDK Manager or set ndk.dir in local.properties."
            )
        }
        outDir.mkdirs()
    }

    // -fPIE -pie: position-independent executable (required on Android).
    // -static-libstdc++ not needed for plain C.
    // We dynamic-link to bionic libc from /system/lib64 — that's always available.
    commandLine(
        clang.absolutePath,
        "-O2",
        "-fPIE",
        "-pie",
        "-Wall",
        "-o", outFile.absolutePath,
        srcFile.absolutePath
    )
}

// --------------------------------------------------------------------------
// Custom task: build a *real* shared library (libhellolib.so) used to test
// whether dlopen() from filesDir is allowed on Android 15. Unlike the
// renamed-executable trick, this is a proper PIC .so with JNI_OnLoad.
// --------------------------------------------------------------------------
val buildHelloLib by tasks.registering(Exec::class) {
    group = "build"
    description = "Cross-compiles hellolib.c to jniLibs/arm64-v8a/libhellolib.so (real shared library)"

    val props = Properties().apply {
        val f = rootProject.file("local.properties")
        if (f.exists()) f.inputStream().use { load(it) }
    }
    val sdkDir = props.getProperty("sdk.dir")
        ?: System.getenv("ANDROID_HOME")
        ?: System.getenv("ANDROID_SDK_ROOT")
        ?: "C:/Program Files (x86)/Android/android-sdk"
    val ndkDir = props.getProperty("ndk.dir")
        ?: System.getenv("ANDROID_NDK_HOME")
        ?: "$sdkDir/ndk/27.1.12297006"

    val osName = System.getProperty("os.name").lowercase()
    val hostTag = when {
        osName.contains("windows") -> "windows-x86_64"
        osName.contains("mac") || osName.contains("darwin") -> "darwin-x86_64"
        else -> "linux-x86_64"
    }
    val binExt = if (osName.contains("windows")) ".cmd" else ""
    val clang = file("$ndkDir/toolchains/llvm/prebuilt/$hostTag/bin/aarch64-linux-android26-clang$binExt")

    val srcFile = file("src/main/cpp/hellolib.c")
    val outDir = file("src/main/jniLibs/arm64-v8a")
    val outFile = file("$outDir/libhellolib.so")

    inputs.file(srcFile)
    inputs.property("clangPath", clang.absolutePath)
    outputs.file(outFile)

    doFirst {
        if (!clang.exists()) {
            throw GradleException("NDK clang not found at: ${clang.absolutePath}")
        }
        outDir.mkdirs()
    }

    commandLine(
        clang.absolutePath,
        "-O2",
        "-fPIC",
        "-shared",
        "-Wall",
        "-landroid",
        "-llog",
        "-o", outFile.absolutePath,
        srcFile.absolutePath
    )
}

// --------------------------------------------------------------------------
// Custom task: libjnibridge.so — the JNI wrapper that lets Kotlin call into
// a dlopen'd user .so. Lives permanently in jniLibs, loaded via
// System.loadLibrary("jnibridge").
// --------------------------------------------------------------------------
val buildJniBridge by tasks.registering(Exec::class) {
    group = "build"
    description = "Cross-compiles jnibridge.c to jniLibs/arm64-v8a/libjnibridge.so"

    val props = Properties().apply {
        val f = rootProject.file("local.properties")
        if (f.exists()) f.inputStream().use { load(it) }
    }
    val sdkDir = props.getProperty("sdk.dir")
        ?: System.getenv("ANDROID_HOME")
        ?: System.getenv("ANDROID_SDK_ROOT")
        ?: "C:/Program Files (x86)/Android/android-sdk"
    val ndkDir = props.getProperty("ndk.dir")
        ?: System.getenv("ANDROID_NDK_HOME")
        ?: "$sdkDir/ndk/27.1.12297006"
    val osName = System.getProperty("os.name").lowercase()
    val hostTag = when {
        osName.contains("windows") -> "windows-x86_64"
        osName.contains("mac") || osName.contains("darwin") -> "darwin-x86_64"
        else -> "linux-x86_64"
    }
    val binExt = if (osName.contains("windows")) ".cmd" else ""
    val clang = file("$ndkDir/toolchains/llvm/prebuilt/$hostTag/bin/aarch64-linux-android26-clang$binExt")

    val srcFile = file("src/main/cpp/jnibridge.c")
    val outDir = file("src/main/jniLibs/arm64-v8a")
    val outFile = file("$outDir/libjnibridge.so")

    inputs.file(srcFile)
    inputs.property("clangPath", clang.absolutePath)
    outputs.file(outFile)

    doFirst {
        if (!clang.exists()) throw GradleException("NDK clang not found at: ${clang.absolutePath}")
        outDir.mkdirs()
    }

    commandLine(
        clang.absolutePath,
        "-O2", "-fPIC", "-shared", "-Wall",
        "-llog", "-ldl",
        "-o", outFile.absolutePath,
        srcFile.absolutePath
    )
}

// --------------------------------------------------------------------------
// Custom task: libuser.so — a compiled "user program" (user_main.c) linked
// with runtime_shim.c. The user's int main() is renamed via -Dmain=user_main_fn
// so the shim can call it as a regular function. Simulates the output that
// bundled on-device clang will produce in the real IDE.
// --------------------------------------------------------------------------
val buildUserLib by tasks.registering(Exec::class) {
    group = "build"
    description = "Compiles user_main.c + runtime_shim.c into jniLibs/arm64-v8a/libuser.so"

    val props = Properties().apply {
        val f = rootProject.file("local.properties")
        if (f.exists()) f.inputStream().use { load(it) }
    }
    val sdkDir = props.getProperty("sdk.dir")
        ?: System.getenv("ANDROID_HOME")
        ?: System.getenv("ANDROID_SDK_ROOT")
        ?: "C:/Program Files (x86)/Android/android-sdk"
    val ndkDir = props.getProperty("ndk.dir")
        ?: System.getenv("ANDROID_NDK_HOME")
        ?: "$sdkDir/ndk/27.1.12297006"
    val osName = System.getProperty("os.name").lowercase()
    val hostTag = when {
        osName.contains("windows") -> "windows-x86_64"
        osName.contains("mac") || osName.contains("darwin") -> "darwin-x86_64"
        else -> "linux-x86_64"
    }
    val binExt = if (osName.contains("windows")) ".cmd" else ""
    val clang = file("$ndkDir/toolchains/llvm/prebuilt/$hostTag/bin/aarch64-linux-android26-clang$binExt")

    val userSrc = file("src/main/cpp/user_main.c")
    val shimSrc = file("src/main/cpp/runtime_shim.c")
    val outDir = file("src/main/jniLibs/arm64-v8a")
    val outFile = file("$outDir/libuser.so")

    inputs.file(userSrc)
    inputs.file(shimSrc)
    inputs.property("clangPath", clang.absolutePath)
    outputs.file(outFile)

    doFirst {
        if (!clang.exists()) throw GradleException("NDK clang not found at: ${clang.absolutePath}")
        outDir.mkdirs()
    }

    // -Dmain=user_main_fn rewrites the user's `int main` into `int user_main_fn`,
    // which runtime_shim.c declares extern and invokes. Safe because neither
    // standard headers nor runtime_shim.c itself use the identifier `main`.
    commandLine(
        clang.absolutePath,
        "-O2", "-fPIC", "-shared", "-Wall",
        "-Dmain=user_main_fn",
        "-o", outFile.absolutePath,
        userSrc.absolutePath,
        shimSrc.absolutePath
    )
}

// --------------------------------------------------------------------------
// Custom task: libdebug_target.so — the debuggee for Spike 2c. A tiny PIE
// executable renamed into jniLibs so we can fork+exec it and ptrace the
// child, validating the debugger primitive on Android 15.
// --------------------------------------------------------------------------
val buildDebugTarget by tasks.registering(Exec::class) {
    group = "build"
    description = "Cross-compiles debug_target.c to jniLibs/arm64-v8a/libdebug_target.so"

    val props = Properties().apply {
        val f = rootProject.file("local.properties")
        if (f.exists()) f.inputStream().use { load(it) }
    }
    val sdkDir = props.getProperty("sdk.dir")
        ?: System.getenv("ANDROID_HOME")
        ?: System.getenv("ANDROID_SDK_ROOT")
        ?: "C:/Program Files (x86)/Android/android-sdk"
    val ndkDir = props.getProperty("ndk.dir")
        ?: System.getenv("ANDROID_NDK_HOME")
        ?: "$sdkDir/ndk/27.1.12297006"
    val osName = System.getProperty("os.name").lowercase()
    val hostTag = when {
        osName.contains("windows") -> "windows-x86_64"
        osName.contains("mac") || osName.contains("darwin") -> "darwin-x86_64"
        else -> "linux-x86_64"
    }
    val binExt = if (osName.contains("windows")) ".cmd" else ""
    val clang = file("$ndkDir/toolchains/llvm/prebuilt/$hostTag/bin/aarch64-linux-android26-clang$binExt")

    val srcFile = file("src/main/cpp/debug_target.c")
    val outDir = file("src/main/jniLibs/arm64-v8a")
    val outFile = file("$outDir/libdebug_target.so")

    inputs.file(srcFile)
    inputs.property("clangPath", clang.absolutePath)
    outputs.file(outFile)

    doFirst {
        if (!clang.exists()) throw GradleException("NDK clang not found at: ${clang.absolutePath}")
        outDir.mkdirs()
    }

    commandLine(
        clang.absolutePath,
        "-O0",          // unoptimized so single-step advances instruction-by-instruction in a predictable way
        "-g",           // debug info (harmless; lets us inspect symbols later)
        "-fPIE",
        "-pie",
        "-Wall",
        "-o", outFile.absolutePath,
        srcFile.absolutePath
    )
}

// Make sure all native artifacts are built before the APK is assembled.
tasks.named("preBuild") {
    dependsOn(buildNativeHello, buildHelloLib, buildJniBridge, buildUserLib, buildDebugTarget)
}

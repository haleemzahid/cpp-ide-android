import java.util.Properties

plugins {
    // Kotlin support is provided automatically by AGP 9.0+. Do NOT apply
    // org.jetbrains.kotlin.android here — AGP rejects it as redundant.
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
}

// Load local.properties once so defaultConfig and the native build
// tasks can both read overrides from it.
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "dev.cppide.core"
    compileSdk = 36
    ndkVersion = "27.1.12297006"

    buildFeatures {
        // Needed so `buildConfigField` below generates
        // dev.cppide.core.BuildConfig for Kotlin to read at runtime.
        buildConfig = true
    }

    defaultConfig {
        minSdk = 26
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
        consumerProguardFiles("consumer-rules.pro")

        // Exercises API base URL. Override per-machine by adding
        //   exercisesApiUrl=https://your-host.example.com
        // to local.properties; defaults to the deployed Dokploy
        // instance so a fresh checkout Just Works.
        //
        // HTTPS via nip.io — Dokploy serves a valid Let's Encrypt cert
        // for the *.nip.io hostname, so no cleartext exception is needed.
        val exercisesApiUrl: String =
            localProps.getProperty("exercisesApiUrl")
                ?: "https://cpp-apis-1cvyqe-eb5bd1-204-168-128-241.nip.io"
        buildConfigField(
            "String",
            "EXERCISES_API_URL",
            "\"$exercisesApiUrl\"",
        )
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // Native libs (clang, lld, libLLVM, libclang-cpp, libc++_shared, libjnibridge)
    // must be extracted as real files on-disk — they're exec'd or dlopen'd at
    // runtime, not mmap'd from the APK.
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

// AGP 9.0+: Kotlin compiler config moved to the standard kotlin { } block.
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    api(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.jdk8)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // LSP client for clangd
    api(libs.lsp4j)
    api(libs.lsp4j.jsonrpc)

}

// --------------------------------------------------------------------------
// Build libjnibridge.so — the small JNI glue lib that lets Kotlin call into
// dlopen'd user code and drive ptrace for debugging.
// --------------------------------------------------------------------------
val buildJniBridge by tasks.registering(Exec::class) {
    group = "build"
    description = "Cross-compiles cpp/jnibridge.c to jniLibs/arm64-v8a/libjnibridge.so"

    val props = Properties().apply {
        val f = rootProject.file("local.properties")
        if (f.exists()) f.inputStream().use { load(it) }
    }
    val sdkDir = props.getProperty("sdk.dir")
        ?: System.getenv("ANDROID_HOME")
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
    inputs.property("clang", clang.absolutePath)
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
        srcFile.absolutePath,
    )
}

tasks.named("preBuild") { dependsOn(buildJniBridge) }

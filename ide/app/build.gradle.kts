import java.util.Properties

plugins {
    // Kotlin support is provided automatically by AGP 9.0+. The compose
    // compiler plugin must still be applied separately.
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Release signing — credentials live in local.properties (gitignored).
// If any key is missing we fall back to the debug keystore so local
// `assembleRelease` still works for contributors without the prod cert.
val signingProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val releaseStoreFile = signingProps.getProperty("RELEASE_STORE_FILE")
val releaseStorePassword = signingProps.getProperty("RELEASE_STORE_PASSWORD")
val releaseKeyAlias = signingProps.getProperty("RELEASE_KEY_ALIAS")
val releaseKeyPassword = signingProps.getProperty("RELEASE_KEY_PASSWORD")
val hasReleaseSigning = releaseStoreFile != null &&
    releaseStorePassword != null &&
    releaseKeyAlias != null &&
    releaseKeyPassword != null

android {
    namespace = "dev.cppide.ide"
    compileSdk = 36
    ndkVersion = "27.1.12297006"

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseStoreFile!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    defaultConfig {
        applicationId = "dev.cppide.ide"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "0.2"
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
        // Drop non-English string resources pulled in by androidx/material3/
        // compose — saves ~1-2MB of per-locale .xml from transitive deps.
        resourceConfigurations += listOf("en")
    }

    // Termux toolchain (~60 MB termux.zip) lives in a separate
    // install-time asset pack so the release base APK stays under
    // Google Play's 150 MB limit. For debug builds, `adb install`
    // doesn't deliver asset packs, so fold the pack's assets into
    // the debug variant's source sets — otherwise context.assets
    // .open("termux.zip") throws FileNotFoundException.
    assetPacks += listOf(":toolchain-pack")
    sourceSets.getByName("debug").assets.srcDirs(
        "../toolchain-pack/src/main/assets",
    )

    buildTypes {
        debug {
            isMinifyEnabled = false
            isJniDebuggable = true
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Use the production signing config when local.properties
            // supplies credentials; otherwise fall back to the debug
            // keystore so contributors can still run `installRelease`.
            signingConfig = if (hasReleaseSigning) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }

    packaging {
        jniLibs {
            // The :core module ships binaries that must be exec'able on disk.
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
    implementation(project(":core"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)

    // Code editor
    implementation(libs.sora.editor)
    implementation(libs.sora.language.textmate)

    debugImplementation(libs.androidx.ui.tooling)
}

// --------------------------------------------------------------------------
// Trampoline binary for the debugger. A tiny PIE arm64 executable that
// dlopens the user's libuser.so and calls run_user_main. Compiled via
// NDK clang (the project already uses NDK 27 for ndkVersion) and dropped
// into jniLibs/arm64-v8a/libTrampoline.so so AGP treats it as a native
// library (the "lib*.so trick" — lets it be exec'd from nativeLibraryDir
// under Android 15's SELinux). Pattern copied from spike1 which did the
// same for libdebug_target.so.
// --------------------------------------------------------------------------
val buildTrampoline by tasks.registering(Exec::class) {
    group = "build"
    description = "Cross-compiles cpp/trampoline.c to jniLibs/arm64-v8a/libTrampoline.so"

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

    val srcFile = file("src/main/cpp/trampoline.c")
    val outDir = file("src/main/jniLibs/arm64-v8a")
    val outFile = file("$outDir/libTrampoline.so")

    inputs.file(srcFile)
    inputs.property("clangPath", clang.absolutePath)
    outputs.file(outFile)

    doFirst {
        if (!clang.exists()) throw GradleException("NDK clang not found at: ${clang.absolutePath}")
        outDir.mkdirs()
    }

    commandLine(
        clang.absolutePath,
        "-O0",       // unoptimized so every C line maps to distinct instructions —
                     // makes single-stepping through the trampoline readable.
        "-g",        // debug info (used by Phase 2's DWARF line table).
        "-fPIE",
        "-pie",
        "-Wall",
        // 16 KB page-size alignment — required by Play Store for target
        // SDK 35+ since Nov 2025. All bundled Termux prebuilts are already
        // linked with max-page-size=16384; the trampoline is the only .so
        // we compile ourselves, so it needs the flag explicitly.
        "-Wl,-z,max-page-size=16384",
        "-o", outFile.absolutePath,
        srcFile.absolutePath,
        "-ldl",
        "-llog",
    )
}

tasks.named("preBuild") {
    dependsOn(buildTrampoline)
}

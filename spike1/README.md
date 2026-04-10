# C++ IDE — Spike 1

**Goal:** validate the single most critical assumption of the whole project —
can we ship a native arm64 binary inside an Android app and actually run it on
modern Android (14 / 15)?

If this spike fails on your target device, the entire "VSCode-like C++ IDE on
Android" plan needs to be redesigned before writing any more code.

## RESULTS (Android 15 / API 35, Vivo V2324, arm64-v8a)

| # | Test | Result | Implication |
|---|---|---|---|
| **A** | `execve()` a binary from `nativeLibraryDir` | ✅ exit 0, 17ms | We can ship `clang`, `clangd`, `lldb`, `ninja` via `jniLibs`. |
| **B** | `execve()` a binary we wrote to `filesDir` | ❌ `permission denied` | SELinux `untrusted_app_35` denies exec on `app_data_file`. Not a chmod issue. |
| **D** | `dlopen()` a `.so` from `nativeLibraryDir` | ✅ `JNI_OnLoad` ran | Baseline: normal shared-lib loading works. |
| **E** | `dlopen()` a `.so` from `filesDir` | ✅ `JNI_OnLoad` ran | **The unlock:** we can load code we generated at runtime. |

logcat confirms `JNI_OnLoad` executed inside `libhellolib.so` after both D and E,
reading the global variable and logging `value=42`. That means arbitrary C/C++
code inside a dlopen'd lib runs in our app's process — not just "the file
loaded."

### What this unlocks

The IDE's compile-and-run loop now has a clear design:

1. **User writes `hello.cpp`** as normal (with `int main()`)
2. **Compile** with bundled clang using
   `-fPIC -shared -o libuser.so hello.cpp runtime_shim.c`
   where `runtime_shim.c` exports a JNI-callable entry point that invokes `main()`
3. **Write** `libuser.so` to `filesDir/work/`
4. **`System.load(absolutePath)`** — dlopen from filesDir (proven works)
5. **JNI call** into the runtime shim's entry point → user's `main()` runs
6. **Pipe stdin/stdout/stderr** via `dup2()` to file descriptors we own,
   read into the Compose terminal UI

### What stays native-binary-exec'd from nativeLibraryDir

- `clang` — compiler driver
- `clangd` — LSP server for IntelliSense
- `lldb` + `lldb-server` — step debugger (attaches to our own app process,
  which has the user's .so loaded)
- `ninja` + `cmake` — build system
- `clang-format` — code formatter
- `ripgrep` — workspace search

All of these are shipped as `lib*.so` in `jniLibs/arm64-v8a/`.

### What stays in filesDir

- Compiled user programs (`.so` files)
- Their object files, build caches
- `compile_commands.json` for clangd
- The user's source tree (or SAF-backed URIs)
- Extracted sysroot headers/libs (clang reads these, never exec'd)

## What this app proves

| Button | What it validates | Why it matters |
|---|---|---|
| **A.** Run hello from `nativeLibraryDir` | Can we `execve()` a binary shipped via `jniLibs/arm64-v8a/libX.so`? | This is how we'll ship `clang`, `clangd`, `lldb-mi`, `ninja`, etc. |
| **B.** Copy to `filesDir` + exec | Can we `execve()` a binary we wrote to app-private storage *after* install? | This is how we'll run the user's compiled programs. |
| **C.** `clang --version` | Optional — only lights up if you drop a real Android-arm64 clang into jniLibs. | End-to-end toolchain proof. |

Each validation shows exit code, stdout, stderr, and timing, so a failure tells
you exactly where the assumption broke.

## Prereqs

- **Android SDK** with platform **35** installed
- **NDK 27.1.12297006** (install via SDK Manager)
- **JDK 17+** — you have Adoptium 21 at
  `C:\Program Files\Eclipse Adoptium\jdk-21.0.6.7-hotspot` — `run.bat` already
  pins `JAVA_HOME` to it. If your JDK lives elsewhere, edit `run.bat`.
- An **Android device** (physical or emulator) running **API 26+**, ideally
  API 34+ to validate modern exec restrictions. Enable **USB debugging**.

## Build & install

From `D:\repos\maui\cpp-ide-android\spike1`:

```bat
run.bat assembleDebug
run.bat installDebug
```

Then launch "C++ IDE Spike 1" on your device.

## Reading the output

**A succeeds, B fails** — means `nativeLibraryDir` exec works but writing a new
binary to `filesDir` and exec'ing is blocked. Compiler output would need to go
somewhere else (maybe `dlopen` of code-as-shared-lib, or executing via a helper
binary in `nativeLibraryDir`).

**A fails entirely** — means Android is blocking exec of even bundled binaries.
That kills the jniLibs strategy; would need to fall back to building a native
interpreter (like Cling) into the app instead of an on-device compiler, or
require root.

**A and B both succeed** — green light to proceed with the phase 2+ plan:
bundle real `clang`/`lldb` toolchain.

## Architecture notes

### Why `jniLibs` + rename to `lib*.so`?

On Android 10+, the OS blocks exec of files in most writable paths for
security. The one place binaries are reliably marked executable is
`applicationInfo.nativeLibraryDir`, which is populated from `jniLibs/<abi>/`
during install. AGP's packager only looks for files matching `lib*.so`, so we
rename our standalone executable `libnativehello.so` — it's not actually a
shared library, it's a PIE executable that happens to have that filename. This
is the same trick Termux, Cxxdroid, and others use.

### Why `extractNativeLibs="true"`?

Modern AGP defaults to `useLegacyPackaging = false`, which mmaps native libs
directly from the APK on Android 6+. That's faster for `dlopen`, but you can't
`execve` something that isn't a real file on disk. We opt into the legacy
extraction path so the files land as real inodes in `nativeLibraryDir`.

### Why target SDK 35?

Android 15 (API 35) is where the newest exec restrictions kick in. Validating
against 35 means if it works here, it works on older versions too.

## Project layout

```
spike1/
├── run.bat                           ← convenience wrapper that sets JAVA_HOME
├── gradlew / gradlew.bat             ← standard Gradle wrapper
├── gradle/libs.versions.toml         ← version catalog
├── settings.gradle.kts
├── build.gradle.kts
├── local.properties                  ← pins SDK + NDK paths for your machine
└── app/
    ├── build.gradle.kts              ← contains the buildNativeHello task
    └── src/main/
        ├── AndroidManifest.xml       ← extractNativeLibs=true
        ├── cpp/hello.c               ← source for the native arm64 binary
        ├── assets/hello.cpp          ← source that phase 1b's clang will compile
        ├── res/values/...
        └── java/dev/cppide/spike1/
            ├── MainActivity.kt       ← Compose UI
            └── SpikeRunner.kt        ← all the exec plumbing
```

## What Gradle does on each build

1. `buildNativeHello` task runs first (hooked to `preBuild`):
   - Invokes `aarch64-linux-android26-clang.cmd` from your NDK
   - Compiles `app/src/main/cpp/hello.c` as a **PIE executable**
   - Writes it to `app/src/main/jniLibs/arm64-v8a/libnativehello.so`
2. AGP assembles the APK. The file at `jniLibs/arm64-v8a/libnativehello.so`
   gets packaged as a "native library".
3. On install, Android extracts it into the app's `nativeLibraryDir`
   (because `extractNativeLibs=true`) and marks it executable.
4. At runtime, `SpikeRunner` looks it up via
   `context.applicationInfo.nativeLibraryDir` and `ProcessBuilder`s it.

## Phase 1b — adding real clang (later)

To light up the **C.** button, drop an Android-arm64 `clang` binary into
`app/src/main/jniLibs/arm64-v8a/libclang.so`. Options:

1. **Termux packages** — `apt download clang` on a rooted device or from a
   Termux chroot, extract the .deb, grab `usr/bin/clang-19` (or whatever
   version) and supporting libs.
2. **Build LLVM from source** targeting `aarch64-linux-android`. Multi-hour
   build, but gives you a clean, redistributable toolchain.
3. **Use Cxxdroid's redistributable**  — check their license before shipping.

Clang also needs a sysroot (headers + libc/libc++), which is too big for
`jniLibs` — that should be bundled as compressed assets and extracted to
`filesDir` on first run. Validation **B.** above proves `filesDir` is readable
by the child process; writability for a sysroot isn't needed (clang only reads
the sysroot).

## Commands cheat-sheet

```bat
run.bat clean                         # wipe build outputs
run.bat buildNativeHello              # just rebuild the native hello binary
run.bat assembleDebug                 # build debug APK
run.bat installDebug                  # build and install on connected device
run.bat :app:dependencies             # dump dep tree
```

To view logs:
```bat
"C:\Program Files (x86)\Android\android-sdk\platform-tools\adb.exe" logcat -s "cppide-spike1:V" AndroidRuntime:E System.err:V
```

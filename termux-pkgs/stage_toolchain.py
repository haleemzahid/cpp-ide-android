#!/usr/bin/env python3
"""Stage Termux toolchain files into the spike1 app.

Produces:
  spike1/app/src/main/jniLibs/arm64-v8a/
    libclang.so         (renamed from clang pkg's bin/clang-21 — exec'able)
    libld.so            (renamed from lld pkg's bin/lld        — exec'able)
    libclang-cpp.so     (from clang pkg — dlopen'd by libclang.so at startup)
    libLLVM.so          (from libllvm pkg — dlopen'd transitively)
    libc++_shared.so    (from libc++ pkg — required by above)

  spike1/app/src/main/assets/termux.zip
    lib/libX.so.N       (versioned sonames — libz.so.1, libicuuc.so.78, etc.)
    sysroot/include/    (Android SDK headers)
    sysroot/lib/        (crt*.o, libc.so, libm.so, etc.)
    clang-rt/           (LLVM compiler runtime archives + clang builtin headers)

The APK ships the jniLibs as real files in nativeLibraryDir (exec/dlopen OK)
and the assets as an opaque blob. On first launch, the Kotlin side extracts
termux.zip into filesDir/termux, which is writable and readable-by-linker via
LD_LIBRARY_PATH (validated by spike 1 test E).
"""
import os
import sys
import shutil
import zipfile
import json

HERE = os.path.dirname(os.path.abspath(__file__))
CLOSURE = os.path.join(HERE, "closure")

# Destination module's src/main/ dir. Defaults to spike1 for backward compat,
# override with: python stage_toolchain.py <abs path to src/main>
SPIKE1 = (
    sys.argv[1] if len(sys.argv) > 1
    else os.path.normpath(os.path.join(HERE, "..", "spike1", "app", "src", "main"))
)

JNILIBS = os.path.join(SPIKE1, "jniLibs", "arm64-v8a")
ASSETS = os.path.join(SPIKE1, "assets")

TERMUX_USR = "data/data/data/com.termux/files/usr"  # prefix under each extracted pkg


def termux_path(pkg, *parts):
    """Absolute path to a file inside an extracted Termux package."""
    return os.path.join(CLOSURE, pkg, TERMUX_USR, *parts)


def ensure(path):
    os.makedirs(path, exist_ok=True)


def copy(src, dst):
    if not os.path.exists(src):
        raise FileNotFoundError(f"missing: {src}")
    shutil.copy2(src, dst)
    size = os.path.getsize(dst)
    rel = os.path.relpath(dst, SPIKE1)
    print(f"  copy {rel}  ({size:,} bytes)")


def stage_jnilibs():
    print("== staging jniLibs/arm64-v8a/ ==")
    ensure(JNILIBS)

    # 1. clang-21 executable -> libclang.so
    src = termux_path("clang", "bin", "clang-21")
    copy(src, os.path.join(JNILIBS, "libclang.so"))

    # 2. lld executable -> libld.so
    src = termux_path("lld", "bin", "lld")
    copy(src, os.path.join(JNILIBS, "libld.so"))

    # 3. clangd executable -> libclangd.so (LSP server for IntelliSense)
    src = termux_path("clang", "bin", "clangd")
    copy(src, os.path.join(JNILIBS, "libclangd.so"))

    # 4. lldb-server executable -> libLLDBServer.so (legacy GDB-remote path,
    # kept temporarily for the old Kotlin debugger until lldb-dap lands).
    src = termux_path("lldb", "bin", "lldb-server")
    copy(src, os.path.join(JNILIBS, "libLLDBServer.so"))

    # 4b. lldb-dap executable -> libLLDBDAP.so (DAP debug adapter for
    # VSCode-quality debugging: proper step over/into/out, call stack,
    # variables, watch, hover eval). The binary itself is ~900 KB, but it
    # dlopens liblldb.so (17 MB) which pulls in libpython3.13 + stdlib +
    # icu + openssl + ncurses. Those all live in termux.zip, not jniLibs,
    # so they ride the normal APK zip compression path instead of being
    # extracted as raw uncompressed .so files.
    src = termux_path("lldb", "bin", "lldb-dap")
    copy(src, os.path.join(JNILIBS, "libLLDBDAP.so"))

    # 5. Shared libraries with clean names (loaded by the dynamic linker at
    # exec time — sonames are unversioned so AGP's lib*.so packager accepts).
    libs_from_pkgs = {
        "libclang-cpp.so": ("clang", "lib/libclang-cpp.so"),
        "libLLVM.so":      ("libllvm", "lib/libLLVM.so"),
        "libc++_shared.so": ("libc++", "lib/libc++_shared.so"),
    }
    for dst_name, (pkg, rel) in libs_from_pkgs.items():
        copy(termux_path(pkg, *rel.split("/")), os.path.join(JNILIBS, dst_name))


def stage_assets_zip():
    """Build one termux.zip asset containing all the versioned-soname libs
    plus the sysroot + clang runtime. Extracted on first app launch."""
    print("== staging assets/termux.zip ==")
    ensure(ASSETS)
    zip_path = os.path.join(ASSETS, "termux.zip")

    # What to include
    with open(os.path.join(HERE, "closure_manifest.json")) as f:
        manifest = json.load(f)

    # Already-shipped-in-jniLibs (as clean names) — skip in zip so the APK
    # doesn't ship duplicate copies. Every soname listed here has a matching
    # entry in stage_jnilibs() above that places the same file as a clean
    # lib*.so under jniLibs/arm64-v8a/, where it's loaded via the default
    # linker namespace.
    #
    # libLLVM.so is the big one: ~40 MB compressed. Before this dedup it was
    # being shipped twice (inside termux.zip AND as jniLibs/libLLVM.so).
    already_in_jnilibs = {
        "libc++_shared.so",
        "libLLVM.so",
    }

    entries = []

    # 1) Versioned-soname libs from the closure
    for soname, info in manifest["libs"].items():
        if soname in already_in_jnilibs:
            continue
        path = info["path"]
        arcname = f"lib/{soname}"
        entries.append((path, arcname))

    # 1b) liblldb.so — not in the manifest because the resolver only walks
    # DT_NEEDED of .so files, and liblldb.so is NEEDED only by the lldb-dap
    # executable (which isn't walked). Stage it manually. ~17 MB uncompressed,
    # compresses to ~6 MB inside termux.zip.
    lldb_so = termux_path("lldb", "lib", "liblldb.so")
    if os.path.exists(lldb_so):
        entries.append((lldb_so, "lib/liblldb.so"))
    else:
        print(f"  [WARN] liblldb.so not found at {lldb_so} — "
              "lldb-dap will fail to launch")

    # 2) ndk-sysroot (Android headers + crt files + small native libs)
    # Must preserve the "usr/" prefix — clang's Android driver finds per-triple
    # headers at <sysroot>/usr/include/<triple>/ automatically when given
    # -target aarch64-linux-android*, so the layout has to be exactly:
    #   sysroot/usr/include/...
    #   sysroot/usr/lib/...
    sysroot_usr = termux_path("ndk-sysroot")  # = .../files/usr
    sysroot_root_in_termux = os.path.dirname(sysroot_usr)  # = .../files
    if os.path.exists(sysroot_usr):
        for sub in ("include", "lib"):
            src_root = os.path.join(sysroot_usr, sub)
            if not os.path.exists(src_root):
                continue
            for dirpath, _, files in os.walk(src_root):
                for f in files:
                    src = os.path.join(dirpath, f)
                    if os.path.islink(src):
                        continue
                    # rel should start with "usr/" so the final arcname is
                    # sysroot/usr/include/... (clang requirement).
                    rel = os.path.relpath(src, sysroot_root_in_termux)
                    entries.append((src, f"sysroot/{rel.replace(os.sep, '/')}"))

    # 3) Clang builtin headers + runtime archives (lib/clang/21)
    #
    # The compiler-rt package ships a full suite of sanitizer archives, most
    # of which the IDE never exposes. Keep only:
    #   - builtins  (MANDATORY — softfloat, divmod, sync primitives, etc.)
    #   - asan      (+cxx/static/preinit helpers — used for "Debug with asan")
    #   - ubsan     (minimal + standalone + cxx — catches UB for students)
    #
    # Drop: hwasan, tsan, fuzzer, lsan, profile (PGO), stats, stats_client,
    # orc_rt. Each is 1-6 MB raw; together they're ~5 MB compressed in the
    # APK with no runtime benefit for this tool.
    #
    # Anything under lib/clang/21/include/ (intrinsic headers like stdarg.h,
    # arm_neon.h) is required and always kept.
    KEPT_SAN_PREFIXES = (
        "libclang_rt.builtins",
        "libclang_rt.asan",
        "libclang_rt.ubsan_minimal",
        "libclang_rt.ubsan_standalone",
    )

    def should_ship_clang_file(rel_path: str) -> bool:
        # rel_path looks like "lib/clang/21/lib/linux/libclang_rt.tsan-...a"
        # or                   "lib/clang/21/include/stdarg.h"
        if "/lib/linux/" not in rel_path.replace(os.sep, "/"):
            return True  # intrinsic headers etc.
        basename = os.path.basename(rel_path)
        # orc_rt is the JIT runtime, unused here
        if basename.startswith("liborc_rt"):
            return False
        # compiler_rt archives all start with libclang_rt.
        if basename.startswith("libclang_rt."):
            return any(basename.startswith(p) for p in KEPT_SAN_PREFIXES)
        return True

    for pkg, subdir in [("clang", "lib/clang"), ("libcompiler-rt", "lib/clang")]:
        root = termux_path(pkg, subdir)
        if not os.path.exists(root):
            continue
        for dirpath, _, files in os.walk(root):
            for f in files:
                src = os.path.join(dirpath, f)
                if os.path.islink(src):
                    continue
                rel = os.path.relpath(src, termux_path(pkg))
                rel_norm = rel.replace(os.sep, "/")
                if not should_ship_clang_file(rel_norm):
                    continue
                entries.append((src, rel_norm))

    # 4) Python standard library (lib/python3.13) + lldb Python bindings
    #
    # liblldb.so has libpython3.13.so as a hard DT_NEEDED, so Python has
    # to initialize successfully every time lldb-dap launches — even if
    # we never run a Python script. Py_Initialize() looks for stdlib at
    # $PYTHONHOME/lib/python3.13 (we set PYTHONHOME at runtime). Without
    # stdlib, Python init aborts and liblldb.so refuses to load.
    #
    # Layout inside termux.zip:
    #   lib/python3.13/*.py                   (pure-Python stdlib)
    #   lib/python3.13/lib-dynload/*.so       (C extension modules)
    #   lib/python3.13/site-packages/lldb/    (lldb's SBDebugger Python API)
    #
    # Pulled from two packages:
    #   python.deb  — ships stdlib + lib-dynload (~17 MB raw)
    #   lldb.deb    — ships site-packages/lldb (~2 MB, wraps liblldb.so)
    #
    # We skip __pycache__ (compiled .pyc — regenerated on device if needed)
    # and .pyo (optimized bytecode — also regenerable).
    def _walk_python_tree(pkg_name, sub_rel):
        """Yield (src, arcname) pairs under termux_path(pkg)/lib/<sub_rel>."""
        pkg_root = termux_path(pkg_name)
        sub_abs = os.path.join(pkg_root, "lib", *sub_rel.split("/"))
        if not os.path.exists(sub_abs):
            return
        for dirpath, dirnames, files in os.walk(sub_abs):
            # skip __pycache__ dirs in-place (saves walk time + size)
            dirnames[:] = [d for d in dirnames if d != "__pycache__"]
            for f in files:
                if f.endswith((".pyc", ".pyo")):
                    continue
                src = os.path.join(dirpath, f)
                if os.path.islink(src):
                    continue
                rel = os.path.relpath(src, pkg_root)
                yield src, rel.replace(os.sep, "/")

    # stdlib from python package
    for src, arc in _walk_python_tree("python", "python3.13"):
        entries.append((src, arc))

    # lldb Python bindings from lldb package (site-packages/lldb/)
    for src, arc in _walk_python_tree("lldb", "python3.13/site-packages/lldb"):
        entries.append((src, arc))

    # Dedup by arcname (libcompiler-rt overlaps clang's lib/clang/21/include)
    seen = {}
    for src, arc in entries:
        if arc in seen:
            continue
        seen[arc] = src

    print(f"  {len(seen)} files to zip")
    total_src = 0
    with zipfile.ZipFile(zip_path, "w", zipfile.ZIP_DEFLATED, compresslevel=6) as zf:
        for arc, src in sorted(seen.items()):
            total_src += os.path.getsize(src)
            zf.write(src, arc)

    zip_size = os.path.getsize(zip_path)
    print(f"  termux.zip: {zip_size:,} bytes ({zip_size // (1024*1024)} MB)"
          f" from {total_src:,} bytes source ({total_src // (1024*1024)} MB)")


def report_total():
    total_jni = sum(
        os.path.getsize(os.path.join(JNILIBS, f))
        for f in os.listdir(JNILIBS)
        if os.path.isfile(os.path.join(JNILIBS, f))
    )
    termux_zip = os.path.join(ASSETS, "termux.zip")
    zip_size = os.path.getsize(termux_zip) if os.path.exists(termux_zip) else 0
    total = total_jni + zip_size
    print(f"\n== totals ==")
    print(f"  jniLibs/arm64-v8a/ : {total_jni:,} bytes ({total_jni // (1024*1024)} MB)")
    print(f"  assets/termux.zip  : {zip_size:,} bytes ({zip_size // (1024*1024)} MB)")
    print(f"  staged total       : {total:,} bytes ({total // (1024*1024)} MB)")


if __name__ == "__main__":
    if not os.path.exists(os.path.join(HERE, "closure_manifest.json")):
        print("closure_manifest.json not found — run resolve_closure.py first", file=sys.stderr)
        sys.exit(1)
    stage_jnilibs()
    stage_assets_zip()
    report_total()

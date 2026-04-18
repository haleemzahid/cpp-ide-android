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

# Destination module's src/main/ dir for jniLibs. Defaults to spike1 for
# backward compat, override with: python stage_toolchain.py <abs path to src/main>
SPIKE1 = (
    sys.argv[1] if len(sys.argv) > 1
    else os.path.normpath(os.path.join(HERE, "..", "spike1", "app", "src", "main"))
)

# Optional second arg: src/main/ dir for the asset pack module (where
# termux.zip lands). If omitted, falls back to SPIKE1/assets/ — the old
# layout. The Play-Store-compliant build splits termux.zip into a separate
# install-time asset pack module so the base APK stays under 150 MB; pass
# the asset pack module's src/main as argv[2] in that setup.
ASSETS_ROOT = sys.argv[2] if len(sys.argv) > 2 else SPIKE1

JNILIBS = os.path.join(SPIKE1, "jniLibs", "arm64-v8a")
ASSETS = os.path.join(ASSETS_ROOT, "assets")

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

    # Sonames we replace with hand-built stubs from stubs/ instead of
    # shipping the real library. Used for libicudata.so.78, which is
    # 33 MB of Unicode data tables that embedded lldb-dap never actually
    # reads (ICU is only reachable via libxml2, which for lldb's XML
    # parsing needs ASCII/UTF-8 only). The stub exports an empty
    # `icudt78_dat` symbol so the linker accepts it; ICU returns
    # invalid-data errors if anything tries to look up a code point,
    # but nothing in the hot path does.
    STUB_REPLACEMENTS = {
        "libicudata.so.78": os.path.join(HERE, "stubs", "libicudata.so.78"),
    }

    entries = []

    # 1) Versioned-soname libs from the closure
    for soname, info in manifest["libs"].items():
        if soname in already_in_jnilibs:
            continue
        stub = STUB_REPLACEMENTS.get(soname)
        if stub is not None and os.path.exists(stub):
            real_size = os.path.getsize(info["path"])
            stub_size = os.path.getsize(stub)
            print(f"  [stub] {soname}: {real_size:,} -> {stub_size:,} bytes "
                  f"(saves {(real_size - stub_size) / (1024*1024):.1f} MB)")
            entries.append((stub, f"lib/{soname}"))
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
    # We trim aggressively: lldb-dap only needs Python init, the lldb
    # SBDebugger module, and a handful of formatter helpers. Whole
    # subtrees of stdlib (networking, GUI, async, multiprocessing,
    # email, etc.) are dead weight in the embedded scenario.
    #
    # SKIP_PY_DIRS — pure-Python subdirectories we drop entirely.
    # Verified safe by tracing what lldb's site-packages/lldb/__init__.py
    # actually imports during Py_Initialize() + SBDebugger setup.
    # If something in here turns out to be needed, the symptom is
    # `ModuleNotFoundError` from inside lldb-dap on stderr — easy
    # to diagnose, just remove the offending entry.
    SKIP_PY_DIRS = {
        "asyncio",          # async I/O — lldb is sync
        "concurrent",       # futures
        "email",            # email parsing
        "html",             # HTML parser
        "http",             # HTTP client/server
        "idlelib",          # IDLE editor
        "lib2to3",          # 2-to-3 conversion (gone in 3.12+ but defensive)
        "logging",          # surface area we never touch
        "multiprocessing",  # subprocess management
        "_pyrepl",          # interactive REPL — lldb has its own
        "pydoc_data",       # help() doc strings
        "test",             # test suite
        "tkinter",          # GUI (not present in Termux Python anyway)
        "turtledemo",       # graphics demos
        "unittest",         # test framework
        "urllib",           # URL handling
        "venv",             # virtualenv — won't work on Android
        "wsgiref",          # WSGI web server
        "xml",              # Python XML — lldb uses libxml2 (C)
        "xmlrpc",           # XML-RPC
        "ensurepip",        # pip installer
        "dbm",              # database modules
    }

    # SKIP_DYNLOAD_PREFIXES — C extension modules we drop from
    # lib-dynload/. Each .so is 50-500 KB. We keep only the ones
    # Python init / lldb actually loads.
    SKIP_DYNLOAD_PREFIXES = (
        "_asyncio.",            # asyncio C accelerator
        "_multiprocessing.",
        "_posixsubprocess.",    # only used by subprocess (kept) — but multiprocessing pulls it
        "_elementtree.",        # XML
        "_dbm.",                # dbm
        "_gdbm.",               # gdbm
        "_curses.",             # curses
        "_curses_panel.",
        "audioop.",
        "_codecs_cn.",          # Chinese charset codecs — UTF-8 is enough
        "_codecs_hk.",
        "_codecs_iso2022.",
        "_codecs_jp.",
        "_codecs_kr.",
        "_codecs_tw.",
        "_testcapi.",           # CPython test
        "_test",                # any other _test*
        "_xxsubinterpreters.",
        "_xxinterpchannels.",
        "xxlimited.",
        "_zoneinfo.",           # timezone DB
        "ossaudiodev.",
        "spwd.",
        "syslog.",
        "termios.",             # terminal IO — Android doesn't really have one
        "readline.",            # GNU readline
        "_decimal.",            # decimal arithmetic — lldb doesn't need
        "_ctypes_test.",
    )

    def _is_skipped_dir(rel_norm: str) -> bool:
        # rel_norm looks like "lib/python3.13/asyncio/events.py" — split
        # off the third path segment and check membership.
        parts = rel_norm.split("/")
        if len(parts) >= 3 and parts[0] == "lib" and parts[1] == "python3.13":
            return parts[2] in SKIP_PY_DIRS
        return False

    def _is_skipped_dynload(basename: str) -> bool:
        return any(basename.startswith(p) for p in SKIP_DYNLOAD_PREFIXES)

    def _walk_python_tree(pkg_name, sub_rel):
        """Yield (src, arcname) pairs under termux_path(pkg)/lib/<sub_rel>."""
        pkg_root = termux_path(pkg_name)
        sub_abs = os.path.join(pkg_root, "lib", *sub_rel.split("/"))
        if not os.path.exists(sub_abs):
            return
        for dirpath, dirnames, files in os.walk(sub_abs):
            # skip __pycache__ dirs in-place (saves walk time + size)
            dirnames[:] = [d for d in dirnames if d != "__pycache__"]
            # skip whole stdlib subdirs we know are dead weight.
            dirnames[:] = [d for d in dirnames if d not in SKIP_PY_DIRS]
            for f in files:
                if f.endswith((".pyc", ".pyo")):
                    continue
                src = os.path.join(dirpath, f)
                if os.path.islink(src):
                    continue
                rel = os.path.relpath(src, pkg_root)
                rel_norm = rel.replace(os.sep, "/")
                # Belt-and-braces: re-check at the file level too.
                if _is_skipped_dir(rel_norm):
                    continue
                # Drop unwanted lib-dynload C extensions.
                if "/lib-dynload/" in rel_norm and _is_skipped_dynload(f):
                    continue
                yield src, rel_norm

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

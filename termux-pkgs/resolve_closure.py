#!/usr/bin/env python3
"""Transitive dependency resolver for Termux aarch64 packages.

Given an initial set of package names, this:
  1. Downloads each .deb from Termux's public apt repo
  2. Extracts it (via extract_deb.py)
  3. Walks every .so file, reads DT_NEEDED entries via `readelf -d`
  4. Resolves each unseen soname to a package via Contents-aarch64.gz index
  5. Downloads + extracts that package, repeats
  6. Stops when the graph is closed

Output: a CSV-ish manifest listing every shared-object file the final set
needs, along with which package it came from. Downstream Gradle task will
read this and copy files into jniLibs / assets.
"""
import os
import sys
import gzip
import json
import subprocess
import urllib.request

TERMUX_BASE = "https://packages.termux.dev/apt/termux-main/"
PKG_DIR = os.path.dirname(os.path.abspath(__file__))
WORK = os.path.join(PKG_DIR, "closure")
os.makedirs(WORK, exist_ok=True)

# /system/lib64 on Android already provides these sonames exactly —
# clang binaries link against them and they're guaranteed available.
SYSTEM_LIBS = {
    "libc.so", "libm.so", "libdl.so", "libandroid.so", "liblog.so",
    "libGLESv2.so", "libGLESv3.so", "libEGL.so", "libjnigraphics.so",
    "libOpenSLES.so", "libOpenMAXAL.so", "libmediandk.so", "libz.so",
    "libstdc++.so", "libvulkan.so", "libnativewindow.so", "libaaudio.so",
    "ld-android.so",
}


def readelf_needed(path):
    """Return list of DT_NEEDED sonames for an ELF file."""
    try:
        out = subprocess.check_output(
            ["readelf", "-d", path],
            text=True,
            stderr=subprocess.DEVNULL,
        )
    except Exception:
        return []
    needed = []
    for line in out.split("\n"):
        if "(NEEDED)" in line and "[" in line:
            needed.append(line.rsplit("[", 1)[-1].rstrip("]\n "))
    return needed


def load_contents_index():
    """Parse Contents-aarch64.gz into {soname: package_name}."""
    path = os.path.join(PKG_DIR, "Contents.gz")
    if not os.path.exists(path):
        print("  downloading Contents-aarch64.gz")
        urllib.request.urlretrieve(
            TERMUX_BASE + "dists/stable/main/Contents-aarch64.gz", path
        )
    d = {}
    with gzip.open(path, "rt", encoding="utf-8", errors="ignore") as f:
        for line in f:
            line = line.rstrip()
            if not line:
                continue
            # Format: "<file path> <section/package>"
            parts = line.rsplit(None, 1)
            if len(parts) != 2:
                continue
            file_path, pkg = parts
            pkg = pkg.split("/")[-1]
            # Only look at usr/lib/ .so files
            if "/usr/lib/" in file_path and ".so" in file_path:
                soname = os.path.basename(file_path)
                if soname not in d:
                    d[soname] = pkg
    return d


def load_packages_index():
    """Parse Packages.gz into {package_name: deb_filename}."""
    path = os.path.join(PKG_DIR, "Packages.gz")
    if not os.path.exists(path):
        print("  downloading Packages.gz")
        urllib.request.urlretrieve(
            TERMUX_BASE + "dists/stable/main/binary-aarch64/Packages.gz", path
        )
    d = {}
    cur = {}
    with gzip.open(path, "rt", encoding="utf-8") as f:
        for line in f:
            line = line.rstrip()
            if line.startswith("Package: "):
                cur = {"name": line[9:].strip()}
            elif line.startswith("Filename: "):
                cur["file"] = line[10:].strip()
            elif line == "" and "name" in cur and "file" in cur:
                d[cur["name"]] = cur["file"]
                cur = {}
    return d


def ensure_pkg(pkg_name, pkg_index):
    """Download + extract a package if not already done. Returns extract dir."""
    extract_dir = os.path.join(WORK, pkg_name)
    if os.path.exists(extract_dir) and os.listdir(extract_dir):
        return extract_dir
    if pkg_name not in pkg_index:
        print(f"  [WARN] no package named '{pkg_name}'")
        return None
    rel = pkg_index[pkg_name]
    deb_path = os.path.join(WORK, pkg_name + ".deb")
    if not os.path.exists(deb_path):
        print(f"  fetch {rel}")
        urllib.request.urlretrieve(TERMUX_BASE + rel, deb_path)
    os.makedirs(extract_dir, exist_ok=True)
    subprocess.run(
        ["python", os.path.join(PKG_DIR, "extract_deb.py"), deb_path, extract_dir],
        check=True,
        stdout=subprocess.DEVNULL,
    )
    return extract_dir


def walk_so_files(root):
    """Yield paths of .so files (real files only, not symlinks)."""
    for dirpath, _, files in os.walk(root):
        for f in files:
            if ".so" not in f:
                continue
            path = os.path.join(dirpath, f)
            if os.path.islink(path):
                continue
            yield path


def find_soname(extract_root, soname):
    """Find a soname in an extracted package, following symlinks to the real file."""
    for dirpath, _, files in os.walk(extract_root):
        if soname in files:
            path = os.path.join(dirpath, soname)
            # Resolve symlink to the real file (stays within same dir usually)
            if os.path.islink(path):
                target = os.readlink(path)
                if not os.path.isabs(target):
                    target = os.path.normpath(os.path.join(dirpath, target))
                if os.path.exists(target) and not os.path.islink(target):
                    return target
                # Symlink chain — try once more
                if os.path.islink(target):
                    target2 = os.readlink(target)
                    if not os.path.isabs(target2):
                        target2 = os.path.normpath(os.path.join(os.path.dirname(target), target2))
                    if os.path.exists(target2) and not os.path.islink(target2):
                        return target2
                continue
            return path
    return None


def resolve_closure(initial_pkgs):
    print("Loading Termux indices...")
    contents_idx = load_contents_index()
    pkg_idx = load_packages_index()
    print(f"  {len(contents_idx)} sonames indexed, {len(pkg_idx)} packages")

    seen_pkgs = set()
    resolved = {}  # soname -> file path
    missing = set()

    # Seed
    for p in initial_pkgs:
        print(f"Seed: {p}")
        if ensure_pkg(p, pkg_idx):
            seen_pkgs.add(p)

    # Iterate until fixed point
    iteration = 0
    while True:
        iteration += 1
        added_this_round = 0

        # Collect all NEEDED entries from every .so in seen packages
        needed_all = set()
        for pkg in list(seen_pkgs):
            d = os.path.join(WORK, pkg)
            if not os.path.exists(d):
                continue
            for so_path in walk_so_files(d):
                for n in readelf_needed(so_path):
                    needed_all.add(n)

        # For each needed soname: if not system, not already resolved,
        # find which package provides it and download.
        for soname in sorted(needed_all):
            if soname in SYSTEM_LIBS:
                continue
            if soname in resolved:
                continue
            if soname in missing:
                continue
            pkg = contents_idx.get(soname)
            if not pkg:
                print(f"  [?] {soname}: no package found in Contents index")
                missing.add(soname)
                continue
            if pkg not in seen_pkgs:
                print(f"  + {soname} -> {pkg}")
                if ensure_pkg(pkg, pkg_idx):
                    seen_pkgs.add(pkg)
                    added_this_round += 1

        # Try to resolve all needed sonames to actual files in the extracted tree
        for soname in sorted(needed_all):
            if soname in SYSTEM_LIBS:
                continue
            if soname in resolved:
                continue
            for pkg in seen_pkgs:
                d = os.path.join(WORK, pkg)
                if not os.path.exists(d):
                    continue
                found = find_soname(d, soname)
                if found:
                    resolved[soname] = (pkg, found)
                    break
            if soname not in resolved and soname not in missing:
                # still no file — mark missing
                print(f"  [?] {soname}: no file found in extracted packages")

        print(f"  iteration {iteration}: +{added_this_round} pkgs, "
              f"{len(resolved)} resolved, {len(seen_pkgs)} packages, "
              f"{len(missing)} unresolved")

        if added_this_round == 0:
            break

    return resolved, seen_pkgs, missing


def main():
    # Compiler/linker/debugger closure. `lldb` is included because we ship
    # `lldb-dap` for VSCode-quality debugging (call stack, variables, proper
    # step over/into/out). lldb-dap requires liblldb.so which pulls in its
    # full transitive closure: libpython3.13 (+stdlib), ncurses, libedit,
    # libxml2, openssl, libffi, etc. This is a deliberate tradeoff — the APK
    # grows by tens of MB, but we get real debugging instead of reimplementing
    # lldb in Kotlin.
    initial = [
        "clang",           # driver + libclang-cpp.so
        "libllvm",         # libLLVM.so
        "libc++",          # libc++_shared.so
        "lld",             # linker
        "libcompiler-rt",  # compiler runtime (libclang_rt.*.a)
        "ndk-sysroot",     # android headers + libs (crt*.o, libc.a, etc.)
        "lldb",            # lldb-dap + liblldb.so (DAP debug adapter)
    ]
    print(f"Resolving closure from: {initial}")
    resolved, pkgs, missing = resolve_closure(initial)

    print(f"\n=== Result: {len(pkgs)} packages, {len(resolved)} sonames resolved, {len(missing)} missing ===\n")

    # Dump a manifest file the Gradle task can consume
    manifest_path = os.path.join(PKG_DIR, "closure_manifest.json")
    manifest = {
        "packages": sorted(pkgs),
        "libs": {
            soname: {"package": pkg, "path": path}
            for soname, (pkg, path) in sorted(resolved.items())
        },
        "missing": sorted(missing),
    }
    with open(manifest_path, "w") as f:
        json.dump(manifest, f, indent=2)
    print(f"wrote {manifest_path}")

    # Human-readable summary
    total_size = 0
    print("\nResolved sonames:")
    for soname, (pkg, path) in sorted(resolved.items()):
        size = os.path.getsize(path)
        total_size += size
        print(f"  {soname:45s}  {size:>12,}  [{pkg}]")
    print(f"\ntotal: {total_size:,} bytes ({total_size // (1024*1024)} MB)")

    if missing:
        print(f"\nMISSING ({len(missing)}):")
        for m in sorted(missing):
            print(f"  {m}")


if __name__ == "__main__":
    main()

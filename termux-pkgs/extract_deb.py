#!/usr/bin/env python3
"""Extract a Debian .deb file. A .deb is an ar archive containing:
  - debian-binary   (text)
  - control.tar.*   (package metadata)
  - data.tar.*      (actual files)
This script extracts the data tarball into <out>/data/ and the control
files into <out>/control/.
"""
import sys, os, tarfile, io, lzma

def parse_ar(path):
    """Yield (name, data_bytes) for each entry in an ar archive."""
    with open(path, "rb") as f:
        magic = f.read(8)
        if magic != b"!<arch>\n":
            raise ValueError(f"Not an ar archive: {path}")
        while True:
            header = f.read(60)
            if not header or len(header) < 60:
                return
            name = header[0:16].decode("ascii").rstrip().rstrip("/")
            size = int(header[48:58].decode("ascii").strip())
            data = f.read(size)
            # ar pads entries to even length
            if size % 2 == 1:
                f.read(1)
            yield name, data

def extract_tar_bytes(data, outdir, label):
    # data tarball may be .xz, .gz, or uncompressed
    fileobj = io.BytesIO(data)
    try:
        with tarfile.open(fileobj=fileobj, mode="r:*") as tf:
            tf.extractall(outdir, filter="data")
        print(f"  extracted {label} -> {outdir}")
    except Exception as e:
        print(f"  ERROR extracting {label}: {e}", file=sys.stderr)
        raise

def main():
    if len(sys.argv) != 3:
        print("Usage: extract_deb.py <file.deb> <outdir>", file=sys.stderr)
        sys.exit(1)
    deb = sys.argv[1]
    outdir = sys.argv[2]
    os.makedirs(outdir, exist_ok=True)

    for name, data in parse_ar(deb):
        print(f"entry: {name} ({len(data)} bytes)")
        if name.startswith("data.tar"):
            d = os.path.join(outdir, "data")
            os.makedirs(d, exist_ok=True)
            extract_tar_bytes(data, d, "data")
        elif name.startswith("control.tar"):
            d = os.path.join(outdir, "control")
            os.makedirs(d, exist_ok=True)
            extract_tar_bytes(data, d, "control")
        elif name == "debian-binary":
            print(f"  format: {data.decode().strip()}")

if __name__ == "__main__":
    main()

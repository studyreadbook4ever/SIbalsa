#!/usr/bin/env python3
# SPDX-License-Identifier: Unlicense
"""Fail closed on common source-publication and artifact-provenance mistakes.

This is a reproducible distribution check, not legal advice or proof of secret absence.
Run only after source review and meaningful tests. Never prints matching secret values.
"""
import argparse
import hashlib
import io
from pathlib import Path
import re
import subprocess
import struct
import zipfile

ROOT = Path(__file__).resolve().parents[1]
FORBIDDEN = {".apk", ".aar", ".so", ".o", ".a", ".class", ".pyc", ".db", ".sqlite", ".pem", ".key",
             ".crt", ".keystore", ".jks", ".sbm", ".sba", ".sib", ".siblora", ".safetensors", ".gguf", ".sibalsa"}
SOURCE = {".java", ".cpp", ".h", ".py", ".sh", ".xml", ".yml", ".pro"}
SECRET = [re.compile(rb"-----BEGIN (?:RSA |EC |OPENSSH |DSA )?PRIVATE KEY-----"),
          re.compile(rb"\b(?:gh[pousr]_[A-Za-z0-9]{30,}|github_pat_[A-Za-z0-9_]{40,}|sk-[A-Za-z0-9]{40,})\b")]
NOTICE_FILES = ["SIbalsa-Unlicense.txt", "NOTICE.md", "Android-NDK-NOTICE.txt", "Android-NDK-NOTICE.toolchain.txt"]


def require(condition, message):
    if not condition:
        raise SystemExit("Audit failed: " + message)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source-only", action="store_true")
    args = parser.parse_args()
    listed = subprocess.check_output(["git", "ls-files", "--cached", "--others", "--exclude-standard", "-z"], cwd=ROOT)
    names = sorted(set(name.decode("utf-8") for name in listed.split(b"\0") if name))
    require(names, "empty source inventory")
    for name in names:
        path = ROOT / name
        require(not path.is_symlink(), "symlink in source inventory: " + name)
        require(path.is_file(), "missing inventoried file: " + name)
        require(path.suffix.lower() not in FORBIDDEN and not name.startswith((".build/", "dist/", "models/", "private/")),
                "private/generated artifact in source inventory: " + name)
        require(path.name not in {"tokenizer.json", "integration.local.json"} and not path.name.startswith((".env", "local.properties")),
                "local configuration/model artifact in source: " + name)
        raw = path.read_bytes()
        require(b"\0" not in raw, "binary source artifact: " + name)
        require(not any(pattern.search(raw) for pattern in SECRET), "possible secret in " + name)
        if path.suffix in SOURCE or path.name == "CMakeLists.txt":
            require(b"SPDX-License-Identifier: Unlicense" in raw[:1024], "missing original source license: " + name)
    license_bytes = (ROOT / "LICENSE").read_bytes()
    require(b"This is free and unencumbered software released into the public domain." in license_bytes
            and b"THE SOFTWARE IS PROVIDED" in license_bytes and b"unlicense.org" in license_bytes,
            "Unlicense grant/disclaimer missing")
    inventory = hashlib.sha256()
    for name in names:
        inventory.update(name.encode() + b"\0" + hashlib.sha256((ROOT / name).read_bytes()).digest())
    print("Source inventory:", len(names), "files; SHA-256", inventory.hexdigest())
    if args.source_only:
        return
    for filename in ["SIbalsa.aar", "SIbalsa-example.apk"]:
        path = ROOT / "dist" / filename
        require(path.is_file(), "missing distribution: " + filename)
        with zipfile.ZipFile(path) as archive:
            entries = archive.namelist()
            require(len(entries) == len(set(entries)), "duplicate artifact entries: " + filename)
            require(not any(Path(name).suffix.lower() in FORBIDDEN - {".so", ".class"} for name in entries),
                    "model/private artifact embedded: " + filename)
            for notice in NOTICE_FILES:
                name = "assets/notices/" + notice
                require(name in entries and len(archive.read(name)) > 100, "required notice missing: " + filename + "/" + notice)
            require(archive.read("assets/notices/SIbalsa-Unlicense.txt") == license_bytes, "binary original license mismatch")
            require(archive.read("assets/notices/NOTICE.md") == (ROOT / "NOTICE.md").read_bytes(), "binary NOTICE mismatch")
            for abi in ["arm64-v8a", "x86_64"]:
                prefix = "jni/" if filename.endswith(".aar") else "lib/"
                for library in ["libsibalsa_android.so"]:
                    library_path = prefix + abi + "/" + library
                    require(library_path in entries, "missing ABI/runtime: " + filename)
                    elf = archive.read(library_path)
                    require(elf[:6] == b"\x7fELF\x02\x01" and len(elf) >= 64, "expected little-endian ELF64")
                    ph_offset = struct.unpack_from("<Q", elf, 32)[0]
                    ph_size, ph_count = struct.unpack_from("<HH", elf, 54)
                    require(ph_size >= 56 and 0 < ph_count <= 64 and ph_offset + ph_size * ph_count <= len(elf),
                            "invalid ELF program table")
                    loads = 0
                    for index in range(ph_count):
                        header = struct.unpack_from("<IIQQQQQQ", elf, ph_offset + ph_size * index)
                        if header[0] == 1:
                            loads += 1
                            require(header[7] >= 16384 and header[2] % header[7] == header[3] % header[7],
                                    "native ELF missing 16 KiB page alignment: " + filename + "/" + abi)
                        elif header[0] == 0x6474e552:
                            require((header[3] + header[6]) % 16384 == 0,
                                    "native RELRO end missing 16 KiB alignment: " + filename + "/" + abi)
                    require(loads > 0, "ELF has no load segments")
                require(not any(prefix + abi + "/" + old in entries for old in
                                ["libsibalsa_inference.so", "libsibalsa_training.so", "libc++_shared.so"]),
                        "multiple C++ runtime packaging: " + filename)
            if filename.endswith(".aar"):
                require(b"package=\"org.sibalsa.android\"" in archive.read("AndroidManifest.xml"), "AAR namespace missing")
                require(b"ai.sibalsa.runtime.**" in archive.read("proguard.txt"), "JNI consumer keep rules missing")
                with zipfile.ZipFile(io.BytesIO(archive.read("classes.jar"))) as classes:
                    require(all(not name.endswith(".class") or name.startswith(("ai/sibalsa/", "org/sibalsa/"))
                                for name in classes.namelist()), "unexpected vendored Java class")
        print(filename, "SHA-256", hashlib.sha256(path.read_bytes()).hexdigest())
    print("Distribution checks passed; provenance/legal review and test results remain required.")


if __name__ == "__main__":
    main()

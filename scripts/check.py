#!/usr/bin/env python3
# SPDX-License-Identifier: Unlicense
"""Run independent host checks using standard toolchains and generated temporary weights."""
import argparse
import os
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]


def run(*args, env=None):
    print("+", " ".join(map(str, args)), flush=True)
    subprocess.run(list(map(str, args)), cwd=ROOT, env=env, check=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sanitizers", action="store_true", help="Also run native AddressSanitizer/UBSan checks")
    args = parser.parse_args()
    native = ROOT / ".build/native-host"
    run("cmake", "-S", ROOT / "native", "-B", native, "-G", "Ninja", "-DCMAKE_BUILD_TYPE=Release",
        "-DSIBALSA_BUILD_JNI=ON", "-DSIBALSA_BUILD_TESTS=ON", "-DSIBALSA_BUILD_CLI=ON", "-DSIBALSA_SANITIZERS=OFF")
    run("cmake", "--build", native, "--parallel", "4")
    run("ctest", "--test-dir", native, "--output-on-failure")
    jni = ROOT / ".build/jni-check"
    jni.mkdir(parents=True, exist_ok=True)
    fixture = jni / "generated-model.sbm"
    run(native / "sibalsa_native_cli", "fixture", fixture, "int8")
    bridges = ROOT / "android/library/src/main/java/ai/sibalsa/runtime"
    run("javac", "--release", "11", "-d", jni, *sorted(bridges.glob("*.java")),
        ROOT / "native/tests/JniSmoke.java")
    run("java", "-ea", "-Djava.library.path=" + str(native), "-cp", jni, "JniSmoke", fixture)
    run("sh", ROOT / "android/library/test-core.sh")
    environment = os.environ.copy()
    environment["SIBALSA_NATIVE_CLI"] = str(native / "sibalsa_native_cli")
    run("python3", "-W", "error::ResourceWarning", "-m", "unittest", "discover", "-s", "scripts/tests", "-v", env=environment)
    environment["PYTHONPATH"] = str(ROOT / "server")
    run("python3", "-W", "error::ResourceWarning", "-m", "unittest", "discover", "-s", "server/tests", "-v", env=environment)
    if args.sanitizers:
        sanitized = ROOT / ".build/native-sanitizers"
        run("cmake", "-S", ROOT / "native", "-B", sanitized, "-G", "Ninja", "-DCMAKE_BUILD_TYPE=Debug",
            "-DSIBALSA_BUILD_JNI=OFF", "-DSIBALSA_SANITIZERS=ON", "-DSIBALSA_BUILD_TESTS=ON")
        run("cmake", "--build", sanitized, "--parallel", "4")
        run("ctest", "--test-dir", sanitized, "--output-on-failure")
    print("Host checks passed. Android builds and physical gate tests are separate; see docs/VALIDATION.md.")


if __name__ == "__main__":
    main()

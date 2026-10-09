#!/usr/bin/env python3
# SPDX-License-Identifier: Unlicense
"""Build disposable Android instrumentation tests with generated random weights, never pretrained models."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[1]


def run(*args):
    print("+", " ".join(map(str, args)), flush=True)
    subprocess.run(list(map(str, args)), cwd=ROOT, check=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sdk", type=Path, default=Path(os.environ.get("ANDROID_SDK_ROOT", os.environ.get("ANDROID_HOME", str(Path.home() / "Android/Sdk")))))
    parser.add_argument("--api", type=int, default=35)
    parser.add_argument("--build-tools", default="35.0.0")
    parser.add_argument("--development-ca", type=Path, help="Public test CA PEM to include only in disposable test APK")
    parser.add_argument("--adapter-spec", default="head-lora-r2-s1-v1")
    args = parser.parse_args()
    sdk = args.sdk.resolve()
    tools = sdk / "build-tools" / args.build_tools
    android_jar = sdk / "platforms" / ("android-" + str(args.api)) / "android.jar"
    main_build = ROOT / ".build/android"
    build = ROOT / ".build/android-tests"
    build.mkdir(parents=True, exist_ok=True)
    for directory in [build / "classes", build / "dex", build / "assets"]:
        if directory.exists():
            shutil.rmtree(directory)
        directory.mkdir()
    assets = build / "assets"
    cli = ROOT / ".build/native-host/sibalsa_native_cli"
    if not cli.is_file() or not (main_build / "sdk.jar").is_file() or not (main_build / "app.jar").is_file():
        parser.error("Build native-host CLI and Android app first")
    run(cli, "fixture", assets / "model.sbm", "int8")
    info = json.loads(subprocess.check_output([str(cli), "info", str(assets / "model.sbm")], text=True))
    rank = 2
    run(cli, "init-adapter", assets / "model.sbm", rank, 42, assets / "adapter.json")
    manifest = dict(format_version=1, model_file="model.sbm", model_id=hashlib.sha256((assets / "model.sbm").read_bytes()).hexdigest(),
                    adapter_spec=args.adapter_spec, dimension=rank * (info["vocab"] + info["dim"]), rank=rank,
                    max_context=64, bos_token_id=256, eos_token_ids=[257], tokenizer_type="byte", prompt_prefix="",
                    prompt_suffix="", seed=42, dpo_beta=0.1, gradient_clip=1.0)
    (assets / "integration.json").write_text(json.dumps(manifest, indent=2) + "\n")
    if args.development_ca:
        if not args.development_ca.is_file():
            parser.error("Development CA file does not exist")
        shutil.copyfile(args.development_ca, assets / "development-ca.crt")
    sources = sorted((ROOT / "android/tests/java").rglob("*.java"))
    classpath = os.pathsep.join(str(p) for p in [android_jar, main_build / "sdk.jar", main_build / "app.jar"])
    run("javac", "-encoding", "UTF-8", "-source", "8", "-target", "8", "-Xlint:all,-options", "-cp", classpath,
        "-d", build / "classes", *sources)
    test_jar = build / "tests.jar"
    with zipfile.ZipFile(test_jar, "w", zipfile.ZIP_DEFLATED) as archive:
        for file in sorted((build / "classes").rglob("*.class")):
            archive.write(file, file.relative_to(build / "classes").as_posix())
    run(tools / "d8", "--lib", android_jar, "--classpath", main_build / "sdk.jar", "--classpath", main_build / "app.jar",
        "--min-api", "26", "--output", build / "dex", test_jar)
    unsigned = build / "unsigned.apk"
    run(tools / "aapt2", "link", "-o", unsigned, "--manifest", ROOT / "android/tests/AndroidManifest.xml", "-I", android_jar,
        "-A", assets, "--min-sdk-version", "26", "--target-sdk-version", args.api)
    with zipfile.ZipFile(unsigned, "a", zipfile.ZIP_DEFLATED) as archive:
        for dex in (build / "dex").glob("*.dex"):
            archive.write(dex, dex.name)
    aligned = build / "aligned.apk"
    run(tools / "zipalign", "-f", "4", unsigned, aligned)
    output = build / "SIbalsa-device-tests.apk"
    run(tools / "apksigner", "sign", "--ks", main_build / "debug.keystore", "--ks-key-alias", "sibalsa-debug",
        "--ks-pass", "pass:android", "--key-pass", "pass:android", "--out", output, aligned)
    run(tools / "apksigner", "verify", output)
    print("Test APK:", output)
    print("Generated integration / adapter:", assets / "integration.json", assets / "adapter.json")
    print("Disposable emulator run: adb -s emulator-5554 shell am instrument -w -e mode smoke org.sibalsa.tests/org.sibalsa.example.DeviceInstrumentation")


if __name__ == "__main__":
    main()

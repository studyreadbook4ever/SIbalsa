#!/usr/bin/env python3
# SPDX-License-Identifier: Unlicense
"""Build the dependency-free Android AAR and example APK with official SDK tools."""
import argparse
import os
from pathlib import Path
import shutil
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[1]


def run(*args):
    print("+", " ".join(map(str, args)), flush=True)
    subprocess.run(list(map(str, args)), check=True, cwd=ROOT)


def jar_directory(path, output):
    with zipfile.ZipFile(output, "w", zipfile.ZIP_DEFLATED) as archive:
        for file in sorted(path.rglob("*")):
            if file.is_file():
                archive.write(file, file.relative_to(path).as_posix())


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sdk", type=Path, default=Path(os.environ.get("ANDROID_SDK_ROOT", os.environ.get("ANDROID_HOME", str(Path.home() / "Android/Sdk")))))
    parser.add_argument("--ndk-version", default="27.2.12479018")
    parser.add_argument("--api", type=int, default=35)
    parser.add_argument("--build-tools", default="35.0.0")
    parser.add_argument("--abis", nargs="+", choices=["arm64-v8a", "x86_64"], default=["arm64-v8a", "x86_64"])
    parser.add_argument("--skip-native", action="store_true", help="Reuse existing ABI libraries for a Java-only rebuild")
    args = parser.parse_args()
    sdk = args.sdk.resolve()
    ndk = sdk / "ndk" / args.ndk_version
    tools = sdk / "build-tools" / args.build_tools
    android_jar = sdk / "platforms" / ("android-" + str(args.api)) / "android.jar"
    for path in [android_jar, tools / "aapt2", tools / "d8", tools / "apksigner", ndk / "build/cmake/android.toolchain.cmake"]:
        if not path.is_file():
            parser.error("Missing official Android tool: " + str(path))
    build = ROOT / ".build/android"
    build.mkdir(parents=True, exist_ok=True)
    output = ROOT / "dist"
    output.mkdir(exist_ok=True)
    library = ROOT / "android/library/src/main"
    example = ROOT / "android/example/src/main"
    sdk_classes = build / "sdk-classes"
    app_classes = build / "app-classes"
    for directory in [sdk_classes, app_classes, build / "dex"]:
        if directory.exists():
            shutil.rmtree(directory)
        directory.mkdir()
    sdk_sources = sorted((library / "java").rglob("*.java"))
    app_sources = sorted((example / "java").rglob("*.java"))
    if not sdk_sources or not app_sources:
        parser.error("Android library and example sources are required")
    run("javac", "-encoding", "UTF-8", "-source", "8", "-target", "8", "-Xlint:all,-options", "-cp", android_jar, "-d", sdk_classes, *sdk_sources)
    run("javac", "-encoding", "UTF-8", "-source", "8", "-target", "8", "-Xlint:all,-options", "-cp", str(android_jar) + os.pathsep + str(sdk_classes), "-d", app_classes, *app_sources)
    sdk_jar = build / "sdk.jar"
    app_jar = build / "app.jar"
    jar_directory(sdk_classes, sdk_jar)
    jar_directory(app_classes, app_jar)
    native_libraries = {}
    for abi in args.abis:
        native_build = ROOT / ".build" / ("native-" + abi)
        if not args.skip_native:
            run("cmake", "-S", ROOT / "native", "-B", native_build, "-G", "Ninja",
                "-DCMAKE_TOOLCHAIN_FILE=" + str(ndk / "build/cmake/android.toolchain.cmake"),
                "-DANDROID_ABI=" + abi, "-DANDROID_PLATFORM=android-26", "-DANDROID_STL=c++_static",
                "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON",
                "-DCMAKE_BUILD_TYPE=Release", "-DSIBALSA_BUILD_TESTS=OFF")
            run("cmake", "--build", native_build, "--parallel", "4")
        for name in ["libsibalsa_android.so"]:
            matches = list(native_build.rglob(name))
            if len(matches) != 1:
                raise RuntimeError("Expected one " + name + " for " + abi)
            native_libraries[(abi, name)] = matches[0]
    notices = build / "assets/notices"
    notices.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(ROOT / "LICENSE", notices / "SIbalsa-Unlicense.txt")
    shutil.copyfile(ROOT / "NOTICE.md", notices / "NOTICE.md")
    # NDK standard-library/runtime notices remain separate from our Unlicense.
    for notice_name in ["NOTICE", "NOTICE.toolchain"]:
        ndk_notice = ndk / notice_name
        if not ndk_notice.is_file():
            raise RuntimeError("NDK redistribution notices are required for APK/AAR")
        shutil.copyfile(ndk_notice, notices / ("Android-NDK-" + notice_name + ".txt"))
    aar_path = output / "SIbalsa.aar"
    with zipfile.ZipFile(aar_path, "w", zipfile.ZIP_DEFLATED) as aar:
        aar.write(library / "AndroidManifest.xml", "AndroidManifest.xml")
        aar.write(sdk_jar, "classes.jar")
        aar.write(ROOT / "android/library/consumer-rules.pro", "proguard.txt")
        for (abi, name), file in native_libraries.items():
            aar.write(file, "jni/" + abi + "/" + name)
        for file in notices.iterdir():
            aar.write(file, "assets/notices/" + file.name)
    resources = build / "resources.zip"
    run(tools / "aapt2", "compile", "--dir", example / "res", "-o", resources)
    unsigned = build / "unsigned.apk"
    run(tools / "aapt2", "link", "-o", unsigned, "--manifest", example / "AndroidManifest.xml", "-I", android_jar,
        "--min-sdk-version", "26", "--target-sdk-version", str(args.api), "--version-code", "1", "--version-name", "0.1.0", resources)
    run(tools / "d8", "--lib", android_jar, "--min-api", "26", "--output", build / "dex", sdk_jar, app_jar)
    with zipfile.ZipFile(unsigned, "a", zipfile.ZIP_DEFLATED) as apk:
        for dex in (build / "dex").glob("*.dex"):
            apk.write(dex, dex.name)
        for (abi, name), file in native_libraries.items():
            apk.write(file, "lib/" + abi + "/" + name)
        for file in notices.iterdir():
            apk.write(file, "assets/notices/" + file.name)
    aligned = build / "aligned.apk"
    run(tools / "zipalign", "-f", "-P", "16", "4", unsigned, aligned)
    keystore = build / "debug.keystore"
    if not keystore.exists():
        run("keytool", "-genkeypair", "-keystore", keystore, "-storepass", "android", "-keypass", "android",
            "-alias", "sibalsa-debug", "-keyalg", "RSA", "-keysize", "3072", "-validity", "10000",
            "-dname", "CN=SIbalsa development build", "-noprompt")
    apk_path = output / "SIbalsa-example.apk"
    run(tools / "apksigner", "sign", "--ks", keystore, "--ks-key-alias", "sibalsa-debug", "--ks-pass", "pass:android",
        "--key-pass", "pass:android", "--out", apk_path, aligned)
    run(tools / "apksigner", "verify", "--verbose", apk_path)
    print("Built:", aar_path, apk_path, sep="\n")


if __name__ == "__main__":
    main()

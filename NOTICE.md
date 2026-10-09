# Source and binary notices

SIbalsa's original Java, C++, Python, test and documentation source is released
under the Unlicense in LICENSE. No third-party inference, training, federation,
HTTP or UI implementation is vendored. Models and tokenizers supplied by an
integrator retain their own licenses and are not included in this repository.

The Android example and AAR are built using the official Android SDK/NDK.
Compiled C++ code uses the NDK's standard C++ library/compiler runtime. These
toolchain components are not relicensed by SIbalsa. The build embeds the NDK's
redistribution notices in `assets/notices/Android-NDK-NOTICE.txt` and
`assets/notices/Android-NDK-NOTICE.toolchain.txt`, alongside
SIbalsa's LICENSE. Android system libraries are provided by the device.

Java uses the Android platform API. The reference server and build/conversion
tools use Python's standard library. TLS and certificate verification are
supplied by these platforms; SIbalsa does not implement its own TLS or
certificate cryptography. Native model-file integrity checks use SIbalsa's
original SHA-256 implementation in native/src/sha256.cpp. That hash code is
released under the Unlicense and does not implement a TLS or signature system.

Build tools are not runtime dependencies of the library. Their licenses remain
with their publishers. A locally generated debug signing key is not a release
signing identity and is never committed.

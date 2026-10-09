// SPDX-License-Identifier: Unlicense
package ai.sibalsa.runtime;

/** Android packages one private C++ standard runtime; host tools retain separate shared libraries. */
final class NativeLibraries {
    private NativeLibraries() {}
    static void load(String hostLibrary) {
        boolean android = "Dalvik".equals(System.getProperty("java.vm.name"))
                || "Android Runtime".equals(System.getProperty("java.runtime.name"));
        System.loadLibrary(android ? "sibalsa_android" : hostLibrary);
    }
}

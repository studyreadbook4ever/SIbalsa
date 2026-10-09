// SPDX-License-Identifier: Unlicense
package ai.sibalsa.runtime;

/** JNI entry points for the inference runtime. Tokenization belongs to the integrator. */
public final class InferenceBridge {
    static { NativeLibraries.load("sibalsa_inference"); }
    private InferenceBridge() {}
    public static native long loadModel(String path);
    public static native int[] modelInfo(long model);
    public static native String modelSha256(long model);
    public static native long createSession(long model, float[] adapter, int rank, int maxContext);
    public static native float[] nextLogits(long session, int token);
    public static native void resetSession(long session);
    public static native void cancelSession(long session);
    public static native void closeSession(long session);
    public static native void closeModel(long model);
}

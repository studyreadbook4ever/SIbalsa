// SPDX-License-Identifier: Unlicense
package ai.sibalsa.runtime;

/** Separate trainer state for local optimization of generation-head LoRA parameters. */
public final class TrainingBridge {
    static { NativeLibraries.load("sibalsa_training"); }
    private TrainingBridge() {}
    public static native long createTrainer(String modelPath, int rank, long seed, int maxContext);
    public static native String modelSha256(long trainer);
    public static native float trainSft(long trainer, int[] sequence, int promptLength, float learningRate, float clip);
    public static native float trainDpo(long trainer, int[] prompt, int[] chosen, int[] rejected, float beta, float learningRate, float clip);
    public static native float[] adapterParameters(long trainer);
    public static native void setAdapterParameters(long trainer, float[] parameters);
    public static native void cancelTrainer(long trainer);
    public static native void closeTrainer(long trainer);
}

// SPDX-License-Identifier: Unlicense
package org.sibalsa.nativeengine;

import ai.sibalsa.runtime.InferenceBridge;
import java.util.Arrays;
import java.util.Random;
import org.sibalsa.core.Cancellation;
import org.sibalsa.core.InferenceRequest;
import org.sibalsa.core.InferenceRuntime;
import org.sibalsa.core.ModelSpec;
import org.sibalsa.core.TokenSink;
import org.sibalsa.core.Tokenizer;

/** mmap-backed CPU serving with a fresh bounded KV cache and immutable adapter per request. */
public final class NativeInferenceRuntime implements InferenceRuntime {
    private long modelHandle;
    private final ModelSpec spec;
    private final Tokenizer tokenizer;
    private final int rank, context, bos;
    private final int[] eos;
    private final String prefix, suffix;
    private float[] adapter;
    private long version = -1;
    private final Random random = new Random();

    public NativeInferenceRuntime(String path, ModelSpec spec, Tokenizer tokenizer, int rank, int context,
                                  int bos, int[] eos, String prefix, String suffix) {
        if (spec == null || tokenizer == null || rank < 1 || context < 2 || eos == null)
            throw new IllegalArgumentException("Invalid inference integration");
        long handle = InferenceBridge.loadModel(path);
        try {
            int[] info = InferenceBridge.modelInfo(handle);
            if (!spec.modelId.equals(InferenceBridge.modelSha256(handle)))
                throw new IllegalArgumentException("Loaded model SHA-256 differs from federation contract");
            if ((long) rank * (info[0] + (long) info[1]) != spec.dimension || context > info[2])
                throw new IllegalArgumentException("Model/adapter/context mismatch");
            if (bos >= info[0] || bos < -1) throw new IllegalArgumentException("Invalid BOS ID");
            for (int id : eos) if (id < 0 || id >= info[0]) throw new IllegalArgumentException("Invalid EOS ID");
            modelHandle = handle;
        } catch (RuntimeException | Error failure) { InferenceBridge.closeModel(handle); throw failure; }
        this.spec = spec; this.tokenizer = tokenizer; this.rank = rank; this.context = context;
        this.bos = bos; this.eos = eos.clone(); this.prefix = prefix == null ? "" : prefix; this.suffix = suffix == null ? "" : suffix;
    }
    @Override public ModelSpec model() { return spec; }
    @Override public synchronized void resetAdapter() { adapter = null; version = -1; }
    public int[] promptTokens(String prompt) {
        int[] tokens = tokenizer.encode(prefix + prompt + suffix);
        if (bos < 0) return tokens;
        int[] result = new int[tokens.length + 1];
        result[0] = bos; System.arraycopy(tokens, 0, result, 1, tokens.length); return result;
    }
    @Override public synchronized void applyAdapter(double[] values, long newVersion) {
        if (values == null || values.length != spec.dimension || newVersion < version)
            throw new IllegalArgumentException("Invalid adapter or rollback without explicit runtime replacement");
        adapter = floats(values); version = newVersion;
    }
    static float[] floats(double[] values) {
        float[] output = new float[values.length];
        for (int i = 0; i < values.length; i++) {
            output[i] = (float) values[i];
            if (!Float.isFinite(output[i])) throw new IllegalArgumentException("Adapter must fit finite float32");
        }
        return output;
    }
    @Override public synchronized void generate(InferenceRequest request, Cancellation cancellation, TokenSink sink) throws Exception {
        if (modelHandle == 0) throw new IllegalStateException("Inference runtime closed");
        int[] prompt = promptTokens(request.prompt);
        if (prompt.length == 0 || prompt.length >= context) throw new IllegalArgumentException("Prompt exceeds configured context; no silent truncation");
        int count = Math.min(request.maxTokens, context - prompt.length);
        long session = InferenceBridge.createSession(modelHandle, adapter, rank, context);
        try (AutoCloseable registration = cancellation.onCancel(() -> InferenceBridge.cancelSession(session))) {
            float[] logits = null;
            for (int token : prompt) { cancellation.check(); logits = InferenceBridge.nextLogits(session, token); }
            int[] generated = new int[count];
            String emitted = "";
            for (int i = 0; i < count; i++) {
                cancellation.check();
                int token = select(logits, request.temperature);
                boolean stop = false;
                for (int end : eos) if (token == end) stop = true;
                if (stop) break;
                generated[i] = token;
                String decoded = tokenizer.decode(Arrays.copyOf(generated, i + 1));
                // Wait for a complete UTF-8 suffix instead of emitting partial-byte replacement characters.
                if (!decoded.endsWith("\uFFFD") && decoded.startsWith(emitted)) {
                    String part = decoded.substring(emitted.length());
                    if (!part.isEmpty()) sink.onToken(part);
                    emitted = decoded;
                }
                if (i + 1 < count) logits = InferenceBridge.nextLogits(session, token);
            }
        } finally { InferenceBridge.closeSession(session); }
    }
    private int select(float[] logits, double temperature) {
        int best = 0;
        for (int i = 0; i < logits.length; i++) {
            if (!Float.isFinite(logits[i])) throw new IllegalStateException("Nonfinite model logits");
            if (logits[i] > logits[best]) best = i;
        }
        if (temperature == 0) return best;
        double sum = 0;
        double[] probabilities = new double[logits.length];
        for (int i = 0; i < logits.length; i++) { probabilities[i] = Math.exp((logits[i] - logits[best]) / temperature); sum += probabilities[i]; }
        double draw = random.nextDouble() * sum;
        for (int i = 0; i < probabilities.length; i++) { draw -= probabilities[i]; if (draw <= 0) return i; }
        return best;
    }
    @Override public synchronized void close() { if (modelHandle != 0) { InferenceBridge.closeModel(modelHandle); modelHandle = 0; } }
}

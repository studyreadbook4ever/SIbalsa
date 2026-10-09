// SPDX-License-Identifier: Unlicense
package org.sibalsa.nativeengine;

import ai.sibalsa.runtime.TrainingBridge;
import java.util.ArrayList;
import java.util.List;
import org.sibalsa.core.Cancellation;
import org.sibalsa.core.Feedback;
import org.sibalsa.core.ModelSpec;
import org.sibalsa.core.ProgressSink;
import org.sibalsa.core.Tokenizer;
import org.sibalsa.core.TrainingRequest;
import org.sibalsa.core.TrainingResult;
import org.sibalsa.core.TrainingRuntime;

/** Independent local SFT/DPO optimizer. Never modifies the active inference runtime. */
public final class NativeTrainingRuntime implements TrainingRuntime {
    private final String path, prefix, suffix;
    private final ModelSpec spec;
    private final Tokenizer tokenizer;
    private final int rank, context, bos, eos;
    private final long seed;
    private final float dpoBeta, gradientClip;
    public NativeTrainingRuntime(String path, ModelSpec spec, Tokenizer tokenizer, int rank, int context, int bos, int eos,
                                 String prefix, String suffix, long seed, float dpoBeta, float gradientClip) {
        if (path == null || spec == null || tokenizer == null || rank < 1 || context < 2 ||
                !Float.isFinite(dpoBeta) || dpoBeta <= 0 || dpoBeta > 10 || !Float.isFinite(gradientClip) || gradientClip <= 0)
            throw new IllegalArgumentException("Invalid training integration");
        this.path = path; this.spec = spec; this.tokenizer = tokenizer; this.rank = rank; this.context = context;
        this.bos = bos; this.eos = eos; this.prefix = prefix == null ? "" : prefix; this.suffix = suffix == null ? "" : suffix;
        this.seed = seed; this.dpoBeta = dpoBeta; this.gradientClip = gradientClip;
    }
    @Override public ModelSpec model() { return spec; }
    @Override public boolean accepts(Feedback item) { return item.kind == Feedback.Kind.CORRECTION || item.kind == Feedback.Kind.PREFERENCE; }
    private int[] prompt(String text) {
        int[] tokens = tokenizer.encode(prefix + text + suffix);
        if (bos < 0) return tokens;
        int[] output = new int[tokens.length + 1]; output[0] = bos;
        System.arraycopy(tokens, 0, output, 1, tokens.length); return output;
    }
    private int[] response(String text) {
        int[] tokens = tokenizer.encode(text);
        if (eos < 0) return tokens;
        int[] output = new int[tokens.length + 1];
        System.arraycopy(tokens, 0, output, 0, tokens.length); output[tokens.length] = eos; return output;
    }
    private int[] join(int[] left, int[] right) {
        if (left.length == 0 || right.length == 0 || left.length + right.length > context)
            throw new IllegalArgumentException("Training example exceeds context or lacks tokens; no silent truncation");
        int[] joined = new int[left.length + right.length];
        System.arraycopy(left, 0, joined, 0, left.length); System.arraycopy(right, 0, joined, left.length, right.length); return joined;
    }
    @Override public TrainingResult train(TrainingRequest request, Cancellation cancellation, ProgressSink progress) throws Exception {
        request.gate.requireEligible(cancellation);
        if (!spec.equals(request.model)) throw new IllegalArgumentException("Training model mismatch");
        List<Feedback> items = new ArrayList<>();
        for (Feedback item : request.feedback) if (accepts(item)) items.add(item);
        if (items.isEmpty()) throw new IllegalArgumentException("Built-in runtime needs corrections or same-context preference pairs");
        long trainer = TrainingBridge.createTrainer(path, rank, seed, context);
        try (AutoCloseable registration = cancellation.onCancel(() -> TrainingBridge.cancelTrainer(trainer))) {
            request.gate.requireEligible(cancellation);
            if (!spec.modelId.equals(TrainingBridge.modelSha256(trainer)))
                throw new IllegalArgumentException("Loaded training model SHA-256 differs from federation contract");
            double[] reference = request.baseAdapter();
            TrainingBridge.setAdapterParameters(trainer, NativeInferenceRuntime.floats(reference));
            int completed = 0;
            for (; completed < request.requestedSteps; completed++) {
                request.gate.requireEligible(cancellation);
                Feedback item = items.get(completed % items.size());
                int[] input = prompt(item.prompt), chosen = response(item.chosen);
                if (item.kind == Feedback.Kind.CORRECTION) {
                    TrainingBridge.trainSft(trainer, join(input, chosen), input.length, (float) request.learningRate, gradientClip);
                } else {
                    int[] rejected = response(item.rejected);
                    join(input, chosen); join(input, rejected);
                    TrainingBridge.trainDpo(trainer, input, chosen, rejected, dpoBeta, (float) request.learningRate, gradientClip);
                }
                request.gate.requireEligible(cancellation);
                if (progress != null) progress.onStep(completed + 1, request.requestedSteps);
            }
            request.gate.requireEligible(cancellation);
            float[] trained = TrainingBridge.adapterParameters(trainer);
            if (trained.length != reference.length) throw new IllegalStateException("Training adapter mismatch");
            double[] delta = new double[trained.length];
            for (int i = 0; i < delta.length; i++) delta[i] = trained[i] - reference[i];
            int samples = Math.min(items.size(), completed);
            long[] contributors = new long[samples];
            for (int i = 0; i < samples; i++) contributors[i] = items.get(i).localId;
            return new TrainingResult(request.limits.clipped(delta, spec.dimension), samples, completed, contributors);
        } finally { TrainingBridge.closeTrainer(trainer); }
    }
}

// SPDX-License-Identifier: Unlicense
import ai.sibalsa.runtime.InferenceBridge;
import ai.sibalsa.runtime.TrainingBridge;
import java.util.Arrays;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;

/** Host JVM check of the identical JNI bridges shipped to Android. Fixture weights are generated. */
public final class JniSmoke {
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    private static void rejects(Runnable action) {
        try { action.run(); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("invalid native handle/input accepted");
    }
    public static void main(String[] args) throws Exception {
        long model = InferenceBridge.loadModel(args[0]);
        byte[] bytes = Files.readAllBytes(Path.of(args[0]));
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder expectedHash = new StringBuilder();
        for (byte value : digest) expectedHash.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        check(InferenceBridge.modelSha256(model).equals(expectedHash.toString()), "inference hash binds loaded artifact");
        int[] config = InferenceBridge.modelInfo(model);
        check(config[0] == 258 && config[1] == 8, "byte fixture contract");
        long trainer = TrainingBridge.createTrainer(args[0], 2, 42, 64);
        check(TrainingBridge.modelSha256(trainer).equals(expectedHash.toString()), "training hash binds loaded artifact");
        float[] initial = TrainingBridge.adapterParameters(trainer);
        check(initial.length == (258 + 8) * 2, "flat adapter layout");
        long session = InferenceBridge.createSession(model, initial, 2, 64);
        check(InferenceBridge.nextLogits(session, 256).length == 258, "real JNI forward logits");
        rejects(() -> InferenceBridge.nextLogits(session, -1));
        rejects(() -> InferenceBridge.modelInfo(session));
        rejects(() -> TrainingBridge.adapterParameters(model));
        rejects(() -> InferenceBridge.modelSha256(session));
        rejects(() -> TrainingBridge.modelSha256(model));
        InferenceBridge.closeModel(model);
        check(InferenceBridge.nextLogits(session, 97).length == 258, "session owns its mapped model");
        rejects(() -> InferenceBridge.modelInfo(model));
        rejects(() -> InferenceBridge.modelSha256(model));
        int[] sequence = {256, 97, 98};
        float first = TrainingBridge.trainSft(trainer, sequence, 2, 0.1f, 2.0f);
        float last = first;
        for (int i = 0; i < 180; i++) last = TrainingBridge.trainSft(trainer, sequence, 2, 0.1f, 2.0f);
        check(last < first * 0.2f, "JNI SFT loss drops");
        float[] changed = TrainingBridge.adapterParameters(trainer);
        check(!Arrays.equals(initial, changed), "JNI adapter changes");
        float[] invalid = changed.clone(); invalid[0] = Float.NaN;
        rejects(() -> TrainingBridge.setAdapterParameters(trainer, invalid));
        check(Arrays.equals(changed, TrainingBridge.adapterParameters(trainer)), "bad set leaves adapter unchanged");
        TrainingBridge.setAdapterParameters(trainer, changed);
        float initialDpo = TrainingBridge.trainDpo(trainer, new int[]{256,97}, new int[]{98}, new int[]{99}, 0.5f, 0.1f, 2.0f);
        check(Math.abs(initialDpo - Math.log(2)) < 1e-6, "DPO reference reset to global base");
        changed = TrainingBridge.adapterParameters(trainer);
        TrainingBridge.cancelTrainer(trainer);
        try {
            TrainingBridge.trainSft(trainer, sequence, 2, 0.1f, 2.0f);
            throw new AssertionError("cancelled JNI training accepted");
        } catch (Throwable expected) {
            check(!(expected instanceof AssertionError), "cancellation must throw native IOException");
        }
        check(Arrays.equals(changed, TrainingBridge.adapterParameters(trainer)), "cancellation discards update");
        InferenceBridge.closeSession(session);
        TrainingBridge.closeTrainer(trainer);
        rejects(() -> InferenceBridge.nextLogits(session, 1));
        rejects(() -> TrainingBridge.adapterParameters(trainer));
        rejects(() -> TrainingBridge.modelSha256(trainer));
        System.out.println("PASS host JVM JNI model hashes, forward, SFT, DPO reference, cancellation, bounds and stale handles");
    }
}

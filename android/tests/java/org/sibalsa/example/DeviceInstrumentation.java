// SPDX-License-Identifier: Unlicense
package org.sibalsa.example;

import android.app.Instrumentation;
import android.content.Context;
import android.os.Bundle;
import org.json.JSONArray;
import org.json.JSONObject;
import org.sibalsa.android.SiBalsa;
import org.sibalsa.android.StorageDeviceTests;
import org.sibalsa.core.*;
import org.sibalsa.nativeengine.*;
import ai.sibalsa.runtime.InferenceBridge;
import ai.sibalsa.runtime.TrainingBridge;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Platform-only instrumentation. Generated random fixture assets never enter the public APK. */
public final class DeviceInstrumentation extends Instrumentation {
    private Bundle arguments;
    private int checks;
    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); this.arguments = arguments; start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            String mode = arguments == null ? "smoke" : arguments.getString("mode", "smoke");
            if ("bootstrap".equals(mode)) bootstrap();
            else if ("gate".equals(mode)) {
                boolean actual = StorageDeviceTests.inspectGate(getTargetContext());
                boolean expected = Boolean.parseBoolean(arguments.getString("expected", "false"));
                require(actual == expected, "Physical gate eligibility " + actual + " expected " + expected);
            } else if ("smoke".equals(mode)) smoke();
            else throw new IllegalArgumentException("Unknown test mode");
            result.putString("stream", "SIbalsa device " + mode + ": " + checks + " checks passed\n");
            finish(-1, result);
        } catch (Throwable error) {
            java.io.StringWriter trace = new java.io.StringWriter();
            error.printStackTrace(new java.io.PrintWriter(trace));
            result.putString("stream", trace.toString());
            finish(0, result);
        }
    }
    private File fixtureDirectory() throws Exception {
        File directory = new File(getTargetContext().getFilesDir(), "generated-device-fixture");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Cannot create test fixture directory");
        for (String name : new String[]{"model.sbm", "integration.json", "adapter.json"}) copyAsset(name, new File(directory, name));
        return directory;
    }
    private void copyAsset(String name, File output) throws Exception {
        try (InputStream input = getContext().getAssets().open(name); FileOutputStream out = new FileOutputStream(output)) {
            byte[] buffer = new byte[4096]; int n;
            while ((n = input.read(buffer)) != -1) out.write(buffer, 0, n);
        }
    }
    private double[] baseAdapter(File directory) throws Exception {
        JSONArray array = new JSONArray(ModelBundle.read(new File(directory, "adapter.json"), 1000000));
        double[] values = new double[array.length()];
        for (int i = 0; i < values.length; i++) values[i] = array.getDouble(i);
        return values;
    }
    private void smoke() throws Exception {
        Context context = getTargetContext();
        SIbalsaApplication app = (SIbalsaApplication) context.getApplicationContext();
        Future<?> reset = app.worker.submit(() -> {
            if (app.sdk != null) { app.sdk.close(); app.sdk = null; }
            app.settings().edit().clear().commit();
            return null;
        }); reset.get(30, TimeUnit.SECONDS);
        File directory = fixtureDirectory();
        ModelBundle bundle = new ModelBundle(directory);
        checks += StorageDeviceTests.run(context, bundle.spec);
        checks += StorageDeviceTests.schedulerAndConsent(context, bundle.spec);
        checks += StorageDeviceTests.foregroundGate(context);
        bpe();
        nativeRuntime(bundle, baseAdapter(directory));
    }
    private void bpe() throws Exception {
        JSONObject vocab = new JSONObject();
        int extra = 0;
        for (int value = 0; value < 256; value++) {
            boolean visible = value >= 33 && value <= 126 || value >= 161 && value <= 172 || value >= 174;
            int symbol = visible ? value : 256 + extra++;
            vocab.put(new String(Character.toChars(symbol)), value);
        }
        vocab.put("hi", 256);
        JSONObject model = new JSONObject().put("type", "BPE").put("vocab", vocab)
                .put("merges", new JSONArray().put(new JSONArray().put("h").put("i")));
        JSONObject tokenizer = new JSONObject().put("model", model).put("normalizer", JSONObject.NULL)
                .put("pre_tokenizer", new JSONObject().put("type", "ByteLevel").put("add_prefix_space", false).put("use_regex", true));
        ByteBpeTokenizer bpe = new ByteBpeTokenizer(tokenizer);
        require(Arrays.equals(bpe.encode("hi"), new int[]{256}), "Known BPE merge emits known ID");
        require(Arrays.equals(bpe.encode("한글"), new int[]{237,149,156,234,184,128}), "Korean UTF-8 byte IDs");
        require("hi 한글!".equals(bpe.decode(bpe.encode("hi 한글!"))), "Android JSONObject BPE roundtrip");
        require(bpe.encode("").length == 0, "Empty ordinary input terminates");
        tokenizer.put("added_tokens", new JSONArray().put(new JSONObject().put("id", 257).put("content", "").put("special", true)));
        rejects(() -> new ByteBpeTokenizer(tokenizer), "Empty added token rejected before encode can loop");
        tokenizer.put("added_tokens", new JSONArray().put(new JSONObject().put("id", 104).put("content", "<oops>").put("special", true)));
        rejects(() -> new ByteBpeTokenizer(tokenizer), "Added-token ID collision rejected");
        tokenizer.put("added_tokens", new JSONArray().put(new JSONObject().put("id", -1).put("content", "<oops>").put("special", true)));
        rejects(() -> new ByteBpeTokenizer(tokenizer), "Negative added-token ID rejected");
        tokenizer.remove("added_tokens");
        JSONObject split = new JSONObject().put("type", "Split").put("behavior", "Isolated").put("invert", false)
                .put("pattern", new JSONObject().put("Regex", "(?s).+"));
        JSONObject byteLevel = new JSONObject().put("type", "ByteLevel").put("use_regex", false).put("add_prefix_space", false);
        tokenizer.put("pre_tokenizer", new JSONObject().put("type", "Sequence")
                .put("pretokenizers", new JSONArray().put(split).put(byteLevel)));
        ByteBpeTokenizer sequence = new ByteBpeTokenizer(tokenizer);
        require(Arrays.equals(sequence.encode("hi"), new int[]{256}), "Exact Split then ByteLevel sequence supported");
        tokenizer.put("pre_tokenizer", new JSONObject().put("type", "Sequence")
                .put("pretokenizers", new JSONArray().put(byteLevel).put(split)));
        rejects(() -> new ByteBpeTokenizer(tokenizer), "Reversed tokenizer stages rejected");
        tokenizer.put("pre_tokenizer", new JSONObject().put("type", "Sequence")
                .put("pretokenizers", new JSONArray().put(byteLevel).put(byteLevel)));
        rejects(() -> new ByteBpeTokenizer(tokenizer), "Duplicate ByteLevel stages rejected");
        byteLevel.put("use_regex", true);
        tokenizer.put("pre_tokenizer", new JSONObject().put("type", "Sequence")
                .put("pretokenizers", new JSONArray().put(split).put(byteLevel)));
        rejects(() -> new ByteBpeTokenizer(tokenizer), "Split followed by another regex stage rejected");
        tokenizer.put("pre_tokenizer", byteLevel);
        tokenizer.put("normalizer", new JSONObject().put("type", "NFC"));
        rejects(() -> new ByteBpeTokenizer(tokenizer), "Unsupported normalizer rejected");
    }
    private Feedback feedback(ModelSpec model, long id, String chosen) {
        return new Feedback(id, model, "1+1?", "3", Feedback.Kind.CORRECTION, -1, chosen, "", "test", null);
    }
    private void nativeRuntime(ModelBundle bundle, double[] initial) throws Exception {
        String path = bundle.model.getPath();
        ModelSpec wrongHash = new ModelSpec("0000000000000000000000000000000000000000000000000000000000000000",
                bundle.spec.adapterSpec, bundle.spec.dimension);
        rejects(() -> new NativeInferenceRuntime(path, wrongHash, bundle.tokenizer, bundle.rank, bundle.context,
                bundle.bos, bundle.eos, "", ""), "Inference rejects actual file/model-contract SHA mismatch");
        long model = InferenceBridge.loadModel(path);
        long session = InferenceBridge.createSession(model, null, bundle.rank, bundle.context);
        try {
            require(InferenceBridge.modelInfo(model)[0] == 258, "Actual Android JNI model metadata");
            float[] logits = InferenceBridge.nextLogits(session, 49);
            require(logits.length == 258 && Float.isFinite(logits[0]), "Actual mmap JNI forward pass");
            rejects(() -> InferenceBridge.nextLogits(model, 49), "Cross-type native handle rejected");
            InferenceBridge.cancelSession(session);
            rejects(() -> InferenceBridge.nextLogits(session, 49), "Native inference cancellation");
        } finally { InferenceBridge.closeSession(session); InferenceBridge.closeModel(model); }
        long trainer = TrainingBridge.createTrainer(path, 2, 42, bundle.context);
        try {
            float first = TrainingBridge.trainSft(trainer, new int[]{49,43,49,63,50,257}, 4, 0.01f, 1f);
            float last = first;
            for (int i = 0; i < 12; i++) last = TrainingBridge.trainSft(trainer, new int[]{49,43,49,63,50,257}, 4, 0.01f, 1f);
            require(Float.isFinite(first) && last < first, "Real on-device SFT reduces fixture loss");
            rejects(() -> TrainingBridge.adapterParameters(model), "Stale/wrong native trainer handle rejected");
            TrainingBridge.cancelTrainer(trainer);
            rejects(() -> TrainingBridge.trainSft(trainer, new int[]{49,50}, 1, 0.01f, 1f), "Native training cancellation");
        } finally { TrainingBridge.closeTrainer(trainer); }
        NativeTrainingRuntime runtime = new NativeTrainingRuntime(path, bundle.spec, bundle.tokenizer, bundle.rank,
                bundle.context, bundle.bos, bundle.eos[0], "", "", 42, 0.1f, 1f);
        AtomicBoolean allowed = new AtomicBoolean(true);
        ExecutionGate unitGate = new ExecutionGate() {
            public boolean eligible() { return allowed.get(); }
            public void requireEligible(Cancellation c) throws IOException {
                c.check(); if (!eligible()) { c.cancel(); throw new IOException("Test gate revoked"); }
            }
        };
        Feedback first = feedback(bundle.spec, 1, "2"), second = feedback(bundle.spec, 2, "two");
        ServerLimits limits = new ServerLimits(2, 2, 1, 1);
        NativeTrainingRuntime wrongTraining = new NativeTrainingRuntime(path, wrongHash, bundle.tokenizer, bundle.rank,
                bundle.context, bundle.bos, bundle.eos[0], "", "", 42, 0.1f, 1f);
        TrainingRequest wrongRequest = new TrainingRequest(wrongHash, initial, Collections.singletonList(feedback(wrongHash, 1, "2")),
                limits, 1, 0.01, unitGate);
        rejects(() -> wrongTraining.train(wrongRequest, new Cancellation(), null), "Training rejects actual file/model-contract SHA mismatch");
        TrainingRequest request = new TrainingRequest(bundle.spec, initial, Arrays.asList(first, second), limits, 1, 0.01, unitGate);
        TrainingResult result = runtime.train(request, new Cancellation(), null);
        require(Arrays.equals(result.validateContributors(request), new long[]{1}), "Native partial step tracks exact contributor IDs");
        require(Arrays.stream(result.delta()).anyMatch(v -> v != 0), "Native learner produces a real parameter delta");
        TrainingRequest twoSteps = new TrainingRequest(bundle.spec, initial, Arrays.asList(first, second), limits, 2, 0.01, unitGate);
        rejects(() -> runtime.train(twoSteps, new Cancellation(), (done, total) -> allowed.set(false)), "Mid-minibatch gate revocation stops training");
        NativeInferenceRuntime serving = new NativeInferenceRuntime(path, bundle.spec, bundle.tokenizer, bundle.rank,
                bundle.context, bundle.bos, bundle.eos, "", "");
        try {
            serving.applyAdapter(initial, 7);
            serving.resetAdapter();
            serving.applyAdapter(initial, 0);
            require(true, "Native explicit reset removes prior version guard");
        } finally { serving.close(); runtime.close(); }
    }
    private void bootstrap() throws Exception {
        SIbalsaApplication app = (SIbalsaApplication) getTargetContext().getApplicationContext();
        File directory = fixtureDirectory();
        String server = arguments.getString("server", "https://10.0.2.2:8443");
        String caAsset = arguments.getString("ca_asset", "development-ca.crt");
        copyAsset(caAsset, new File(app.getFilesDir(), "development-ca.crt"));
        Future<?> task = app.worker.submit(() -> {
            if (app.sdk != null) { app.sdk.clearLocalData(); app.sdk.close(); app.sdk = null; }
            app.settings().edit().putString("server", server).putInt("minFeedback", 2).putInt("maxSamples", 2)
                    .putInt("maxSteps", 2).putString("learningRate", "0.01").putBoolean("participation", false)
                    .putBoolean("unmetered", false).commit();
            app.configure(directory);
            SiBalsa sdk = app.siBalsa();
            sdk.clearLocalData();
            sdk.recordFeedback(feedback(sdk.model(), 0, "2"));
            sdk.recordFeedback(new Feedback(0, sdk.model(), "2+2?", "5", Feedback.Kind.CORRECTION, -1, "4", "", "private", null));
            sdk.setParticipationEnabled(true);
            app.settings().edit().putBoolean("participation", true).commit();
            return null;
        }); task.get(30, TimeUnit.SECONDS);
        require(app.siBalsa() != null && app.siBalsa().participationEnabled(), "Fixture app bootstrapped with opt-in and two corrections");
        Bundle status = new Bundle();
        status.putString("stream", "Bootstrap model_id=" + app.bundle.spec.modelId + " dimension=" + app.bundle.spec.dimension +
                " adapter_spec=" + app.bundle.spec.adapterSpec + "\n");
        sendStatus(0, status);
    }
    private interface Throwing { void run() throws Exception; }
    private void rejects(Throwing work, String message) throws Exception {
        try { work.run(); throw new AssertionError(message); }
        catch (Exception expected) { checks++; }
    }
    private void require(boolean value, String message) { if (!value) throw new AssertionError(message); checks++; }
}

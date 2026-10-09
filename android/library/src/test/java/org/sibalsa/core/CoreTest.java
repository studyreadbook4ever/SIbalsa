// SPDX-License-Identifier: Unlicense
package org.sibalsa.core;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;

/** Dependency-free contract tests. Run via android/library/test-core.sh. */
public final class CoreTest {
    private static int checks;
    private static final ModelSpec MODEL = new ModelSpec("0123456789abcdef".repeat(4), "adapter-v1", 2);
    private static final ExecutionGate OPEN = new ExecutionGate() {
        public boolean eligible() { return true; }
        public void requireEligible(Cancellation cancellation) throws IOException { cancellation.check(); }
    };
    public static void main(String[] args) throws Exception {
        gates(); configuration(); cancellation(); feedback(); limits(); contributors(); wire(); proof();
        System.out.println("SIbalsa SDK core: " + checks + " checks passed");
    }
    private static void gates() {
        for (int bits = 0; bits < 128; bits++) {
            boolean known = bit(bits, 0), idle = bit(bits, 1), interactive = bit(bits, 2), charging = bit(bits, 3),
                    wifi = bit(bits, 4), validated = bit(bits, 5), unmetered = bit(bits, 6);
            DeviceState state = new DeviceState(known, idle, interactive, charging, wifi, validated, unmetered);
            check(state.eligible(false) == (known && idle && !interactive && charging && wifi && validated), "Mandatory gate");
            check(state.eligible(true) == (known && idle && !interactive && charging && wifi && validated && unmetered), "Metered gate");
        }
    }
    private static boolean bit(int bits, int at) { return (bits & (1 << at)) != 0; }
    private static void configuration() {
        SiBalsaConfig disabled = SiBalsaConfig.builder().build();
        check(!disabled.federationEnabled && disabled.server == null, "Offline default");
        check(disabled.requireUnmeteredWifi, "Unmetered default");
        SiBalsaConfig enabled = SiBalsaConfig.builder().server("https://localhost:8443/").federationEnabled(true)
                .minFeedback(1).maxSamples(2).requireUnmeteredWifi(false).build();
        check(enabled.federationEnabled && !enabled.requireUnmeteredWifi, "Custom flags");
        rejects(() -> SiBalsaConfig.builder().server("http://localhost").build());
        rejects(() -> SiBalsaConfig.builder().server("https://user:pass@localhost").build());
        rejects(() -> SiBalsaConfig.builder().server("https://localhost/foo").build());
        rejects(() -> SiBalsaConfig.builder().server("https://localhost?token=secret").build());
        rejects(() -> SiBalsaConfig.builder().server("https://localhost#fragment").build());
        rejects(() -> SiBalsaConfig.builder().server("https://localhost:0").build());
        rejects(() -> SiBalsaConfig.builder().federationEnabled(true).build());
        rejects(() -> SiBalsaConfig.builder().minFeedback(0).build());
        rejects(() -> SiBalsaConfig.builder().learningRate(Double.NaN).build());
        rejects(() -> SiBalsaConfig.builder().readTimeoutMs(0).build());
        rejects(() -> SiBalsaConfig.builder().spkiPin("sha256/bad").build());
        rejects(() -> SiBalsaConfig.builder().minFeedback(33).maxSamples(32).build());
        rejects(() -> new ModelSpec("pretend-model", "adapter", 2));
        rejects(() -> new ModelSpec(MODEL.modelId, "unsafe\"json", 2));
        rejects(() -> new ModelSpec(MODEL.modelId, "adapter", 0));
        byte[] ca = new byte[100]; ca[0] = 9;
        SiBalsaConfig pinned = SiBalsaConfig.builder().developmentCa(ca).build();
        ca[0] = 1;
        check(pinned.developmentCa()[0] == 9, "Immutable CA input");
        byte[] exported = pinned.developmentCa(); exported[0] = 2;
        check(pinned.developmentCa()[0] == 9, "Immutable CA output");
    }
    private static void cancellation() throws Exception {
        Cancellation token = new Cancellation();
        AtomicInteger called = new AtomicInteger();
        AutoCloseable removed = token.onCancel(() -> called.addAndGet(10)); removed.close();
        token.onCancel(called::incrementAndGet);
        token.onCancel(() -> { throw new IllegalStateException("Listener failure"); });
        token.onCancel(called::incrementAndGet);
        token.cancel(); token.cancel();
        check(called.get() == 2, "Cancellation idempotent; listener failure cannot prevent cleanup");
        token.onCancel(called::incrementAndGet);
        check(called.get() == 3, "Late listener closes immediately");
        check(token.isCancelled(), "Cancellation visible");
        try { token.check(); throw new AssertionError("Missing cancellation exception"); }
        catch (java.io.InterruptedIOException expected) { checks++; }
    }
    private static Feedback correction() {
        return new Feedback(1, MODEL, "PRIVATE QUESTION", "PRIVATE ANSWER", Feedback.Kind.CORRECTION, -1,
                "PRIVATE CORRECTION", "", "PRIVATE NOTE", Collections.singletonList(Feedback.Reason.FACTUAL_ERROR));
    }
    private static void feedback() {
        Feedback corrected = correction();
        check(corrected.chosen.equals("PRIVATE CORRECTION"), "Correction preserved locally");
        rejects(() -> new Feedback(1, MODEL, "q", "a", Feedback.Kind.CORRECTION, 0, "", "", "", null));
        rejects(() -> new Feedback(1, MODEL, "q", "a", Feedback.Kind.PREFERENCE, 0, "same", "same", "", null));
        rejects(() -> new Feedback(1, MODEL, "q", "a", Feedback.Kind.RATING, 0, "", "", "", null));
        rejects(() -> new Feedback(1, MODEL, "q", "a", Feedback.Kind.RATING, 2, "", "", "", null));
        rejects(() -> new Feedback(1, MODEL, "", "a", Feedback.Kind.RATING, 1, "", "", "", null));
        new Feedback(0, MODEL, "q", "a", Feedback.Kind.RATING, -1, null, null, null, null);
        checks++;
        rejects(() -> corrected.reasons.add(Feedback.Reason.OTHER));
    }
    private static void limits() {
        ServerLimits limits = new ServerLimits(2, 3, 1, 0.75);
        SiBalsaConfig config = SiBalsaConfig.builder().minFeedback(1).maxSamples(99).maxSteps(99).build();
        check(limits.effectiveSamples(config) == 2 && limits.effectiveSteps(config) == 3, "Local flags cannot loosen server bounds");
        double[] clipped = limits.clipped(new double[]{100, -100}, 2);
        check(Math.hypot(clipped[0], clipped[1]) <= 1 && Math.abs(clipped[0]) <= 0.75, "Coordinate and L2 clipping");
        check(clipped[0] > 0 && clipped[1] < 0, "Clipping preserves directions");
        double[] extreme = limits.clipped(new double[]{Double.MAX_VALUE, -Double.MAX_VALUE}, 2);
        check(Double.isFinite(extreme[0]) && Math.hypot(extreme[0], extreme[1]) <= 1, "Overflow-safe norm");
        rejects(() -> limits.clipped(new double[]{Double.NaN, 0}, 2));
        rejects(() -> limits.clipped(new double[]{Double.POSITIVE_INFINITY, 0}, 2));
        rejects(() -> limits.clipped(new double[]{0}, 2));
        rejects(() -> new ServerLimits(0, 1, 1, 1));
        rejects(() -> new ServerLimits(1, 1, Double.POSITIVE_INFINITY, 1));
        double[] source = {1, 2};
        AdapterSnapshot snapshot = new AdapterSnapshot(MODEL, 2, source); source[0] = 99;
        check(snapshot.adapter()[0] == 1, "Adapter input immutable");
        double[] output = snapshot.adapter(); output[0] = 99;
        check(snapshot.adapter()[0] == 1, "Adapter output immutable");
        TrainingRequest request = new TrainingRequest(MODEL, new double[]{0, 0}, Arrays.asList(correction()), limits, 3, 0.01, OPEN);
        check(request.feedback.size() == 1, "Training request");
        rejects(() -> new TrainingRequest(MODEL, new double[]{0, 0}, Arrays.asList(correction()), limits, 4, 0.01, OPEN));
        rejects(() -> new TrainingRequest(MODEL, new double[]{0}, Arrays.asList(correction()), limits, 3, 0.01, OPEN));
    }
    private static String snapshotFields() {
        return "\"model_id\":\"" + MODEL.modelId + "\",\"adapter_spec\":\"adapter-v1\",\"base_version\":0,\"dimension\":2,\"adapter\":[0,0]";
    }
    private static void contributors() {
        Feedback first = correction();
        Feedback second = new Feedback(2, MODEL, "q", "a", Feedback.Kind.CORRECTION, -1, "better", "", "", null);
        TrainingRequest request = new TrainingRequest(MODEL, new double[]{0, 0}, Arrays.asList(first, second),
                new ServerLimits(2, 3, 1, 1), 2, 0.01, OPEN);
        TrainingResult subset = new TrainingResult(new double[]{0.1, 0.2}, 1, 1, new long[]{2});
        check(Arrays.equals(subset.validateContributors(request), new long[]{2}), "Mark only contributing subset consumed");
        long[] ids = subset.contributingFeedbackIds(); ids[0] = 1;
        check(subset.contributingFeedbackIds()[0] == 2, "Immutable contributor IDs");
        TrainingResult all = new TrainingResult(new double[]{0, 0}, 2, 2);
        check(Arrays.equals(all.validateContributors(request), new long[]{1, 2}), "Whole request consumption");
        rejects(() -> new TrainingResult(new double[]{0, 0}, 2, 1, new long[]{1, 1}));
        rejects(() -> new TrainingResult(new double[]{0, 0}, 2, 1, new long[]{1}));
        rejects(() -> new TrainingResult(new double[]{0, 0}, 1, 1, new long[]{0}));
        rejects(() -> new TrainingResult(new double[]{0, 0}, 1, 1, new long[]{3}).validateContributors(request));
        rejects(() -> new TrainingResult(new double[]{0, 0}, 1, 1).validateContributors(request));
        rejects(() -> new TrainingResult(new double[]{0, 0}, 2, 3).validateContributors(request));
        rejects(() -> new TrainingResult(new double[]{0}, 2, 1).validateContributors(request));
    }
    private static String limitFields() { return "{\"max_samples\":2,\"max_steps\":3,\"max_l2\":1,\"max_coordinate\":0.75}"; }
    private static String policyJson() {
        return "{\"protocol_version\":1," + snapshotFields() + ",\"limits\":" + limitFields() + ",\"pow_bits\":0,\"lease_seconds\":600}";
    }
    private static void wire() {
        ServerPolicy p = WireCodec.decodePolicy(policyJson());
        check(p.snapshot.model.equals(MODEL) && p.limits.maxSteps == 3, "Decode policy");
        Lease l = WireCodec.decodeLease("{\"lease_id\":\"abcdefghijkl\",\"nonce\":\"123456789abc\",\"expires_at\":2000000000,\"base_version\":0,\"adapter\":[0,0],\"limits\":" + limitFields() + ",\"pow_bits\":0}", MODEL);
        String request = WireCodec.encodeUpdate(new UpdatePayload(l, "0", 1, 2, new double[]{0.1, -0.1}));
        check(request.equals("{\"lease_id\":\"abcdefghijkl\",\"nonce\":\"123456789abc\",\"proof\":\"0\",\"sample_count\":1,\"steps\":2,\"delta\":[0.1,-0.1]}"), "Exact wire schema");
        for (String secret : new String[]{"PRIVATE", "prompt", "response", "note", "chosen", "localId", "device", "account"})
            check(!request.contains(secret), "No local data/identity in upload: " + secret);
        check(WireCodec.encodeLease(p.snapshot).contains("\"dimension\":2"), "Lease model shape");
        check(WireCodec.decodeAdapter("{" + snapshotFields() + "}").model.equals(MODEL), "Decode adapter");
        check(WireCodec.decodeReceipt("{\"accepted\":true,\"base_version\":0,\"round_size\":1,\"candidate_version\":null}").candidateVersion == null, "Null candidate");
        check(WireCodec.decodeReceipt("{\"accepted\":true,\"base_version\":0,\"round_size\":1,\"candidate_version\":2}").candidateVersion == 2, "Candidate version");
        rejects(() -> WireCodec.decodePolicy(policyJson().replace("\"protocol_version\":1", "\"protocol_version\":1,\"protocol_version\":1")));
        rejects(() -> WireCodec.decodePolicy(policyJson().replace("\"protocol_version\":1", "\"protocol_version\":1.0")));
        rejects(() -> WireCodec.decodePolicy(policyJson().replace("\"protocol_version\":1", "\"protocol_version\":true")));
        rejects(() -> WireCodec.decodePolicy(policyJson().replace("\"protocol_version\":1", "\"protocol_version\":2")));
        rejects(() -> WireCodec.decodePolicy(policyJson().replace("[0,0]", "[1e999,0]")));
        rejects(() -> WireCodec.decodePolicy(policyJson().replace("[0,0]", "[NaN,0]")));
        rejects(() -> WireCodec.decodePolicy(policyJson().replace("[0,0]", "[01,0]")));
        rejects(() -> WireCodec.decodePolicy(policyJson().replace("[0,0]", "[0,]")));
        rejects(() -> WireCodec.decodePolicy(policyJson() + " trailing"));
        rejects(() -> WireCodec.decodePolicy(policyJson().replace("\"dimension\":2", "\"dimension\":999999999999999999999999")));
        rejects(() -> WireCodec.decodeReceipt("{\"accepted\":false,\"base_version\":0,\"round_size\":1,\"candidate_version\":null}"));
        rejects(() -> new UpdatePayload(l, "0", 3, 2, new double[]{0, 0}));
        rejects(() -> new UpdatePayload(l, "0", 1, 4, new double[]{0, 0}));
        rejects(() -> new UpdatePayload(l, "0\"bad", 1, 2, new double[]{0, 0}));
    }
    private static void proof() throws Exception {
        check(ProofOfWork.hasLeadingZeros(new byte[]{0, 0}, 16), "Whole byte proof");
        check(ProofOfWork.hasLeadingZeros(new byte[]{0, 15}, 12), "Partial byte proof");
        check(!ProofOfWork.hasLeadingZeros(new byte[]{0, 16}, 12), "Partial byte rejection");
        check(!ProofOfWork.hasLeadingZeros(new byte[]{0}, 9), "Proof dimension bound");
        Lease l = new Lease("abcdefgh", "abcdefgh", 2000000000L,
                new AdapterSnapshot(MODEL, 0, new double[]{0, 0}), new ServerLimits(1, 1, 1, 1), 0);
        check(ProofOfWork.solve(l, OPEN, new Cancellation()).equals("0"), "Zero-work proof");
        Cancellation cancelled = new Cancellation(); cancelled.cancel();
        try { ProofOfWork.solve(l, OPEN, cancelled); throw new AssertionError("Proof should stop"); }
        catch (IOException expected) { checks++; }
    }
    private static void rejects(Runnable action) {
        try { action.run(); throw new AssertionError("Expected rejection"); }
        catch (IllegalArgumentException | UnsupportedOperationException expected) { checks++; }
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }
}

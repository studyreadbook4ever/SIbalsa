// SPDX-License-Identifier: Unlicense
package org.sibalsa.android;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.Context;
import android.os.PowerManager;
import org.sibalsa.core.*;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Run only against a disposable test installation: these tests erase its SIbalsa database. */
public final class StorageDeviceTests {
    private StorageDeviceTests() {}
    public static int run(Context context, ModelSpec model) throws Exception {
        int checks = 0;
        FeedbackStore store = new FeedbackStore(context);
        store.clearAll();
        try {
            Feedback a = correction(model, "first", "corrected first");
            Feedback b = correction(model, "second", "corrected second");
            long first = store.insert(a, 20), second = store.insert(b, 20);
            Feedback rating = new Feedback(0, model, "rating question", "rating answer", Feedback.Kind.RATING, -1,
                    "", "", "PRIVATE TEST NOTE", Collections.singletonList(Feedback.Reason.TOO_LONG));
            store.insert(rating, 20);
            List<Feedback> pending = store.pending(model, 20, f -> f.kind == Feedback.Kind.CORRECTION);
            require(pending.size() == 2 && pending.get(0).localId == first && pending.get(1).localId == second,
                    "SQLite filtering keeps unlearnable ratings local"); checks++;
            double[] values = new double[model.dimension]; values[0] = 0.001;
            Lease lease = new Lease("device_test_lease", "device_test_nonce", System.currentTimeMillis()/1000 + 600,
                    new AdapterSnapshot(model, 3, new double[model.dimension]), new ServerLimits(8, 8, 1, 1), 0);
            UpdatePayload payload = new UpdatePayload(lease, "0", 1, 1, values);
            store.saveQueue(new FeedbackStore.QueuedUpdate(payload, new long[]{second}, "https://127.0.0.1:8443"));
            String originalWire = WireCodec.encodeUpdate(payload);
            store.close(); store = new FeedbackStore(context);
            FeedbackStore.QueuedUpdate restored = store.queue();
            require(restored != null && originalWire.equals(WireCodec.encodeUpdate(restored.payload)),
                    "Pending queue reproduces exact wire payload after SQLite reopen"); checks++;
            require(Arrays.equals(restored.feedbackIds, new long[]{second}), "Queue stores exact local contributor subset"); checks++;
            require("https://127.0.0.1:8443".equals(restored.serverOrigin), "Queue persists operator origin"); checks++;
            store.accepted(restored);
            pending = store.pending(model, 20, f -> f.kind == Feedback.Kind.CORRECTION);
            require(pending.size() == 1 && pending.get(0).localId == first && store.queue() == null,
                    "Receipt marks only contributing feedback consumed atomically"); checks++;
            store.saveQueue(new FeedbackStore.QueuedUpdate(payload, new long[]{first}, "https://127.0.0.1:8443"));
            store.delete(first);
            require(store.queue() == null && store.pending(model, 20, f -> f.kind == Feedback.Kind.CORRECTION).isEmpty(),
                    "Local deletion abandons associated queued contributions"); checks++;
            store.saveAdapter(new AdapterSnapshot(model, 7, values));
            require(store.adapter(model).baseVersion == 7, "Adapter persists version"); checks++;
            try { store.saveAdapter(new AdapterSnapshot(model, 6, values)); throw new AssertionError("Rollback accepted"); }
            catch (IOException expected) { checks++; }
            store.clearAll();
            require(store.adapter(model) == null && store.queue() == null && store.pending(model, 20, f -> true).isEmpty(),
                    "Clear removes feedback, updates and adapters"); checks++;
            long retained = store.insert(correction(model, "retained", "local corrected text"), 20);
            android.content.ContentValues legacy = new android.content.ContentValues();
            legacy.put("singleton", 1); legacy.put("data", new byte[]{0,0,0,1,0,0,0,0});
            store.getWritableDatabase().insertOrThrow("pending_upload", null, legacy);
            require(store.queue() == null && store.pending(model, 20, f -> true).get(0).localId == retained,
                    "Legacy originless queue discarded while local feedback retained"); checks++;
            store.clearAll();
        } finally { store.close(); }
        return checks;
    }
    public static int schedulerAndConsent(Context context, ModelSpec model) throws Exception {
        final class Serving implements InferenceRuntime {
            long version; boolean reset;
            public ModelSpec model() { return model; }
            public void generate(InferenceRequest r, Cancellation c, TokenSink sink) throws Exception { c.check(); sink.onToken("offline"); }
            public void applyAdapter(double[] a, long v) {
                if (v < version) throw new IllegalArgumentException("rollback"); version = v;
            }
            public void resetAdapter() { version = 0; reset = true; }
        }
        Serving serving = new Serving();
        TrainingRuntime training = new TrainingRuntime() {
            public ModelSpec model() { return model; }
            public TrainingResult train(TrainingRequest r, Cancellation c, ProgressSink p) { throw new AssertionError("Foreground training"); }
        };
        Tokenizer tokenizer = new Tokenizer() {
            public int[] encode(String s) { return new int[]{1}; }
            public String decode(int[] ids) { return "test"; }
        };
        SiBalsaConfig config = SiBalsaConfig.builder().server("https://127.0.0.1:1").federationEnabled(true)
                .jobId(0x534954).minFeedback(1).maxSamples(2).maxSteps(2).build();
        SiBalsa sdk = new SiBalsa(context, config, serving, training, tokenizer);
        int checks = 0;
        try {
            sdk.setParticipationEnabled(false);
            require(!sdk.scheduleFederation(), "No federation without user opt-in"); checks++;
            final StringBuilder output = new StringBuilder();
            sdk.generate(new InferenceRequest("offline prompt", 2, 0), new Cancellation(), output::append);
            require("offline".equals(output.toString()), "Inference works without network/participation"); checks++;
            sdk.recordFeedback(correction(model, "sqlite", "corrected"));
            sdk.setParticipationEnabled(true);
            JobInfo job = context.getSystemService(JobScheduler.class).getPendingJob(config.jobId);
            require(job != null && job.isRequireCharging() && job.isRequireDeviceIdle(),
                    "Real Android JobInfo builds with mandatory charging and idle constraints"); checks++;
            require(job.getRequiredNetwork() != null && job.getRequiredNetwork().hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI),
                    "Scheduler requires Wi-Fi (API28+)"); checks++;
            sdk.setParticipationEnabled(false);
            require(context.getSystemService(JobScheduler.class).getPendingJob(config.jobId) == null,
                    "Opt-out cancels scheduled work"); checks++;
            serving.applyAdapter(new double[model.dimension], 7);
            sdk.clearLocalData();
            require(serving.reset && serving.version == 0, "Clear uses explicit runtime reset after cached version"); checks++;
            long retained = sdk.recordFeedback(correction(model, "retained after operator switch", "local corrected answer"));
            sdk.setParticipationEnabled(true);
            FeedbackStore local = new FeedbackStore(context);
            Lease lease = new Lease("operator_switch_lease", "operator_switch_nonce", System.currentTimeMillis()/1000 + 600,
                    new AdapterSnapshot(model, 5, new double[model.dimension]), new ServerLimits(2, 2, 1, 1), 0);
            local.saveQueue(new FeedbackStore.QueuedUpdate(new UpdatePayload(lease, "0", 1, 1, new double[model.dimension]),
                    new long[]{retained}, config.server.toASCIIString()));
            local.saveAdapter(new AdapterSnapshot(model, 5, new double[model.dimension]));
            local.close();
            Serving switched = new Serving();
            SiBalsaConfig changed = SiBalsaConfig.builder().server("https://127.0.0.1:2").federationEnabled(true)
                    .jobId(config.jobId).minFeedback(1).maxSamples(2).maxSteps(2).build();
            SiBalsa other = new SiBalsa(context, changed, switched, training, tokenizer);
            try {
                require(!other.participationEnabled() && !sdk.participationEnabled(), "Operator switch invalidates old opt-in scope"); checks++;
                FeedbackStore inspect = new FeedbackStore(context);
                try {
                    require(inspect.queue() == null && inspect.adapter(model) == null &&
                            inspect.pending(model, 20, f -> true).get(0).localId == retained,
                            "Operator switch drops old queue/cache and retains local training feedback"); checks++;
                    inspect.saveAdapter(new AdapterSnapshot(model, 1, new double[model.dimension]));
                } finally { inspect.close(); }
                switched.applyAdapter(new double[model.dimension], 1);
                require(switched.version == 1, "Same model on new server accepts its lower independent base version"); checks++;
                other.setParticipationEnabled(true);
            } finally { other.close(); }
            SiBalsa reopened = new SiBalsa(context, changed, new Serving(), training, tokenizer);
            try {
                require(reopened.participationEnabled(), "Same operator/model scope retains explicit opt-in on restart"); checks++;
                reopened.clearLocalData();
            } finally { reopened.close(); }
        } finally { sdk.close(); }
        require(context.getSystemService(JobScheduler.class).getPendingJob(config.jobId) == null, "Close cancels jobs");
        return checks + 1;
    }
    public static int foregroundGate(Context context) throws Exception {
        require(context.getSystemService(PowerManager.class).isInteractive(), "Run smoke tests with screen on");
        Cancellation cancellation = new Cancellation();
        AndroidExecutionGate gate = new AndroidExecutionGate(context, false, cancellation);
        try {
            require(!gate.eligible(), "Interactive foreground blocks training and federated transport");
            try {
                new AndroidHttpsTransport(context, SiBalsaConfig.builder().server("https://127.0.0.1:1").build(), gate)
                        .policy(cancellation);
                throw new AssertionError("Foreground network request allowed");
            } catch (IOException expected) { /* No DNS/connect occurs before the gate. */ }
            return 3;
        } finally { gate.close(); }
    }
    public static boolean inspectGate(Context context) {
        Cancellation cancellation = new Cancellation();
        AndroidExecutionGate gate = new AndroidExecutionGate(context, false, cancellation);
        try { return gate.eligible(); } finally { gate.close(); }
    }
    private static Feedback correction(ModelSpec model, String q, String answer) {
        return new Feedback(0, model, q, "incorrect", Feedback.Kind.CORRECTION, -1, answer, "", "private", null);
    }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
}

// SPDX-License-Identifier: Unlicense
package org.sibalsa.android;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Build;
import org.sibalsa.core.AdapterSnapshot;
import org.sibalsa.core.Cancellation;
import org.sibalsa.core.Feedback;
import org.sibalsa.core.InferenceRequest;
import org.sibalsa.core.InferenceRuntime;
import org.sibalsa.core.Lease;
import org.sibalsa.core.ModelSpec;
import org.sibalsa.core.ProofOfWork;
import org.sibalsa.core.ServerPolicy;
import org.sibalsa.core.ServerTransport;
import org.sibalsa.core.SiBalsaConfig;
import org.sibalsa.core.TokenSink;
import org.sibalsa.core.Tokenizer;
import org.sibalsa.core.TrainingRequest;
import org.sibalsa.core.TrainingResult;
import org.sibalsa.core.TrainingRuntime;
import org.sibalsa.core.UpdatePayload;
import org.sibalsa.core.UpdateReceipt;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/** Integration facade. Generation and local feedback work without a server or participation. */
public final class SiBalsa implements AutoCloseable {
    private final Context context;
    private final SiBalsaConfig config;
    private final InferenceRuntime inference;
    private final TrainingRuntime training;
    private final Tokenizer tokenizer;
    private final TransportFactory transportFactory;
    private final FeedbackStore store;
    private final SharedPreferences preferences;
    private final String consentScope;
    private final AtomicReference<Cancellation> activeTraining = new AtomicReference<>();
    private volatile boolean closed;

    public SiBalsa(Context context, SiBalsaConfig config, InferenceRuntime inference,
                   TrainingRuntime training, Tokenizer tokenizer) throws Exception {
        this(context, config, inference, training, tokenizer, AndroidHttpsTransport::new);
    }
    public SiBalsa(Context context, SiBalsaConfig config, InferenceRuntime inference,
                   TrainingRuntime training, Tokenizer tokenizer, TransportFactory transportFactory) throws Exception {
        if (context == null || config == null || inference == null || training == null || tokenizer == null || transportFactory == null)
            throw new IllegalArgumentException("All integration slots must be supplied (server is optional in config)");
        if (!inference.model().equals(training.model())) throw new IllegalArgumentException("Serving and training model contracts differ");
        this.context = context.getApplicationContext();
        this.config = config;
        this.inference = inference;
        this.training = training;
        this.tokenizer = tokenizer;
        this.transportFactory = transportFactory;
        this.store = new FeedbackStore(this.context);
        this.preferences = this.context.getSharedPreferences("sibalsa-local-consent", Context.MODE_PRIVATE);
        this.consentScope = serverOrigin() + "|" + model().modelId + "|" + model().adapterSpec + "|" + model().dimension;
        if (!consentScope.equals(preferences.getString("scope", ""))) {
            // Clear before committing the new scope: a crash must never label an old operator's cache as new.
            store.clearAdapter();
            inference.resetAdapter();
            if (!preferences.edit().putBoolean("participating", false).putString("scope", consentScope)
                    .putBoolean("adapter_refresh_pending", false).commit())
                throw new IOException("Could not persist changed server/model consent scope");
        }
        FeedbackStore.QueuedUpdate pending = store.queue();
        if (pending != null && (!model().equals(pending.payload.lease.snapshot.model) || !serverOrigin().equals(pending.serverOrigin)))
            store.clearQueue();
        AdapterSnapshot cached = store.adapter(inference.model());
        if (cached != null) inference.applyAdapter(cached.adapter(), cached.baseVersion);
    }
    public SiBalsaConfig config() { return config; }
    public ModelSpec model() { return inference.model(); }
    public Tokenizer tokenizer() { return tokenizer; }
    public boolean participationEnabled() {
        return !closed && config.federationEnabled && consentScope.equals(preferences.getString("scope", "")) &&
                preferences.getBoolean("participating", false);
    }
    public void setParticipationEnabled(boolean enabled) {
        requireOpen();
        if (enabled && !config.federationEnabled) throw new IllegalStateException("Enable federation and configure HTTPS server first");
        SharedPreferences.Editor consent = preferences.edit().putBoolean("participating", enabled).putString("scope", consentScope);
        if (!enabled) consent.putBoolean("adapter_refresh_pending", false);
        if (!consent.commit())
            throw new IllegalStateException("Could not persist participation preference");
        if (enabled) scheduleFederation();
        else { cancelJob(); store.clearQueue(); }
    }
    public long recordFeedback(Feedback feedback) {
        requireOpen();
        if (feedback == null || !model().equals(feedback.model)) throw new IllegalArgumentException("Feedback model mismatch");
        long id = store.insert(feedback, config.maxFeedback);
        if (participationEnabled()) scheduleFederation();
        return id;
    }
    public void deleteFeedback(long localId) {
        requireOpen();
        if (localId < 1) throw new IllegalArgumentException("Invalid local feedback ID");
        cancelJob();
        store.delete(localId);
        if (participationEnabled()) scheduleFederation();
    }
    public void clearLocalData() throws Exception {
        requireOpen();
        setParticipationEnabled(false);
        store.clearAll();
        inference.resetAdapter();
    }
    /** Call from a host-owned worker thread. The SDK never contacts the server for generation. */
    public void generate(InferenceRequest request, Cancellation cancellation, TokenSink sink) throws Exception {
        requireOpen();
        stopTraining();
        inference.generate(request, cancellation, sink);
    }
    public boolean scheduleFederation() {
        return scheduleFederation(0);
    }
    boolean scheduleRetry() { return scheduleFederation(config.retryBackoffMs); }
    private boolean scheduleFederation(long delayMs) {
        requireOpen();
        if (!participationEnabled()) return false;
        JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        if (scheduler == null) return false;
        JobInfo.Builder job = new JobInfo.Builder(config.jobId, new ComponentName(context, FederatedJobService.class))
                .setRequiresCharging(true).setRequiresDeviceIdle(true)
                .setRequiresBatteryNotLow(config.requireBatteryNotLow)
                .setRequiresStorageNotLow(config.requireStorageNotLow)
                .setPersisted(true);
        // Android forbids explicit backoff criteria with a requiresDeviceIdle job.
        if (delayMs > 0) job.setMinimumLatency(delayMs);
        if (Build.VERSION.SDK_INT >= 28) {
            NetworkRequest.Builder wifi = new NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
            if (config.requireUnmeteredWifi) wifi.addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED);
            job.setRequiredNetwork(wifi.build());
        } else {
            job.setRequiredNetworkType(config.requireUnmeteredWifi ? JobInfo.NETWORK_TYPE_UNMETERED : JobInfo.NETWORK_TYPE_ANY);
        }
        return scheduler.schedule(job.build()) == JobScheduler.RESULT_SUCCESS;
    }
    /** Refresh still runs exclusively as an idle, Wi-Fi, charging job; never in the foreground. */
    public boolean requestAdapterRefresh() {
        requireOpen();
        if (!participationEnabled()) return false;
        if (!preferences.edit().putBoolean("adapter_refresh_pending", true).commit())
            throw new IllegalStateException("Could not persist adapter refresh request");
        return scheduleFederation();
    }
    void cancelJob() {
        stopTraining();
        JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        if (scheduler != null) scheduler.cancel(config.jobId);
    }
    private void stopTraining() {
        Cancellation cancellation = activeTraining.get();
        if (cancellation != null) cancellation.cancel();
    }
    boolean beginJob(Cancellation cancellation) { return participationEnabled() && activeTraining.compareAndSet(null, cancellation); }
    void endJob(Cancellation cancellation) { activeTraining.compareAndSet(cancellation, null); }

    /** Returns true only for retryable failure; no eligible local data does not create a retry loop. */
    boolean runRound(AndroidExecutionGate gate, Cancellation cancellation) {
        try {
            gate.requireEligible(cancellation);
            if (!participationEnabled()) return false;
            FeedbackStore.QueuedUpdate queued = store.queue();
            if (queued != null && (!model().equals(queued.payload.lease.snapshot.model) || !serverOrigin().equals(queued.serverOrigin))) {
                store.clearQueue();
                queued = null;
            }
            List<Feedback> local = queued == null ? store.pending(model(), config.maxSamples, training::accepts) : null;
            boolean refresh = preferences.getBoolean("adapter_refresh_pending", false);
            if (queued == null && local.size() < config.minFeedback && !refresh) return false;
            try (ServerTransport server = transportFactory.create(context, config, gate)) {
                if (queued == null && refresh && local.size() < config.minFeedback) {
                    refreshAdapter(server, gate, cancellation);
                    return false;
                }
                if (queued == null) {
                    gate.requireEligible(cancellation);
                    ServerPolicy policy = server.policy(cancellation);
                    requireModel(policy.snapshot);
                    gate.requireEligible(cancellation);
                    Lease lease = server.lease(policy.snapshot, cancellation);
                    requireModel(lease.snapshot);
                    if (lease.expired(System.currentTimeMillis() / 1000)) throw new IOException("Expired lease");
                    int samples = lease.limits.effectiveSamples(config);
                    if (samples < config.minFeedback) return false;
                    local = store.pending(model(), samples, training::accepts);
                    if (local.size() < config.minFeedback) return false;
                    TrainingRequest request = new TrainingRequest(model(), lease.snapshot.adapter(), local, lease.limits,
                            lease.limits.effectiveSteps(config), config.learningRate, gate);
                    gate.requireEligible(cancellation);
                    TrainingResult result = training.train(request, cancellation, (done, total) -> gate.requireEligible(cancellation));
                    gate.requireEligible(cancellation);
                    long[] ids = result.validateContributors(request);
                    String proof = ProofOfWork.solve(lease, gate, cancellation);
                    UpdatePayload update = new UpdatePayload(lease, proof, result.samples, result.steps, result.delta());
                    queued = new FeedbackStore.QueuedUpdate(update, ids, serverOrigin());
                    // Persist before upload: dropped responses never cause a different retry payload.
                    gate.requireEligible(cancellation);
                    store.saveQueue(queued);
                } else requireModel(queued.payload.lease.snapshot);
                gate.requireEligible(cancellation);
                UpdateReceipt receipt = server.upload(queued.payload, cancellation);
                if (receipt == null || receipt.baseVersion != queued.payload.lease.snapshot.baseVersion)
                    throw new IOException("Upload receipt does not match its lease base version");
                gate.requireEligible(cancellation);
                if (!preferences.edit().putBoolean("adapter_refresh_pending", true).commit())
                    throw new IOException("Could not persist adapter refresh state");
                store.accepted(queued);
                refreshAdapter(server, gate, cancellation);
                return store.pending(model(), config.minFeedback, training::accepts).size() >= config.minFeedback;
            }
        } catch (HttpStatusException e) {
            if (e.permanentlyRejected()) { store.clearQueue(); return false; }
            return participationEnabled();
        } catch (IllegalArgumentException e) {
            // Model/schema/runtime contract errors need an integration correction, not an infinite retry.
            return false;
        } catch (Exception e) {
            // No prompts, notes, responses or exception bodies are emitted to telemetry/logcat.
            return participationEnabled();
        }
    }
    private void refreshAdapter(ServerTransport server, AndroidExecutionGate gate, Cancellation cancellation) throws Exception {
        gate.requireEligible(cancellation);
        AdapterSnapshot snapshot = server.adapter(cancellation);
        requireModel(snapshot);
        gate.requireEligible(cancellation);
        AdapterSnapshot previous = store.adapter(model());
        if (previous != null && snapshot.baseVersion < previous.baseVersion) throw new IOException("Server adapter rollback rejected");
        gate.requireEligible(cancellation);
        inference.applyAdapter(snapshot.adapter(), snapshot.baseVersion);
        store.saveAdapter(snapshot);
        if (!preferences.edit().putBoolean("adapter_refresh_pending", false).commit())
            throw new IOException("Could not persist adapter refresh completion");
    }
    private void requireModel(AdapterSnapshot snapshot) throws IOException {
        if (!model().equals(snapshot.model)) throw new IOException("Server model/adapter group mismatch");
    }
    private void requireOpen() { if (closed) throw new IllegalStateException("SIbalsa is closed"); }
    private String serverOrigin() { return config.server == null ? "" : config.server.normalize().toASCIIString(); }
    @Override public void close() throws Exception {
        if (closed) return;
        closed = true;
        cancelJob();
        store.close();
        try { inference.close(); } finally { training.close(); }
    }
}

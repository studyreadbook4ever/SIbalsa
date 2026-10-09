// SPDX-License-Identifier: Unlicense
package org.sibalsa.example;

import android.app.Application;
import android.content.SharedPreferences;
import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.sibalsa.android.RuntimeProvider;
import org.sibalsa.android.SiBalsa;
import org.sibalsa.core.SiBalsaConfig;
import org.sibalsa.core.Cancellation;
import org.sibalsa.nativeengine.NativeInferenceRuntime;
import org.sibalsa.nativeengine.NativeTrainingRuntime;

public final class SIbalsaApplication extends Application implements RuntimeProvider {
    final ExecutorService worker = Executors.newSingleThreadExecutor();
    volatile SiBalsa sdk;
    volatile ModelBundle bundle;
    volatile String status = "모델과 서버를 직접 연결하세요.";
    private volatile Future<?> initialization;
    @Override public void onCreate() {
        super.onCreate();
        String directory = settings().getString("directory", "");
        if (!directory.isEmpty()) initialization = worker.submit(() -> {
            try { configure(new File(directory), false); } catch (Exception | LinkageError error) { status = "통합 오류: " + error.getMessage(); }
        });
    }
    SharedPreferences settings() { return getSharedPreferences("integration", MODE_PRIVATE); }
    synchronized void configure(File directory) throws Exception {
        configure(directory, true);
    }
    private synchronized void configure(File directory, boolean schedule) throws Exception {
        ModelBundle nextBundle = new ModelBundle(directory);
        SharedPreferences prefs = settings();
        String origin = prefs.getString("server", "").trim();
        SiBalsaConfig.Builder builder = SiBalsaConfig.builder()
                .federationEnabled(!origin.isEmpty())
                .requireUnmeteredWifi(prefs.getBoolean("unmetered", true))
                .minFeedback(prefs.getInt("minFeedback", 8))
                .maxSamples(prefs.getInt("maxSamples", 32))
                .maxSteps(prefs.getInt("maxSteps", 8))
                .learningRate(Double.parseDouble(prefs.getString("learningRate", "0.001")))
                .maxResponseBytes(prefs.getInt("maxResponseBytes", 32 * 1024 * 1024));
        if (!origin.isEmpty()) builder.server(origin);
        String pin = prefs.getString("pin", "").trim();
        if (!pin.isEmpty()) builder.spkiPin(pin);
        File ca = new File(getFilesDir(), "development-ca.crt");
        if (ca.isFile()) builder.developmentCa(ModelBundle.read(ca, 65536).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        SiBalsaConfig nextConfig = builder.build();
        SiBalsa previous = sdk;
        boolean scopeChanged = previous != null && (!previous.model().equals(nextBundle.spec)
                || !java.util.Objects.equals(previous.config().server, nextConfig.server));
        if (scopeChanged && !prefs.edit().putBoolean("participation", false).commit())
            throw new IllegalStateException("Cannot save new federation scope preference");
        NativeInferenceRuntime inference = new NativeInferenceRuntime(nextBundle.model.getPath(), nextBundle.spec,
                nextBundle.tokenizer, nextBundle.rank, nextBundle.context, nextBundle.bos, nextBundle.eos,
                nextBundle.prefix, nextBundle.suffix);
        NativeTrainingRuntime training = new NativeTrainingRuntime(nextBundle.model.getPath(), nextBundle.spec,
                nextBundle.tokenizer, nextBundle.rank, nextBundle.context, nextBundle.bos,
                nextBundle.eos.length == 0 ? -1 : nextBundle.eos[0], nextBundle.prefix, nextBundle.suffix,
                nextBundle.seed, nextBundle.beta, nextBundle.clip);
        SiBalsa next;
        try {
            if (previous != null) { previous.close(); sdk = null; }
            next = new SiBalsa(this, nextConfig, inference, training, nextBundle.tokenizer);
        }
        catch (Exception | Error error) { inference.close(); throw error; }
        sdk = next; bundle = nextBundle;
        prefs.edit().putString("directory", directory.getPath()).commit();
        // Restoring a persisted job must not replace/cancel the very job starting this process.
        if (schedule) next.setParticipationEnabled(!origin.isEmpty() && prefs.getBoolean("participation", false));
        status = "모델 준비 완료 · 공동학습 " + (next.participationEnabled() ? "참여" : "꺼짐");
    }
    @Override public SiBalsa siBalsa() { return sdk; }
    @Override public SiBalsa awaitSiBalsa(Cancellation cancellation) throws Exception {
        Future<?> pending = initialization;
        while (pending != null && !pending.isDone()) {
            cancellation.check();
            try { pending.get(1, TimeUnit.SECONDS); }
            catch (TimeoutException waiting) { /* Check scheduler cancellation between bounded waits. */ }
        }
        cancellation.check();
        return sdk;
    }
}

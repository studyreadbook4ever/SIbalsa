// SPDX-License-Identifier: Unlicense
package org.sibalsa.android;

import android.app.job.JobParameters;
import android.app.job.JobService;
import org.sibalsa.core.Cancellation;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** The platform's device-idle constraint grants the permit; Doze state is not an idle substitute. */
public final class FederatedJobService extends JobService {
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private volatile JobRun current;
    private static final class JobRun {
        volatile SiBalsa sdk;
        final Cancellation cancellation;
        volatile AndroidExecutionGate gate;
        volatile boolean stopped;
        JobRun(Cancellation cancellation) { this.cancellation = cancellation; }
        void stop() {
            stopped = true;
            cancellation.cancel();
            AndroidExecutionGate live = gate;
            if (live != null) live.close();
        }
    }
    @Override public boolean onStartJob(JobParameters parameters) {
        if (!(getApplication() instanceof RuntimeProvider)) return false;
        if (current != null) return false;
        RuntimeProvider provider = (RuntimeProvider) getApplication();
        Cancellation token = new Cancellation();
        JobRun run = new JobRun(token);
        current = run;
        executor.execute(() -> {
            boolean retry = false;
            try {
                SiBalsa selected = provider.awaitSiBalsa(token);
                token.check();
                if (selected == null || selected.config().jobId != parameters.getJobId() || !selected.beginJob(token)) return;
                run.sdk = selected;
                AndroidExecutionGate live = new AndroidExecutionGate(this, selected.config().requireUnmeteredWifi, token);
                run.gate = live;
                live.watch();
                retry = selected.runRound(live, token);
            } catch (Exception e) { retry = run.sdk != null && run.sdk.participationEnabled(); }
            finally {
                AndroidExecutionGate live = run.gate;
                if (live != null) live.close();
                SiBalsa selected = run.sdk;
                if (selected != null) selected.endJob(token);
                if (current == run) current = null;
                if (!run.stopped) {
                    jobFinished(parameters, false);
                    if (retry && selected != null && selected.participationEnabled()) selected.scheduleRetry();
                }
            }
        });
        return true;
    }
    @Override public boolean onStopJob(JobParameters parameters) {
        JobRun run = current;
        if (run == null) return false;
        run.stop();
        return run.sdk == null || run.sdk.participationEnabled();
    }
    @Override public void onDestroy() {
        JobRun run = current;
        if (run != null) run.stop();
        executor.shutdownNow();
        super.onDestroy();
    }
}

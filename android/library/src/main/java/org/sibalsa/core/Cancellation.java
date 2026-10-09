// SPDX-License-Identifier: Unlicense
package org.sibalsa.core;

import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.List;

/** Cooperative cancellation, including listeners that close blocking network operations. */
public final class Cancellation {
    private boolean cancelled;
    private final List<Runnable> listeners = new ArrayList<>();
    public synchronized boolean isCancelled() { return cancelled; }
    public void cancel() {
        List<Runnable> closing;
        synchronized (this) {
            if (cancelled) return;
            cancelled = true;
            closing = new ArrayList<>(listeners);
            listeners.clear();
        }
        for (Runnable listener : closing) {
            try { listener.run(); } catch (RuntimeException ignored) { /* Continue closing others. */ }
        }
    }
    public void check() throws InterruptedIOException {
        if (isCancelled() || Thread.currentThread().isInterrupted())
            throw new InterruptedIOException("SIbalsa operation cancelled");
    }
    public AutoCloseable onCancel(Runnable listener) {
        synchronized (this) {
            if (!cancelled) {
                listeners.add(listener);
                return () -> { synchronized (Cancellation.this) { listeners.remove(listener); } };
            }
        }
        listener.run();
        return () -> {};
    }
}

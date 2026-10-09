// SPDX-License-Identifier: Unlicense
package org.sibalsa.core;

/** Separate from serving: produce a delta without mutating the active inference model. */
public interface TrainingRuntime extends AutoCloseable {
    ModelSpec model();
    /** Allows a runtime to decline signals it cannot meaningfully learn from. */
    default boolean accepts(Feedback feedback) { return true; }
    TrainingResult train(TrainingRequest request, Cancellation cancellation, ProgressSink progress) throws Exception;
    @Override default void close() throws Exception {}
}

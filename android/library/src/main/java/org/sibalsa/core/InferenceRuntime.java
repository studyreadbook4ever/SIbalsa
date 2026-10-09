// SPDX-License-Identifier: Unlicense
package org.sibalsa.core;

/** Offline serving. Adapter changes must be atomic relative to generation. */
public interface InferenceRuntime extends AutoCloseable {
    ModelSpec model();
    void generate(InferenceRequest request, Cancellation cancellation, TokenSink sink) throws Exception;
    void applyAdapter(double[] adapter, long version) throws Exception;
    /** Explicit user-requested reset; implementations with version guards should override this. */
    default void resetAdapter() throws Exception { applyAdapter(new double[model().dimension], 0); }
    @Override default void close() throws Exception {}
}

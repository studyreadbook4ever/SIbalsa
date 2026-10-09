// SPDX-License-Identifier: Unlicense
package org.sibalsa.core;

import java.util.Objects;

/** The ID is the SHA-256 of the actual integrated model artifact, not a display name. */
public final class ModelSpec {
    public final String modelId;
    public final String adapterSpec;
    public final int dimension;
    public ModelSpec(String modelId, String adapterSpec, int dimension) {
        if (modelId == null || !modelId.matches("[a-f0-9]{64}"))
            throw new IllegalArgumentException("modelId must be lowercase SHA-256");
        if (adapterSpec == null || !adapterSpec.matches("[A-Za-z0-9][A-Za-z0-9_.:-]{0,63}"))
            throw new IllegalArgumentException("Invalid adapter spec");
        this.modelId = modelId;
        this.adapterSpec = adapterSpec;
        this.dimension = Checks.range("dimension", dimension, 1, 1_000_000);
    }
    @Override public boolean equals(Object other) {
        if (!(other instanceof ModelSpec)) return false;
        ModelSpec s = (ModelSpec) other;
        return modelId.equals(s.modelId) && adapterSpec.equals(s.adapterSpec) && dimension == s.dimension;
    }
    @Override public int hashCode() { return Objects.hash(modelId, adapterSpec, dimension); }
}

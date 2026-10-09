// SPDX-License-Identifier: Unlicense
package org.sibalsa.core;

public final class AdapterSnapshot {
    public final ModelSpec model;
    public final long baseVersion;
    private final double[] adapter;
    public AdapterSnapshot(ModelSpec model, long baseVersion, double[] adapter) {
        if (model == null || baseVersion < 0) throw new IllegalArgumentException("Invalid adapter identity");
        this.model = model;
        this.baseVersion = baseVersion;
        this.adapter = validate(adapter, model.dimension);
    }
    public double[] adapter() { return adapter.clone(); }
    static double[] validate(double[] values, int dimension) {
        if (values == null || values.length != dimension) throw new IllegalArgumentException("Adapter dimension mismatch");
        double[] copy = values.clone();
        for (double value : copy) if (!Double.isFinite(value)) throw new IllegalArgumentException("Nonfinite adapter");
        return copy;
    }
}

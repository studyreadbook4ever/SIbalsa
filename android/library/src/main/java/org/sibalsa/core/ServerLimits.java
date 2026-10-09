// SPDX-License-Identifier: Unlicense
package org.sibalsa.core;

public final class ServerLimits {
    public final int maxSamples, maxSteps;
    public final double maxL2, maxCoordinate;
    public ServerLimits(int maxSamples, int maxSteps, double maxL2, double maxCoordinate) {
        this.maxSamples = Checks.range("server maxSamples", maxSamples, 1, 1_000_000);
        this.maxSteps = Checks.range("server maxSteps", maxSteps, 1, 100_000);
        this.maxL2 = Checks.finite("server maxL2", maxL2, 1e-12, 1e6);
        this.maxCoordinate = Checks.finite("server maxCoordinate", maxCoordinate, 1e-12, 1e6);
    }
    public int effectiveSamples(SiBalsaConfig config) { return Math.min(maxSamples, config.maxSamples); }
    public int effectiveSteps(SiBalsaConfig config) { return Math.min(maxSteps, config.maxSteps); }
    public double[] clipped(double[] delta, int dimension) {
        if (delta == null || delta.length != dimension) throw new IllegalArgumentException("Delta dimension mismatch");
        double[] result = delta.clone();
        double norm = 0;
        for (int i = 0; i < result.length; i++) {
            if (!Double.isFinite(result[i])) throw new IllegalArgumentException("Nonfinite delta");
            result[i] = Math.max(-maxCoordinate, Math.min(maxCoordinate, result[i]));
            norm = Math.hypot(norm, result[i]);
        }
        if (norm > maxL2) {
            // Small margin avoids numeric rejection at an exact server limit.
            double scale = maxL2 / norm * (1 - 1e-12);
            for (int i = 0; i < result.length; i++) result[i] *= scale;
        }
        return result;
    }
}

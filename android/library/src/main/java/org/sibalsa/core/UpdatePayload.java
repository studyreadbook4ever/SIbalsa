// SPDX-License-Identifier: Unlicense
package org.sibalsa.core;

/** Only anonymous lease credentials, work counts and clipped parameter deltas can be serialized. */
public final class UpdatePayload {
    public final Lease lease;
    public final String proof;
    public final int sampleCount, steps;
    private final double[] delta;
    public UpdatePayload(Lease lease, String proof, int sampleCount, int steps, double[] delta) {
        if (lease == null || proof == null || !proof.matches("[0-9]{1,20}"))
            throw new IllegalArgumentException("Invalid update credentials");
        if (sampleCount < 1 || sampleCount > lease.limits.maxSamples || steps < 1 || steps > lease.limits.maxSteps)
            throw new IllegalArgumentException("Update exceeds server limits");
        this.lease = lease;
        this.proof = proof;
        this.sampleCount = sampleCount;
        this.steps = steps;
        this.delta = lease.limits.clipped(delta, lease.snapshot.model.dimension);
    }
    public double[] delta() { return delta.clone(); }
}

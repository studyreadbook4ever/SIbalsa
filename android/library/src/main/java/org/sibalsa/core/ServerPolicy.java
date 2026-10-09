// SPDX-License-Identifier: Unlicense
package org.sibalsa.core;

public final class ServerPolicy {
    public final AdapterSnapshot snapshot;
    public final ServerLimits limits;
    public final int powBits;
    public final long leaseSeconds;
    public ServerPolicy(AdapterSnapshot snapshot, ServerLimits limits, int powBits, long leaseSeconds) {
        if (snapshot == null || limits == null || leaseSeconds < 1 || leaseSeconds > 86400)
            throw new IllegalArgumentException("Invalid server policy");
        this.snapshot = snapshot;
        this.limits = limits;
        this.powBits = Checks.range("proof work bits", powBits, 0, 24);
        this.leaseSeconds = leaseSeconds;
    }
}

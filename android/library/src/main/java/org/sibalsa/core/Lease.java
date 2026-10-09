// SPDX-License-Identifier: Unlicense
package org.sibalsa.core;

public final class Lease {
    public final String leaseId, nonce;
    public final long expiresAt;
    public final AdapterSnapshot snapshot;
    public final ServerLimits limits;
    public final int powBits;
    public Lease(String leaseId, String nonce, long expiresAt, AdapterSnapshot snapshot,
                 ServerLimits limits, int powBits) {
        this.leaseId = machineToken("leaseId", leaseId);
        this.nonce = machineToken("nonce", nonce);
        if (expiresAt < 1 || snapshot == null || limits == null) throw new IllegalArgumentException("Invalid lease");
        this.expiresAt = expiresAt;
        this.snapshot = snapshot;
        this.limits = limits;
        this.powBits = Checks.range("proof work bits", powBits, 0, 24);
    }
    private static String machineToken(String name, String value) {
        if (value == null || !value.matches("[A-Za-z0-9_-]{8,128}"))
            throw new IllegalArgumentException("Invalid " + name);
        return value;
    }
    public boolean expired(long epochSeconds) { return epochSeconds >= expiresAt; }
}

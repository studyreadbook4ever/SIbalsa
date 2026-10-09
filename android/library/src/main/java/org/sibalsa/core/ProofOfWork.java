// SPDX-License-Identifier: Unlicense
package org.sibalsa.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Public participation uses per-lease work; it does not authenticate a human or prevent Sybils. */
public final class ProofOfWork {
    private ProofOfWork() {}
    public static String solve(Lease lease, ExecutionGate gate, Cancellation cancellation) throws Exception {
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        for (long proof = 0; proof >= 0; proof++) {
            if ((proof & 1023) == 0) {
                gate.requireEligible(cancellation);
                if (lease.expired(System.currentTimeMillis() / 1000))
                    throw new java.io.IOException("Lease expired while finding proof");
            }
            String value = Long.toString(proof);
            byte[] hash = sha.digest((lease.leaseId + ":" + lease.nonce + ":" + value).getBytes(StandardCharsets.UTF_8));
            if (hasLeadingZeros(hash, lease.powBits)) return value;
        }
        throw new java.io.IOException("Proof search exhausted");
    }
    public static boolean hasLeadingZeros(byte[] digest, int bits) {
        if (digest == null || bits < 0 || bits > digest.length * 8) return false;
        int whole = bits / 8;
        for (int i = 0; i < whole; i++) if (digest[i] != 0) return false;
        return bits % 8 == 0 || ((digest[whole] & 255) >>> (8 - bits % 8)) == 0;
    }
}

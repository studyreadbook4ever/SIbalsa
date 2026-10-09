// SPDX-License-Identifier: Unlicense
package org.sibalsa.core;

public final class UpdateReceipt {
    public final long baseVersion;
    public final int roundSize;
    public final Long candidateVersion;
    public UpdateReceipt(long baseVersion, int roundSize, Long candidateVersion) {
        if (baseVersion < 0 || roundSize < 1 || (candidateVersion != null && candidateVersion < 0))
            throw new IllegalArgumentException("Invalid update receipt");
        this.baseVersion = baseVersion;
        this.roundSize = roundSize;
        this.candidateVersion = candidateVersion;
    }
}

// SPDX-License-Identifier: Unlicense
package org.sibalsa.android;

import org.sibalsa.core.Cancellation;

/** Implement on the host Application. JobService resolves no downloaded executable code. */
public interface RuntimeProvider {
    SiBalsa siBalsa();
    /** Invoked on the job worker. Async hosts can wait for initialization, checking cancellation. */
    default SiBalsa awaitSiBalsa(Cancellation cancellation) throws Exception {
        cancellation.check();
        return siBalsa();
    }
}

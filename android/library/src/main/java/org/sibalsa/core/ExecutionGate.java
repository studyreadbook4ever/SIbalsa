// SPDX-License-Identifier: Unlicense
package org.sibalsa.core;

import java.io.IOException;

/** Training runtimes must call this between minibatches and before returning a result. */
public interface ExecutionGate {
    boolean eligible();
    void requireEligible(Cancellation cancellation) throws IOException;
}

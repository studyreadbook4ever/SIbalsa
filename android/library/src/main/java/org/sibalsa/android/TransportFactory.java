// SPDX-License-Identifier: Unlicense
package org.sibalsa.android;

import android.content.Context;
import org.sibalsa.core.ServerTransport;
import org.sibalsa.core.SiBalsaConfig;

/** Custom integrations must preserve the ServerTransport TLS, live-gate and cancellation contract. */
public interface TransportFactory {
    ServerTransport create(Context context, SiBalsaConfig config, AndroidExecutionGate gate) throws Exception;
}

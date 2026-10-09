// SPDX-License-Identifier: Unlicense
package org.sibalsa.core;

/**
 * Transport integration contract: authenticated TLS 1.2+, no redirects, Wi-Fi binding,
 * gate checks before every network action and cancellation of blocked I/O are mandatory.
 * The built-in Android transport implements these requirements.
 */
public interface ServerTransport extends AutoCloseable {
    ServerPolicy policy(Cancellation cancellation) throws Exception;
    Lease lease(AdapterSnapshot base, Cancellation cancellation) throws Exception;
    UpdateReceipt upload(UpdatePayload payload, Cancellation cancellation) throws Exception;
    AdapterSnapshot adapter(Cancellation cancellation) throws Exception;
    @Override default void close() throws Exception {}
}

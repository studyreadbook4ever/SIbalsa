// SPDX-License-Identifier: Unlicense
package org.sibalsa.android;

import java.io.IOException;

/** Response bodies are deliberately excluded from exception messages and logs. */
public final class HttpStatusException extends IOException {
    public final int status;
    public HttpStatusException(int status) { super("SIbalsa server HTTP " + status); this.status = status; }
    public boolean permanentlyRejected() { return status == 400 || status == 403 || status == 404 || status == 409 || status == 410 || status == 413; }
}

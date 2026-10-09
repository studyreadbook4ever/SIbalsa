// SPDX-License-Identifier: Unlicense
package org.sibalsa.core;

/** JobScheduler device-idle eligibility is distinct from deep Doze mode. */
public final class DeviceState {
    public final boolean known, jobIdleEligible, interactive, charging, wifi, validated, unmetered;
    public DeviceState(boolean known, boolean jobIdleEligible, boolean interactive,
                       boolean charging, boolean wifi, boolean validated, boolean unmetered) {
        this.known = known;
        this.jobIdleEligible = jobIdleEligible;
        this.interactive = interactive;
        this.charging = charging;
        this.wifi = wifi;
        this.validated = validated;
        this.unmetered = unmetered;
    }
    public boolean eligible(boolean requireUnmetered) {
        return known && jobIdleEligible && !interactive && charging && wifi && validated &&
                (!requireUnmetered || unmetered);
    }
}

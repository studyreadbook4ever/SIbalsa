// SPDX-License-Identifier: Unlicense
package org.sibalsa.core;

import java.net.URI;
import java.util.Base64;

/** Local preferences can tighten server policy, never increase server limits or disable gates. */
public final class SiBalsaConfig {
    public final URI server;
    public final boolean federationEnabled;
    public final boolean requireUnmeteredWifi;
    public final boolean requireBatteryNotLow;
    public final boolean requireStorageNotLow;
    public final int minFeedback, maxFeedback, maxSteps, maxSamples;
    public final int connectTimeoutMs, readTimeoutMs, maxResponseBytes;
    public final int jobId;
    public final long retryBackoffMs;
    public final double learningRate;
    public final String spkiPin;
    private final byte[] developmentCa;

    private SiBalsaConfig(Builder b) {
        server = validateServer(b.server);
        federationEnabled = b.federationEnabled;
        if (federationEnabled && server == null) throw new IllegalArgumentException("Federation requires HTTPS server");
        requireUnmeteredWifi = b.requireUnmeteredWifi;
        requireBatteryNotLow = b.requireBatteryNotLow;
        requireStorageNotLow = b.requireStorageNotLow;
        minFeedback = Checks.range("minFeedback", b.minFeedback, 1, 1024);
        maxFeedback = Checks.range("maxFeedback", b.maxFeedback, minFeedback, 100_000);
        maxSamples = Checks.range("maxSamples", b.maxSamples, minFeedback, 1024);
        maxSteps = Checks.range("maxSteps", b.maxSteps, 1, 1000);
        connectTimeoutMs = Checks.range("connectTimeoutMs", b.connectTimeoutMs, 1000, 120_000);
        readTimeoutMs = Checks.range("readTimeoutMs", b.readTimeoutMs, 1000, 120_000);
        maxResponseBytes = Checks.range("maxResponseBytes", b.maxResponseBytes, 1024, 32 * 1024 * 1024);
        jobId = Checks.range("jobId", b.jobId, 1, Integer.MAX_VALUE);
        if (b.retryBackoffMs < 30_000 || b.retryBackoffMs > 24 * 60 * 60 * 1000L)
            throw new IllegalArgumentException("retryBackoffMs out of range");
        retryBackoffMs = b.retryBackoffMs;
        learningRate = Checks.finite("learningRate", b.learningRate, 1e-9, 1.0);
        if (b.spkiPin != null) {
            try {
                if (!b.spkiPin.startsWith("sha256/") || Base64.getDecoder().decode(b.spkiPin.substring(7)).length != 32)
                    throw new IllegalArgumentException("SPKI pin must be sha256/base64");
            } catch (IllegalArgumentException e) { throw new IllegalArgumentException("Invalid SPKI pin", e); }
        }
        spkiPin = b.spkiPin;
        developmentCa = b.developmentCa == null ? null : b.developmentCa.clone();
        if (developmentCa != null && (developmentCa.length < 32 || developmentCa.length > 65536))
            throw new IllegalArgumentException("Invalid development CA");
    }
    public byte[] developmentCa() { return developmentCa == null ? null : developmentCa.clone(); }
    private static URI validateServer(String value) {
        if (value == null) return null;
        if (value.length() > 4096) throw new IllegalArgumentException("Server origin too long");
        URI uri = URI.create(value);
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null ||
            uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null ||
            (uri.getPort() != -1 && (uri.getPort() < 1 || uri.getPort() > 65535)) ||
            (uri.getRawPath() != null && !uri.getRawPath().isEmpty() && !"/".equals(uri.getRawPath())))
            throw new IllegalArgumentException("Server must be an HTTPS origin without credentials, query or path");
        return uri;
    }
    public static Builder builder() { return new Builder(); }
    public static final class Builder {
        private String server;
        private boolean federationEnabled, requireUnmeteredWifi = true, requireBatteryNotLow = true,
                requireStorageNotLow = true;
        private int minFeedback = 8, maxFeedback = 5000, maxSteps = 8, maxSamples = 32,
                connectTimeoutMs = 10_000, readTimeoutMs = 30_000, maxResponseBytes = 32 * 1024 * 1024,
                jobId = 0x534942;
        private long retryBackoffMs = 15 * 60 * 1000L;
        private double learningRate = 0.001;
        private String spkiPin;
        private byte[] developmentCa;
        public Builder server(String v) { server = v; return this; }
        public Builder federationEnabled(boolean v) { federationEnabled = v; return this; }
        public Builder requireUnmeteredWifi(boolean v) { requireUnmeteredWifi = v; return this; }
        public Builder requireBatteryNotLow(boolean v) { requireBatteryNotLow = v; return this; }
        public Builder requireStorageNotLow(boolean v) { requireStorageNotLow = v; return this; }
        public Builder minFeedback(int v) { minFeedback = v; return this; }
        public Builder maxFeedback(int v) { maxFeedback = v; return this; }
        public Builder maxSamples(int v) { maxSamples = v; return this; }
        public Builder maxSteps(int v) { maxSteps = v; return this; }
        public Builder learningRate(double v) { learningRate = v; return this; }
        public Builder connectTimeoutMs(int v) { connectTimeoutMs = v; return this; }
        public Builder readTimeoutMs(int v) { readTimeoutMs = v; return this; }
        public Builder maxResponseBytes(int v) { maxResponseBytes = v; return this; }
        public Builder retryBackoffMs(long v) { retryBackoffMs = v; return this; }
        public Builder jobId(int v) { jobId = v; return this; }
        public Builder spkiPin(String v) { spkiPin = v; return this; }
        /** Accepted only by a debuggable Android host. System hostname checks still apply. */
        public Builder developmentCa(byte[] v) { developmentCa = v == null ? null : v.clone(); return this; }
        public SiBalsaConfig build() { return new SiBalsaConfig(this); }
    }
}

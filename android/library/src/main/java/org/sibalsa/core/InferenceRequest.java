// SPDX-License-Identifier: Unlicense
package org.sibalsa.core;

public final class InferenceRequest {
    public final String prompt;
    public final int maxTokens;
    public final double temperature;
    public InferenceRequest(String prompt, int maxTokens, double temperature) {
        this.prompt = Checks.text("prompt", prompt, 131072, false);
        this.maxTokens = Checks.range("maxTokens", maxTokens, 1, 32768);
        this.temperature = Checks.finite("temperature", temperature, 0, 2);
    }
}

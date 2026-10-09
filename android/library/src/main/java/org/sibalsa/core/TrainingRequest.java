// SPDX-License-Identifier: Unlicense
package org.sibalsa.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class TrainingRequest {
    public final ModelSpec model;
    private final double[] baseAdapter;
    public final List<Feedback> feedback;
    public final ServerLimits limits;
    public final int requestedSteps;
    public final double learningRate;
    public final ExecutionGate gate;
    public TrainingRequest(ModelSpec model, double[] adapter, List<Feedback> feedback,
                           ServerLimits limits, int requestedSteps, double learningRate, ExecutionGate gate) {
        if (model == null || limits == null || gate == null || feedback == null || feedback.isEmpty())
            throw new IllegalArgumentException("Incomplete training request");
        this.model = model;
        this.baseAdapter = AdapterSnapshot.validate(adapter, model.dimension);
        if (feedback.size() > limits.maxSamples) throw new IllegalArgumentException("Server sample limit");
        for (Feedback f : feedback) if (f == null || !model.equals(f.model))
            throw new IllegalArgumentException("Mixed model training data");
        this.feedback = Collections.unmodifiableList(new ArrayList<>(feedback));
        this.limits = limits;
        this.requestedSteps = Checks.range("requestedSteps", requestedSteps, 1, limits.maxSteps);
        this.learningRate = Checks.finite("learningRate", learningRate, 1e-9, 1);
        this.gate = gate;
    }
    public double[] baseAdapter() { return baseAdapter.clone(); }
}

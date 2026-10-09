// SPDX-License-Identifier: Unlicense
package org.sibalsa.core;

public final class TrainingResult {
    private final double[] delta;
    private final long[] contributingFeedbackIds;
    public final int samples, steps;
    public TrainingResult(double[] delta, int samples, int steps) {
        this(delta, samples, steps, null);
    }
    /** Null IDs means every request sample contributed. Otherwise supply the exact unique local IDs. */
    public TrainingResult(double[] delta, int samples, int steps, long[] contributingFeedbackIds) {
        if (delta == null || delta.length == 0) throw new IllegalArgumentException("Empty delta");
        this.delta = AdapterSnapshot.validate(delta, delta.length);
        this.samples = Checks.range("samples", samples, 1, 1_000_000);
        this.steps = Checks.range("steps", steps, 1, 100_000);
        if (contributingFeedbackIds != null) {
            if (contributingFeedbackIds.length != samples) throw new IllegalArgumentException("Contributor count mismatch");
            java.util.HashSet<Long> unique = new java.util.HashSet<>();
            for (long id : contributingFeedbackIds)
                if (id < 1 || !unique.add(id)) throw new IllegalArgumentException("Invalid or duplicate contributor ID");
        }
        this.contributingFeedbackIds = contributingFeedbackIds == null ? null : contributingFeedbackIds.clone();
    }
    public double[] delta() { return delta.clone(); }
    public long[] contributingFeedbackIds() {
        return contributingFeedbackIds == null ? null : contributingFeedbackIds.clone();
    }
    /** Validates the result against its request and returns precisely the consumed local IDs. */
    public long[] validateContributors(TrainingRequest request) {
        if (samples > request.feedback.size() || steps > request.requestedSteps || delta.length != request.model.dimension)
            throw new IllegalArgumentException("Training result exceeds request bounds");
        java.util.HashSet<Long> available = new java.util.HashSet<>();
        for (Feedback item : request.feedback) if (!available.add(item.localId) || item.localId < 1)
            throw new IllegalArgumentException("Request needs unique persisted feedback IDs");
        if (contributingFeedbackIds == null) {
            if (samples != request.feedback.size()) throw new IllegalArgumentException("Subset result needs contributing IDs");
            long[] ids = new long[samples];
            for (int i = 0; i < samples; i++) ids[i] = request.feedback.get(i).localId;
            return ids;
        }
        for (long id : contributingFeedbackIds) if (!available.contains(id))
            throw new IllegalArgumentException("Result includes an unknown local feedback ID");
        return contributingFeedbackIds();
    }
}

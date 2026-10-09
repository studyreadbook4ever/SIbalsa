// SPDX-License-Identifier: Unlicense
package org.sibalsa.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;

/** Local-only training data. This type deliberately has no wire encoder. */
public final class Feedback {
    public enum Kind { RATING, CORRECTION, PREFERENCE }
    public enum Reason { FACTUAL_ERROR, TOO_LONG, TOO_SHORT, IRRELEVANT, STYLE, INSTRUCTION_FAILURE, OTHER }
    public final long localId;
    public final ModelSpec model;
    public final String prompt, response, chosen, rejected, note;
    public final Kind kind;
    public final int rating;
    public final List<Reason> reasons;
    public Feedback(long localId, ModelSpec model, String prompt, String response, Kind kind, int rating,
                    String chosen, String rejected, String note, List<Reason> reasons) {
        if (localId < 0 || model == null || kind == null) throw new IllegalArgumentException("Invalid feedback identity");
        this.localId = localId;
        this.model = model;
        this.prompt = Checks.text("prompt", prompt, 32768, false);
        this.response = Checks.text("response", response, 32768, false);
        this.kind = kind;
        this.rating = Checks.range("rating", rating, -1, 1);
        this.chosen = Checks.text("chosen", chosen == null ? "" : chosen, 32768, true);
        this.rejected = Checks.text("rejected", rejected == null ? "" : rejected, 32768, true);
        this.note = Checks.text("note", note == null ? "" : note, 8192, true);
        EnumSet<Reason> unique = EnumSet.noneOf(Reason.class);
        if (reasons != null) unique.addAll(reasons);
        this.reasons = Collections.unmodifiableList(new ArrayList<>(unique));
        if (kind == Kind.CORRECTION && this.chosen.isEmpty())
            throw new IllegalArgumentException("Correction requires a corrected answer");
        if (kind == Kind.PREFERENCE && (this.chosen.isEmpty() || this.rejected.isEmpty() || this.chosen.equals(this.rejected)))
            throw new IllegalArgumentException("Preference requires distinct chosen and rejected answers for this prompt");
        if (kind == Kind.RATING && rating == 0 && this.reasons.isEmpty() && this.note.isEmpty())
            throw new IllegalArgumentException("Rating feedback needs a signal");
    }
}

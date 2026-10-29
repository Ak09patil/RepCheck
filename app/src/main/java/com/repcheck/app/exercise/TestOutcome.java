package com.repcheck.app.exercise;

import com.repcheck.app.analysis.SessionVerdict;

/** The result of one attempt at any test: a headline score, a verdict, and a readable breakdown. */
public final class TestOutcome {

    public final double score;
    /** "reps" or "cm" */
    public final String unit;
    /** Label under the big number on the result screen, e.g. "valid reps" or "best jump". */
    public final String scoreLabel;
    public final SessionVerdict verdict;
    /** Multi-line breakdown for the result screen and officials. */
    public final String details;

    /** Rep tests fill these; jump tests use valid = valid jumps and leave the rest 0. */
    public final int valid, partial, badForm, tooFast;

    public TestOutcome(double score, String unit, String scoreLabel, SessionVerdict verdict, String details,
                       int valid, int partial, int badForm, int tooFast) {
        this.score = score;
        this.unit = unit;
        this.scoreLabel = scoreLabel;
        this.verdict = verdict;
        this.details = details;
        this.valid = valid;
        this.partial = partial;
        this.badForm = badForm;
        this.tooFast = tooFast;
    }
}

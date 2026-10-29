package com.repcheck.app.analysis;

import com.repcheck.app.exercise.RepCounter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Decides whether a whole recorded attempt can be trusted, and explains why.
 * Plain Java (no Android) so it's unit-tested.
 *
 * Rules, most serious first:
 *   - no attempts at all                            → NO_REPS
 *   - any rep faster than humanly plausible         → FLAGGED   (sped-up video or not a real rep)
 *   - athlete out of frame for >40% of the video    → NEEDS_REVIEW
 *   - more than half the attempts partial/bad form  → NEEDS_REVIEW
 *   - otherwise                                     → VERIFIED
 */
public final class SessionVerdict {

    public enum Status { VERIFIED, NEEDS_REVIEW, FLAGGED, NO_REPS }

    static final double MIN_BODY_VISIBLE_FRACTION = 0.6;

    public final Status status;
    public final List<String> reasons;

    private SessionVerdict(Status status, List<String> reasons) {
        this.status = status;
        this.reasons = Collections.unmodifiableList(reasons);
    }

    /** For tests with their own rules (e.g. JumpDetector). */
    public static SessionVerdict of(Status status, List<String> reasons) {
        return new SessionVerdict(status, new ArrayList<>(reasons));
    }

    /** The more serious of two statuses (VERIFIED < NEEDS_REVIEW < FLAGGED). NO_REPS is handled separately. */
    public static Status worse(Status a, Status b) {
        return a.ordinal() >= b.ordinal() ? a : b;
    }

    public static SessionVerdict judge(RepCounter.FrameResult counts, int framesAnalysed, int framesWithBody) {
        List<String> reasons = new ArrayList<>();
        int total = counts.totalAttempts();
        double visibleFraction = framesAnalysed == 0 ? 0 : (double) framesWithBody / framesAnalysed;

        if (total == 0) {
            reasons.add(visibleFraction < MIN_BODY_VISIBLE_FRACTION
                    ? "The athlete wasn't clearly visible — check the camera position"
                    : "No complete movement was detected");
            return new SessionVerdict(Status.NO_REPS, reasons);
        }

        Status status = Status.VERIFIED;

        if (counts.tooFastReps > 0) {
            status = Status.FLAGGED;
            reasons.add(plural(counts.tooFastReps, "rep was", "reps were")
                    + " faster than humanly plausible — the video may be sped up");
        }
        if (visibleFraction < MIN_BODY_VISIBLE_FRACTION) {
            status = worse(status, Status.NEEDS_REVIEW);
            reasons.add(String.format(java.util.Locale.US,
                    "Athlete was out of frame for %d%% of the video", Math.round((1 - visibleFraction) * 100)));
        }
        int weak = counts.partialReps + counts.badFormReps;
        if (weak * 2 > total) {
            status = worse(status, Status.NEEDS_REVIEW);
            reasons.add(weak + " of " + total + " attempts were partial or had form issues");
        }

        if (status == Status.VERIFIED) {
            reasons.add("Full range of motion and plausible speed on every counted rep");
            if (counts.partialReps > 0) {
                reasons.add(plural(counts.partialReps, "partial rep was", "partial reps were") + " not counted");
            }
        }
        return new SessionVerdict(status, reasons);
    }

    private static String plural(int n, String one, String many) {
        return n + " " + (n == 1 ? one : many);
    }

    public String reasonsText() {
        return String.join("\n", reasons);
    }
}

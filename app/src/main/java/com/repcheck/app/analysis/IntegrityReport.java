package com.repcheck.app.analysis;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Findings about the video file itself (as opposed to the athlete's performance):
 * gaps, edits, a swapped file, a different person appearing partway.
 */
public final class IntegrityReport {

    public static final class Issue {
        public final SessionVerdict.Status severity; // NEEDS_REVIEW or FLAGGED
        public final String message;

        public Issue(SessionVerdict.Status severity, String message) {
            this.severity = severity;
            this.message = message;
        }
    }

    private final List<Issue> issues = new ArrayList<>();

    public void add(SessionVerdict.Status severity, String message) {
        issues.add(new Issue(severity, message));
    }

    public void addAll(IntegrityReport other) {
        issues.addAll(other.issues);
    }

    public List<Issue> issues() {
        return Collections.unmodifiableList(issues);
    }

    public boolean isClean() {
        return issues.isEmpty();
    }

    /** The most serious severity found, or VERIFIED if nothing was found. */
    public SessionVerdict.Status status() {
        SessionVerdict.Status s = SessionVerdict.Status.VERIFIED;
        for (Issue i : issues) s = SessionVerdict.worse(s, i.severity);
        return s;
    }

    /**
     * Combines the performance verdict with these findings: the result is the more serious of the
     * two, and every integrity finding is listed as a reason. NO_REPS stays NO_REPS.
     */
    public SessionVerdict applyTo(SessionVerdict verdict) {
        List<String> reasons = new ArrayList<>(verdict.reasons);
        SessionVerdict.Status status = verdict.status;
        if (status != SessionVerdict.Status.NO_REPS) status = SessionVerdict.worse(status, status());
        for (Issue i : issues) reasons.add(i.message);
        if (issues.isEmpty() && status == SessionVerdict.Status.VERIFIED) {
            reasons.add("Video timing and continuity checks passed");
        }
        return SessionVerdict.of(status, reasons);
    }

    /** One line per finding, for the breakdown shown to officials. */
    public String summary() {
        if (issues.isEmpty()) return "Integrity: no gaps, edits or changes of person found";
        StringBuilder sb = new StringBuilder("Integrity:");
        for (Issue i : issues) sb.append("\n  ").append(i.severity == SessionVerdict.Status.FLAGGED ? "✕ " : "⚠ ")
                .append(i.message);
        return sb.toString();
    }
}

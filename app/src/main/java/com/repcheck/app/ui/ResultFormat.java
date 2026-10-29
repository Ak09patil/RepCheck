package com.repcheck.app.ui;

import android.graphics.Color;

import com.repcheck.app.analysis.SessionVerdict;
import com.repcheck.app.data.TestResult;
import com.repcheck.app.exercise.ExerciseType;

import com.repcheck.app.progress.Badges;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/** Turns stored values into on-screen text and colours, so every screen shows them the same way. */
final class ResultFormat {
    private ResultFormat() {}

    static String exerciseName(String exercise) {
        try {
            return ExerciseType.valueOf(exercise).displayName;
        } catch (IllegalArgumentException e) {
            return exercise;
        }
    }

    static String score(TestResult r) {
        return number(r.scoreValue) + " " + r.scoreUnit;
    }

    /** Whole numbers without decimals (12), others to one decimal (42.5). */
    static String number(double v) {
        return v == Math.rint(v)
                ? String.valueOf((long) v)
                : String.format(java.util.Locale.US, "%.1f", v);
    }

    static String date(long millis) {
        return DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(new Date(millis));
    }

    static String statusLabel(String status) {
        switch (parse(status)) {
            case VERIFIED: return "✓ Verified";
            case NEEDS_REVIEW: return "⚠ Needs review";
            case FLAGGED: return "✕ Flagged";
            default: return "No reps detected";
        }
    }

    /** Needs work (red) → Excellent (green). */
    static int ratingColor(int level) {
        switch (level) {
            case 5: return Color.parseColor("#15803D");
            case 4: return Color.parseColor("#16A34A");
            case 3: return Color.parseColor("#2563EB");
            case 2: return Color.parseColor("#D97706");
            case 1: return Color.parseColor("#DC2626");
            default: return Color.parseColor("#6B7280");
        }
    }

    /** Converts saved results into the plain objects the badge engine uses. */
    static List<Badges.Attempt> toAttempts(List<TestResult> results) {
        List<Badges.Attempt> out = new ArrayList<>(results.size());
        for (TestResult r : results) {
            ExerciseType type;
            try {
                type = ExerciseType.valueOf(r.exercise);
            } catch (IllegalArgumentException e) {
                continue;
            }
            out.add(new Badges.Attempt(type, SessionVerdict.Status.VERIFIED.name().equals(r.status),
                    r.scoreValue, r.ratingLevel, r.recordedAt));
        }
        return out;
    }

    static int statusColor(String status) {
        switch (parse(status)) {
            case VERIFIED: return Color.parseColor("#16A34A");
            case NEEDS_REVIEW: return Color.parseColor("#D97706");
            case FLAGGED: return Color.parseColor("#DC2626");
            default: return Color.parseColor("#6B7280");
        }
    }

    /** Unknown or missing values (e.g. from an older app version on the server) count as NO_REPS. */
    private static SessionVerdict.Status parse(String status) {
        try {
            return SessionVerdict.Status.valueOf(status);
        } catch (IllegalArgumentException | NullPointerException e) {
            return SessionVerdict.Status.NO_REPS;
        }
    }
}

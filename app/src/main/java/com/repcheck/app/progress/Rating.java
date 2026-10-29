package com.repcheck.app.progress;

import java.util.Locale;

/** Where one score sits against the benchmark for the athlete's age and gender. */
public final class Rating {

    public static final String[] LABELS = {"", "Needs work", "Below average", "Average", "Good", "Excellent"};

    /** 1 (Needs work) .. 5 (Excellent) */
    public final int level;
    /** e.g. "Male, 16–19" */
    public final String group;
    /** Amount still needed to reach the next level; 0 at Excellent. */
    public final double toNextLevel;
    public final String unit;
    public final String source;
    /** True until official SAI norms replace the placeholder table. */
    public final boolean provisional;

    Rating(int level, String group, double toNextLevel, String unit, String source, boolean provisional) {
        this.level = level;
        this.group = group;
        this.toNextLevel = toNextLevel;
        this.unit = unit;
        this.source = source;
        this.provisional = provisional;
    }

    public String label() {
        return label(level);
    }

    public static String label(int level) {
        return level >= 1 && level <= 5 ? LABELS[level] : "";
    }

    /** Instant feedback, e.g. "3 more reps to reach Good". Null at the top level. */
    public String nextStep() {
        if (level >= 5) return null;
        String amount = "reps".equals(unit)
                ? (int) Math.ceil(toNextLevel) + " more rep" + (Math.ceil(toNextLevel) == 1 ? "" : "s")
                : String.format(Locale.US, "%.1f %s higher", toNextLevel, unit);
        return amount + " to reach " + LABELS[level + 1];
    }
}

package com.repcheck.app.progress;

import com.repcheck.app.exercise.ExerciseType;

import java.util.ArrayList;
import java.util.List;

/**
 * Benchmark table: how a score compares with others of the same gender and age.
 *
 * ┌──────────────────────────────────────────────────────────────────────────────┐
 * │ PROVISIONAL DATA. Replace these rows with the official SAI norms             │
 * │ (Annexure A of the problem statement) when available. Nothing else in the    │
 * │ app needs to change: add rows with row(...) below.                           │
 * └──────────────────────────────────────────────────────────────────────────────┘
 *
 * Each row gives four cut-offs: the minimum score for Below average, Average, Good and Excellent.
 * Anything under the first cut-off is "Needs work".
 *
 * Sources used for the placeholder rows:
 *  - Sit-ups (30 s): Davis, B. et al. (2000) Physical Education and the Study of Sport, 4th ed.,
 *    Harcourt, p.124 — norms for ages 16–19 (via brianmac.co.uk). Applied to all ages for now.
 *  - Vertical jump: EUROFIT adult percentiles (20th/40th/60th/80th), Council of Europe
 *    (via marathonhandbook.com). Under-20s use the 20–29 band for now.
 *  - Push-ups and squats are not in the SAI battery, so they have no benchmark.
 */
public final class Benchmarks {
    private Benchmarks() {}

    private static final class Row {
        final ExerciseType test;
        final String gender;
        final int minAge, maxAge;
        final double[] cutoffs;
        final String groupLabel;
        final String source;

        Row(ExerciseType test, String gender, int minAge, int maxAge, double[] cutoffs,
            String groupLabel, String source) {
            this.test = test;
            this.gender = gender;
            this.minAge = minAge;
            this.maxAge = maxAge;
            this.cutoffs = cutoffs;
            this.groupLabel = groupLabel;
            this.source = source;
        }
    }

    static final String MALE = "MALE", FEMALE = "FEMALE";
    private static final String SIT_UP_SRC = "Davis et al. (2000), ages 16–19, 30-second sit-up test";
    private static final String JUMP_SRC = "EUROFIT adult percentiles (Council of Europe)";
    private static final boolean PROVISIONAL = true;

    private static final List<Row> ROWS = new ArrayList<>();

    static {
        // ----- Sit-ups, 30 seconds -----
        row(ExerciseType.SIT_UP, MALE, 0, 120, 17, 20, 26, 31, SIT_UP_SRC);
        row(ExerciseType.SIT_UP, FEMALE, 0, 120, 9, 15, 21, 26, SIT_UP_SRC);

        // ----- Vertical jump, cm (20th / 40th / 60th / 80th percentile) -----
        row(ExerciseType.VERTICAL_JUMP, MALE, 0, 29, 47, 52, 55, 61, JUMP_SRC);
        row(ExerciseType.VERTICAL_JUMP, MALE, 30, 39, 42, 47, 51, 55, JUMP_SRC);
        row(ExerciseType.VERTICAL_JUMP, MALE, 40, 49, 38, 42, 47, 52, JUMP_SRC);
        row(ExerciseType.VERTICAL_JUMP, MALE, 50, 59, 31, 37, 42, 45, JUMP_SRC);
        row(ExerciseType.VERTICAL_JUMP, MALE, 60, 120, 24, 31, 35, 39, JUMP_SRC);
        row(ExerciseType.VERTICAL_JUMP, FEMALE, 0, 29, 31, 36, 39, 43, JUMP_SRC);
        row(ExerciseType.VERTICAL_JUMP, FEMALE, 30, 39, 29, 32, 35, 40, JUMP_SRC);
        row(ExerciseType.VERTICAL_JUMP, FEMALE, 40, 49, 25, 28, 31, 36, JUMP_SRC);
        row(ExerciseType.VERTICAL_JUMP, FEMALE, 50, 59, 21, 25, 27, 30, JUMP_SRC);
        row(ExerciseType.VERTICAL_JUMP, FEMALE, 60, 120, 17, 20, 23, 26, JUMP_SRC);
    }

    private static void row(ExerciseType test, String gender, int minAge, int maxAge,
                            double belowAvg, double avg, double good, double excellent, String source) {
        String g = MALE.equals(gender) ? "Male" : "Female";
        String ages = maxAge >= 120 ? (minAge == 0 ? "all ages" : minAge + "+")
                : (minAge == 0 ? "up to " + maxAge : minAge + "–" + maxAge);
        ROWS.add(new Row(test, gender, minAge, maxAge, new double[]{belowAvg, avg, good, excellent},
                g + ", " + ages, source));
    }

    /** @return the rating, or null if there's no benchmark for this test / gender / age */
    public static Rating rate(ExerciseType test, String gender, int age, double score) {
        for (Row r : ROWS) {
            if (r.test != test || !r.gender.equals(gender) || age < r.minAge || age > r.maxAge) continue;
            int level = 1;
            for (double c : r.cutoffs) if (score >= c) level++;
            double toNext = level >= 5 ? 0 : r.cutoffs[level - 1] - score;
            String unit = test.kind == ExerciseType.Kind.JUMP ? "cm" : "reps";
            return new Rating(level, r.groupLabel, toNext, unit, r.source, PROVISIONAL);
        }
        return null;
    }

    /**
     * The four cut-offs (Below average, Average, Good, Excellent) for this group, or null.
     * Used to draw benchmark lines on the progress chart.
     */
    public static double[] cutoffs(ExerciseType test, String gender, int age) {
        for (Row r : ROWS) {
            if (r.test == test && r.gender.equals(gender) && age >= r.minAge && age <= r.maxAge) {
                return r.cutoffs.clone();
            }
        }
        return null;
    }

    public static boolean hasBenchmark(ExerciseType test) {
        for (Row r : ROWS) if (r.test == test) return true;
        return false;
    }
}

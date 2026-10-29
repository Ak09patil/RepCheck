package com.repcheck.app.progress;

import com.repcheck.app.exercise.ExerciseType;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Gamification: badges earned from an athlete's attempt history.
 * Only VERIFIED attempts count, so badges can't be farmed with flagged videos.
 * Plain Java (no Android), so it's unit-tested.
 */
public final class Badges {
    private Badges() {}

    /** The minimum an attempt needs for badge purposes. */
    public static final class Attempt {
        public final ExerciseType test;
        public final boolean verified;
        public final double score;
        /** 0 = no benchmark */
        public final int ratingLevel;
        public final long recordedAt;

        public Attempt(ExerciseType test, boolean verified, double score, int ratingLevel, long recordedAt) {
            this.test = test;
            this.verified = verified;
            this.score = score;
            this.ratingLevel = ratingLevel;
            this.recordedAt = recordedAt;
        }
    }

    public static final class Badge {
        public final String id;
        public final String emoji;
        public final String title;
        public final String description;
        public final boolean earned;

        Badge(String id, String emoji, String title, String description, boolean earned) {
            this.id = id;
            this.emoji = emoji;
            this.title = title;
            this.description = description;
            this.earned = earned;
        }
    }

    /** The SAI tests this app can measure; earning a verified result in each gives All-rounder. */
    static final ExerciseType[] SAI_TESTS = {ExerciseType.SIT_UP, ExerciseType.VERTICAL_JUMP};

    /** Every badge, earned or not, in display order. Attempts may be in any order. */
    public static List<Badge> evaluate(List<Attempt> attempts) {
        List<Attempt> sorted = new ArrayList<>(attempts);
        sorted.sort((a, b) -> Long.compare(a.recordedAt, b.recordedAt));

        int verified = 0;
        int bestRating = 0;
        boolean personalBest = false;
        int cleanStreak = 0, bestCleanStreak = 0;
        double bestSitUps = 0, bestJump = 0;
        Set<ExerciseType> testsVerified = new HashSet<>();
        Set<Long> days = new HashSet<>();
        Map<ExerciseType, Double> best = new HashMap<>();

        for (Attempt a : sorted) {
            days.add(dayOf(a.recordedAt));
            if (!a.verified) {
                cleanStreak = 0;
                continue;
            }
            verified++;
            cleanStreak++;
            bestCleanStreak = Math.max(bestCleanStreak, cleanStreak);
            testsVerified.add(a.test);
            bestRating = Math.max(bestRating, a.ratingLevel);

            Double previous = best.get(a.test);
            if (previous != null && a.score > previous) personalBest = true;
            if (previous == null || a.score > previous) best.put(a.test, a.score);

            if (a.test == ExerciseType.SIT_UP) bestSitUps = Math.max(bestSitUps, a.score);
            if (a.test == ExerciseType.VERTICAL_JUMP) bestJump = Math.max(bestJump, a.score);
        }

        boolean allRounder = true;
        for (ExerciseType t : SAI_TESTS) allRounder &= testsVerified.contains(t);

        List<Badge> badges = new ArrayList<>();
        badges.add(new Badge("first_step", "👟", "First step", "Complete your first verified test", verified >= 1));
        badges.add(new Badge("all_rounder", "🎯", "All-rounder", "Get a verified result in every SAI test", allRounder));
        badges.add(new Badge("personal_best", "📈", "Personal best", "Beat your own best score in any test", personalBest));
        badges.add(new Badge("clean_sheet", "🛡️", "Clean sheet", "3 verified attempts in a row", bestCleanStreak >= 3));
        badges.add(new Badge("regular", "📅", "Regular", "Test on 3 different days", days.size() >= 3));
        badges.add(new Badge("rated_good", "⭐", "Above the bar", "Reach a Good rating in any test", bestRating >= 4));
        badges.add(new Badge("rated_excellent", "🏆", "Excellent", "Reach an Excellent rating in any test", bestRating >= 5));
        badges.add(new Badge("core", "💪", "Core of steel", "25 sit-ups in 30 seconds", bestSitUps >= 25));
        badges.add(new Badge("sky_high", "🚀", "Sky high", "Jump 40 cm or higher", bestJump >= 40));
        return badges;
    }

    /** Badges earned in 'after' that weren't earned in 'before'. */
    public static List<Badge> newlyEarned(List<Badge> before, List<Badge> after) {
        Set<String> had = new HashSet<>();
        for (Badge b : before) if (b.earned) had.add(b.id);
        List<Badge> fresh = new ArrayList<>();
        for (Badge b : after) if (b.earned && !had.contains(b.id)) fresh.add(b);
        return fresh;
    }

    public static int earnedCount(List<Badge> badges) {
        int n = 0;
        for (Badge b : badges) if (b.earned) n++;
        return n;
    }

    private static long dayOf(long millis) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(millis);
        return c.get(Calendar.YEAR) * 1000L + c.get(Calendar.DAY_OF_YEAR);
    }
}

package com.repcheck.app.progress;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.repcheck.app.exercise.ExerciseType;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class ProgressTest {

    private static final long DAY = 24L * 60 * 60 * 1000;
    private static final long T0 = 1_760_000_000_000L;

    // ---------- Benchmarks ----------

    @Test public void sitUpBandsMatchTheSource() {
        assertEquals(1, Benchmarks.rate(ExerciseType.SIT_UP, "MALE", 17, 16).level);
        assertEquals(2, Benchmarks.rate(ExerciseType.SIT_UP, "MALE", 17, 17).level);
        assertEquals(3, Benchmarks.rate(ExerciseType.SIT_UP, "MALE", 17, 25).level);
        assertEquals(4, Benchmarks.rate(ExerciseType.SIT_UP, "MALE", 17, 26).level);
        assertEquals(5, Benchmarks.rate(ExerciseType.SIT_UP, "MALE", 17, 31).level);
        assertEquals(4, Benchmarks.rate(ExerciseType.SIT_UP, "FEMALE", 17, 22).level);
    }

    @Test public void nextStepTellsYouTheGap() {
        Rating r = Benchmarks.rate(ExerciseType.SIT_UP, "MALE", 17, 23);
        assertEquals("Average", r.label());
        assertEquals("3 more reps to reach Good", r.nextStep());
        assertEquals("1 more rep to reach Good", Benchmarks.rate(ExerciseType.SIT_UP, "MALE", 17, 25).nextStep());
        assertNull(Benchmarks.rate(ExerciseType.SIT_UP, "MALE", 17, 40).nextStep());
    }

    @Test public void jumpUsesAgeBands() {
        // 50 cm: Below average for a 25-year-old man, Good for a 45-year-old
        assertEquals(2, Benchmarks.rate(ExerciseType.VERTICAL_JUMP, "MALE", 25, 50).level);
        assertEquals(4, Benchmarks.rate(ExerciseType.VERTICAL_JUMP, "MALE", 45, 50).level);
        assertEquals("2.0 cm higher to reach Average",
                Benchmarks.rate(ExerciseType.VERTICAL_JUMP, "MALE", 25, 50).nextStep());
    }

    @Test public void noBenchmarkWhereThereIsNoData() {
        assertNull(Benchmarks.rate(ExerciseType.PUSH_UP, "MALE", 20, 30));
        assertNull(Benchmarks.rate(ExerciseType.SIT_UP, "OTHER", 20, 30));
        assertFalse(Benchmarks.hasBenchmark(ExerciseType.SQUAT));
    }

    // ---------- Badges ----------

    private static boolean earned(List<Badges.Badge> badges, String id) {
        for (Badges.Badge b : badges) if (b.id.equals(id)) return b.earned;
        throw new AssertionError("no badge " + id);
    }

    @Test public void nothingEarnedAtStart() {
        assertEquals(0, Badges.earnedCount(Badges.evaluate(new ArrayList<>())));
    }

    @Test public void flaggedAttemptsEarnNothing() {
        List<Badges.Attempt> a = new ArrayList<>();
        a.add(new Badges.Attempt(ExerciseType.VERTICAL_JUMP, false, 80, 5, T0));
        List<Badges.Badge> b = Badges.evaluate(a);
        assertFalse(earned(b, "first_step"));
        assertFalse(earned(b, "sky_high"));
        assertFalse(earned(b, "rated_excellent"));
    }

    @Test public void typicalHistory() {
        List<Badges.Attempt> a = new ArrayList<>();
        a.add(new Badges.Attempt(ExerciseType.SIT_UP, true, 20, 3, T0));
        a.add(new Badges.Attempt(ExerciseType.SIT_UP, true, 26, 4, T0 + DAY));
        a.add(new Badges.Attempt(ExerciseType.VERTICAL_JUMP, true, 41, 1, T0 + 2 * DAY));
        List<Badges.Badge> b = Badges.evaluate(a);
        assertTrue(earned(b, "first_step"));
        assertTrue(earned(b, "all_rounder"));
        assertTrue(earned(b, "personal_best"));
        assertTrue(earned(b, "clean_sheet"));
        assertTrue(earned(b, "regular"));
        assertTrue(earned(b, "rated_good"));
        assertFalse(earned(b, "rated_excellent"));
        assertTrue(earned(b, "core"));
        assertTrue(earned(b, "sky_high"));
    }

    @Test public void aFlagBreaksTheStreak() {
        List<Badges.Attempt> a = new ArrayList<>();
        a.add(new Badges.Attempt(ExerciseType.SIT_UP, true, 20, 3, T0));
        a.add(new Badges.Attempt(ExerciseType.SIT_UP, true, 20, 3, T0 + 1));
        a.add(new Badges.Attempt(ExerciseType.SIT_UP, false, 20, 3, T0 + 2));
        a.add(new Badges.Attempt(ExerciseType.SIT_UP, true, 20, 3, T0 + 3));
        assertFalse(earned(Badges.evaluate(a), "clean_sheet"));
        assertFalse("equal score is not a personal best", earned(Badges.evaluate(a), "personal_best"));
    }

    @Test public void newlyEarnedFindsOnlyFreshBadges() {
        List<Badges.Attempt> a = new ArrayList<>();
        a.add(new Badges.Attempt(ExerciseType.SIT_UP, true, 20, 3, T0));
        List<Badges.Badge> before = Badges.evaluate(a);
        a.add(new Badges.Attempt(ExerciseType.SIT_UP, true, 27, 4, T0 + DAY));
        List<Badges.Badge> fresh = Badges.newlyEarned(before, Badges.evaluate(a));
        List<String> ids = new ArrayList<>();
        for (Badges.Badge b : fresh) ids.add(b.id);
        assertTrue(ids.contains("personal_best"));
        assertTrue(ids.contains("rated_good"));
        assertTrue(ids.contains("core"));
        assertFalse(ids.contains("first_step"));
    }
}

package com.repcheck.app.cloud;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import com.repcheck.app.data.Athlete;
import com.repcheck.app.data.TestResult;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class LeaderboardMapperTest {

    private static TestResult r(String exercise, double score, String status, boolean synced, long at) {
        TestResult t = new TestResult();
        t.exercise = exercise;
        t.scoreValue = score;
        t.scoreUnit = "reps";
        t.status = status;
        t.synced = synced;
        t.recordedAt = at;
        t.videoSha256 = Long.toHexString(at) + "x";
        return t;
    }

    @Test public void displayNameShowsOnlyFirstNameAndInitial() {
        assertEquals("Akanksha P.", LeaderboardMapper.displayName("Akanksha Prakash Patil"));
        assertEquals("ravi K.", LeaderboardMapper.displayName("  ravi   kumar "));
        assertEquals("Mary", LeaderboardMapper.displayName("Mary"));
        assertEquals("", LeaderboardMapper.displayName("   "));
    }

    @Test public void ageGroups() {
        assertEquals("Under 14", LeaderboardMapper.ageGroup(12));
        assertEquals("14–17", LeaderboardMapper.ageGroup(14));
        assertEquals("14–17", LeaderboardMapper.ageGroup(17));
        assertEquals("18–24", LeaderboardMapper.ageGroup(20));
        assertEquals("35+", LeaderboardMapper.ageGroup(50));
    }

    @Test public void onlyVerifiedUploadedResultsQualifyBestFirst() {
        List<TestResult> all = new ArrayList<>();
        all.add(r("SIT_UP", 22, "VERIFIED", true, 1));
        all.add(r("SIT_UP", 30, "FLAGGED", true, 2));      // flagged: never
        all.add(r("SIT_UP", 28, "VERIFIED", false, 3));    // not uploaded yet: rules would refuse
        all.add(r("SIT_UP", 25, "VERIFIED", true, 4));
        all.add(r("SIT_UP", 25, "VERIFIED", true, 5));     // tie: earlier one first
        all.add(r("VERTICAL_JUMP", 40, "VERIFIED", true, 6));
        List<TestResult> c = LeaderboardMapper.candidates(all, "SIT_UP");
        assertEquals(3, c.size());
        assertEquals(25, c.get(0).scoreValue, 0);
        assertEquals(4, c.get(0).recordedAt);
        assertEquals(22, c.get(2).scoreValue, 0);
    }

    @Test public void competitionRanking() {
        assertArrayEquals(new int[]{1, 2, 2, 4, 5}, LeaderboardMapper.ranks(new double[]{30, 25, 25, 20, 10}));
        assertArrayEquals(new int[0], LeaderboardMapper.ranks(new double[0]));
    }

    @Test public void entryHasNoPrivateDetails() {
        Athlete a = new Athlete();
        a.name = "Akanksha Patil";
        a.gender = Athlete.FEMALE;
        a.dobMillis = 1_000_000_000_000L;
        a.heightCm = 160;
        a.weightKg = 50;
        a.state = "Telangana";
        a.district = "Hyderabad";
        TestResult best = r("SIT_UP", 25, "VERIFIED", true, 1_760_000_000_000L);
        Map<String, Object> e = LeaderboardMapper.entry(best, a, "uid1");
        assertEquals("Akanksha P.", e.get("displayName"));
        assertEquals(best.videoSha256, e.get("submissionId"));
        assertEquals(25.0, (Double) e.get("bestScore"), 0);
        for (String key : new String[]{"dobMillis", "heightCm", "weightKg", "name"}) {
            assertFalse(key, e.containsKey(key));
        }
    }
}

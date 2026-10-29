package com.repcheck.app.cloud;

import com.repcheck.app.data.Athlete;
import com.repcheck.app.data.TestResult;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Leaderboard entries: one per athlete per test, holding their best VERIFIED score.
 *
 * Firestore: leaderboards/{EXERCISE}/entries/{uid}
 * Putting the test in the path means the board is one simple "order by score" query,
 * which needs no custom index.
 *
 * Privacy: athletes opt in, and only first name + last initial, district/state, gender and age
 * group are shown, never date of birth, height or weight. Many athletes will be under 18.
 *
 * Trust: each entry names the submission it came from, and the security rules reject the write
 * unless that submission belongs to the athlete, is VERIFIED, has exactly this score and hasn't
 * been rejected by an official. A modified app can't post a made-up score.
 *
 * Plain Java (no Firebase), so it's unit-tested.
 */
public final class LeaderboardMapper {
    private LeaderboardMapper() {}

    public static final String LEADERBOARDS = "leaderboards";
    public static final String ENTRIES = "entries";

    /** "Akanksha Patil" → "Akanksha P." ; single names are kept as they are. */
    public static String displayName(String fullName) {
        if (fullName == null) return "";
        String[] parts = fullName.trim().split("\\s+");
        if (parts.length == 0 || parts[0].isEmpty()) return "";
        if (parts.length == 1) return parts[0];
        String last = parts[parts.length - 1];
        return parts[0] + " " + Character.toUpperCase(last.charAt(0)) + ".";
    }

    /** Age bands used to compare like with like on the board. */
    public static String ageGroup(int age) {
        if (age < 14) return "Under 14";
        if (age <= 17) return "14–17";
        if (age <= 24) return "18–24";
        if (age <= 34) return "25–34";
        return "35+";
    }

    /**
     * Verified, already-uploaded results for one test, best score first.
     * The caller tries them in order, because an official may have rejected the best one.
     */
    public static List<TestResult> candidates(List<TestResult> results, String exercise) {
        List<TestResult> out = new ArrayList<>();
        for (TestResult r : results) {
            if (exercise.equals(r.exercise) && "VERIFIED".equals(r.status) && r.synced
                    && r.videoSha256 != null && !r.videoSha256.isEmpty()) {
                out.add(r);
            }
        }
        out.sort((a, b) -> {
            int byScore = Double.compare(b.scoreValue, a.scoreValue);
            return byScore != 0 ? byScore : Long.compare(a.recordedAt, b.recordedAt); // earlier wins a tie
        });
        return out;
    }

    /**
     * Standard competition ranking for scores already sorted best-first: equal scores share a rank
     * and the next rank skips ahead (1, 2, 2, 4).
     */
    public static int[] ranks(double[] sortedBestFirst) {
        int[] r = new int[sortedBestFirst.length];
        for (int i = 0; i < r.length; i++) {
            r[i] = (i > 0 && sortedBestFirst[i] == sortedBestFirst[i - 1]) ? r[i - 1] : i + 1;
        }
        return r;
    }

    public static Map<String, Object> entry(TestResult best, Athlete a, String uid) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("athleteUid", uid);
        m.put("exercise", best.exercise);
        m.put("displayName", displayName(a.name));
        m.put("gender", a.gender);
        m.put("ageGroup", ageGroup(a.ageOn(best.recordedAt)));
        m.put("state", a.state == null ? "" : a.state);
        m.put("district", a.district == null ? "" : a.district);
        m.put("bestScore", best.scoreValue);
        m.put("unit", best.scoreUnit);
        m.put("ratingLevel", best.ratingLevel);
        m.put("submissionId", best.videoSha256);
        m.put("recordedAt", best.recordedAt);
        return m;
    }
}

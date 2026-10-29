package com.repcheck.app.cloud;

import com.repcheck.app.data.Athlete;
import com.repcheck.app.data.TestResult;
import com.repcheck.app.exercise.ExerciseType;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Turns local records into the documents stored in Firestore.
 * No Firebase classes here, so the mapping is unit-tested; SyncWorker adds server timestamps.
 *
 * Firestore layout:
 *   users/{uid}            { role: "athlete" | "official", email }
 *   athletes/{uid}         profile (this class: profile())
 *   submissions/{sha256}   one verified-or-not attempt (this class: submission())
 *
 * Submissions use the video's SHA-256 as their document id, so uploading the same attempt
 * twice can never create a duplicate, and the id itself is the video's fingerprint.
 * Submissions are a flat top-level collection with the athlete's details copied in, so the
 * officials' list is one simple query (no joins, no custom indexes).
 */
public final class SubmissionMapper {
    private SubmissionMapper() {}

    public static final String USERS = "users";
    public static final String ATHLETES = "athletes";
    public static final String SUBMISSIONS = "submissions";

    public static final String ROLE_ATHLETE = "athlete";
    public static final String ROLE_OFFICIAL = "official";

    public static Map<String, Object> profile(Athlete a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", a.name);
        m.put("dobMillis", a.dobMillis);
        m.put("gender", a.gender);
        m.put("heightCm", a.heightCm);
        m.put("weightKg", a.weightKg);
        m.put("state", nullToEmpty(a.state));
        m.put("district", nullToEmpty(a.district));
        return m;
    }

    /** Everything an official needs to judge the attempt. The local video path is deliberately left out. */
    public static Map<String, Object> submission(TestResult r, Athlete a, String uid, String appVersion) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("athleteUid", uid);
        m.put("athleteName", a.name);
        m.put("gender", a.gender);
        m.put("ageAtTest", a.ageOn(r.recordedAt));
        m.put("heightCm", a.heightCm);
        m.put("weightKg", a.weightKg);
        m.put("state", nullToEmpty(a.state));
        m.put("district", nullToEmpty(a.district));

        m.put("exercise", r.exercise);
        m.put("exerciseName", displayName(r.exercise));
        m.put("recordedAt", r.recordedAt);
        m.put("score", r.scoreValue);
        m.put("unit", r.scoreUnit);
        m.put("status", r.status);
        m.put("reasons", nullToEmpty(r.reasons));
        m.put("details", nullToEmpty(r.details));
        m.put("ratingLevel", r.ratingLevel);

        m.put("validReps", r.validReps);
        m.put("partialReps", r.partialReps);
        m.put("badFormReps", r.badFormReps);
        m.put("tooFastReps", r.tooFastReps);
        m.put("framesAnalysed", r.framesAnalysed);
        m.put("framesWithBody", r.framesWithBody);
        m.put("videoFps", r.videoFps);
        m.put("videoDurationMs", r.videoDurationMs);
        m.put("videoSha256", r.videoSha256);
        m.put("appVersion", appVersion);
        return m;
    }

    private static String displayName(String exercise) {
        try {
            return ExerciseType.valueOf(exercise).displayName;
        } catch (IllegalArgumentException e) {
            return exercise;
        }
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}

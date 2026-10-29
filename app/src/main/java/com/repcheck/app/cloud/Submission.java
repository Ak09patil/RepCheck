package com.repcheck.app.cloud;

import com.google.firebase.Timestamp;
import com.google.firebase.firestore.DocumentSnapshot;

import java.util.Map;

/** One submission as the officials' screen sees it (read from Firestore). */
public final class Submission {

    public static final String APPROVED = "APPROVED";
    public static final String REJECTED = "REJECTED";

    /** Device clock more than this far ahead of the server suggests the date was faked. */
    static final long CLOCK_TOLERANCE_MS = 10 * 60 * 1000;

    public String id;
    public String athleteUid;
    public String athleteName, gender, state, district;
    public long ageAtTest;
    public double heightCm, weightKg;
    public String exercise, exerciseName, unit, status, reasons, details;
    public double score, videoFps;
    public long ratingLevel, framesAnalysed, framesWithBody, recordedAt;
    public Long receivedAt; // null until the server has stamped it
    public String videoSha256;
    public String reviewDecision, reviewNote, reviewByEmail;

    public static Submission from(DocumentSnapshot d) {
        Submission s = new Submission();
        s.id = d.getId();
        s.athleteUid = str(d, "athleteUid");
        s.athleteName = str(d, "athleteName");
        s.gender = str(d, "gender");
        s.state = str(d, "state");
        s.district = str(d, "district");
        s.ageAtTest = num(d, "ageAtTest");
        s.heightCm = dbl(d, "heightCm");
        s.weightKg = dbl(d, "weightKg");
        s.exercise = str(d, "exercise");
        s.exerciseName = str(d, "exerciseName");
        s.unit = str(d, "unit");
        s.status = str(d, "status");
        s.reasons = str(d, "reasons");
        s.details = str(d, "details");
        s.score = dbl(d, "score");
        s.videoFps = dbl(d, "videoFps");
        s.ratingLevel = num(d, "ratingLevel");
        s.framesAnalysed = num(d, "framesAnalysed");
        s.framesWithBody = num(d, "framesWithBody");
        s.recordedAt = num(d, "recordedAt");
        Timestamp received = d.getTimestamp("receivedAt");
        s.receivedAt = received == null ? null : received.toDate().getTime();
        s.videoSha256 = str(d, "videoSha256");

        Object review = d.get("review");
        if (review instanceof Map) {
            Map<?, ?> r = (Map<?, ?>) review;
            s.reviewDecision = asString(r.get("decision"));
            s.reviewNote = asString(r.get("note"));
            s.reviewByEmail = asString(r.get("byEmail"));
        }
        return s;
    }

    public boolean isReviewed() {
        return reviewDecision != null;
    }

    /** The phone's date was set ahead of the real date when the test was recorded (or uploaded). */
    public boolean clockSuspicious() {
        return receivedAt != null && recordedAt - receivedAt > CLOCK_TOLERANCE_MS;
    }

    private static String str(DocumentSnapshot d, String key) {
        String v = d.getString(key);
        return v == null ? "" : v;
    }

    private static long num(DocumentSnapshot d, String key) {
        Long v = d.getLong(key);
        return v == null ? 0 : v;
    }

    private static double dbl(DocumentSnapshot d, String key) {
        Double v = d.getDouble(key);
        return v == null ? 0 : v;
    }

    private static String asString(Object o) {
        return o == null ? null : o.toString();
    }
}

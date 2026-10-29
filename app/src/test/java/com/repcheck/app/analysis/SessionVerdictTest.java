package com.repcheck.app.analysis;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.repcheck.app.exercise.RepCounter;

import org.junit.Test;

public class SessionVerdictTest {

    private static RepCounter.FrameResult counts(int valid, int partial, int badForm, int tooFast) {
        return new RepCounter.FrameResult(true, RepCounter.Phase.EXTENDED, 170, "", null,
                valid, partial, badForm, tooFast);
    }

    @Test public void cleanSessionIsVerified() {
        SessionVerdict v = SessionVerdict.judge(counts(20, 1, 0, 0), 450, 440);
        assertEquals(SessionVerdict.Status.VERIFIED, v.status);
        assertTrue(v.reasonsText().contains("1 partial rep was not counted"));
    }

    @Test public void anyImpossiblyFastRepIsFlagged() {
        SessionVerdict v = SessionVerdict.judge(counts(20, 0, 0, 1), 450, 440);
        assertEquals(SessionVerdict.Status.FLAGGED, v.status);
    }

    @Test public void flaggedBeatsNeedsReview() {
        SessionVerdict v = SessionVerdict.judge(counts(1, 5, 0, 2), 450, 100);
        assertEquals(SessionVerdict.Status.FLAGGED, v.status);
        assertEquals(3, v.reasons.size());
    }

    @Test public void outOfFrameNeedsReview() {
        SessionVerdict v = SessionVerdict.judge(counts(10, 0, 0, 0), 450, 200);
        assertEquals(SessionVerdict.Status.NEEDS_REVIEW, v.status);
        assertTrue(v.reasonsText().contains("out of frame for 56%"));
    }

    @Test public void mostlyPoorRepsNeedReview() {
        SessionVerdict v = SessionVerdict.judge(counts(3, 2, 2, 0), 450, 450);
        assertEquals(SessionVerdict.Status.NEEDS_REVIEW, v.status);
    }

    @Test public void nothingDetected() {
        assertEquals(SessionVerdict.Status.NO_REPS, SessionVerdict.judge(counts(0, 0, 0, 0), 450, 450).status);
        assertTrue(SessionVerdict.judge(counts(0, 0, 0, 0), 450, 10).reasonsText().contains("visible"));
    }
}

package com.repcheck.app.analysis;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.repcheck.app.exercise.RepCounter;
import com.repcheck.app.pose.BodySide;
import com.repcheck.app.pose.PosePoint;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class IntegrityTest {

    private final Random noise = new Random(7);

    // ---------- Frame timing ----------

    /** Timestamps for a camera clip: 30 fps with ±2 ms jitter, plus the odd dropped frame. */
    private long[] cameraClip(double seconds) {
        List<Long> t = new ArrayList<>();
        for (int i = 0; i < seconds * 30; i++) {
            if (i % 97 == 50) continue; // occasional dropped frame
            t.add(Math.round(i * 33_333.3 + noise.nextGaussian() * 2000));
        }
        return t.stream().mapToLong(Long::longValue).toArray();
    }

    @Test public void genuineClipPasses() {
        IntegrityReport r = FrameTiming.check(cameraClip(30), 30_400);
        assertTrue(r.summary(), r.isClean());
    }

    @Test public void cutOutSectionIsAGap() {
        long[] t = cameraClip(30);
        for (int i = 300; i < t.length; i++) t[i] += 1_200_000; // 1.2 s with no frames after frame 300
        IntegrityReport r = FrameTiming.check(t, 31_600);
        assertEquals(SessionVerdict.Status.NEEDS_REVIEW, r.status());
        assertTrue(r.summary().contains("gap"));
    }

    @Test public void joinedClipsShareTimestamps() {
        long[] t = cameraClip(10);
        t[150] = t[149];
        assertTrue(FrameTiming.check(t, 0).summary().contains("timestamp"));
    }

    @Test public void swappedFileFailsLengthCheck() {
        // app recorded for 30 s but the file being analysed is 18 s long
        IntegrityReport r = FrameTiming.check(cameraClip(18), 30_200);
        assertEquals(SessionVerdict.Status.FLAGGED, r.status());
    }

    @Test public void encoderStartupDelayIsTolerated() {
        assertTrue(FrameTiming.check(cameraClip(29.2), 30_300).isClean());
    }

    // ---------- Continuity ----------

    /** Side-on standing athlete; torso 100 px, leg (thigh + shin) given. */
    private List<PosePoint> body(double hipX, double hipY, double legPx) {
        List<PosePoint> p = new ArrayList<>();
        for (int i = 0; i < 33; i++) p.add(new PosePoint(0, 0, 0));
        for (BodySide s : BodySide.values()) {
            p.set(s.shoulder, pt(hipX, hipY - 100));
            p.set(s.hip, pt(hipX, hipY));
            p.set(s.knee, pt(hipX + 5, hipY + legPx / 2));
            p.set(s.ankle, pt(hipX, hipY + legPx));
        }
        return p;
    }

    private PosePoint pt(double x, double y) {
        return new PosePoint(x + noise.nextGaussian(), y + noise.nextGaussian(), 0.9);
    }

    @Test public void smoothMovementIsContinuous() {
        ContinuityTracker c = new ContinuityTracker();
        for (int i = 0; i < 300; i++) {
            // bobbing up and down 40 px, like squats, plus a jump's worth of speed at times
            c.feed(body(300, 400 + 40 * Math.sin(i / 3.0), 180), i * 33L);
        }
        assertTrue(c.report().summary(), c.report().isClean());
    }

    @Test public void cutToAnotherMomentIsASplice() {
        ContinuityTracker c = new ContinuityTracker();
        for (int i = 0; i < 100; i++) c.feed(body(300, 400, 180), i * 33L);
        for (int i = 100; i < 200; i++) c.feed(body(420, 380, 180), i * 33L); // teleports 120+ px and stays
        IntegrityReport r = c.report();
        assertEquals(SessionVerdict.Status.NEEDS_REVIEW, r.status());
        assertTrue(r.summary().contains("1 time"));
    }

    @Test public void oneFrameTrackingGlitchIsIgnored() {
        ContinuityTracker c = new ContinuityTracker();
        for (int i = 0; i < 200; i++) {
            c.feed(i == 100 ? body(600, 200, 180) : body(300, 400, 180), i * 33L);
        }
        assertTrue(c.report().summary(), c.report().isClean());
    }

    @Test public void differentPersonIsNoticed() {
        ContinuityTracker c = new ContinuityTracker();
        for (int i = 0; i < 100; i++) c.feed(body(300, 400, 180), i * 33L);   // torso/leg = 0.56
        for (int i = 100; i < 200; i++) c.feed(body(300, 400, 260), i * 33L);  // torso/leg = 0.38
        assertTrue(c.report().summary().contains("different person"));
    }

    // ---------- Combining with the performance verdict ----------

    @Test public void integrityIssueDowngradesAVerifiedResult() {
        RepCounter.FrameResult counts = new RepCounter.FrameResult(true, RepCounter.Phase.EXTENDED, 170, "",
                null, 20, 0, 0, 0);
        SessionVerdict v = SessionVerdict.judge(counts, 400, 400);
        assertEquals(SessionVerdict.Status.VERIFIED, v.status);

        IntegrityReport r = FrameTiming.check(cameraClip(18), 30_200);
        SessionVerdict merged = r.applyTo(v);
        assertEquals(SessionVerdict.Status.FLAGGED, merged.status);
        assertTrue(merged.reasonsText().contains("replaced"));

        SessionVerdict clean = new IntegrityReport().applyTo(v);
        assertEquals(SessionVerdict.Status.VERIFIED, clean.status);
        assertTrue(clean.reasonsText().contains("checks passed"));
    }
}

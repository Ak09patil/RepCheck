package com.repcheck.app.exercise;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.repcheck.app.analysis.SessionVerdict;
import com.repcheck.app.pose.BodySide;
import com.repcheck.app.pose.PosePoint;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Simulates a side-on video of a 170 cm athlete at 2 px/cm, with real projectile physics
 * and ±1 px landmark jitter, and checks what JumpDetector reports.
 */
public class JumpDetectorTest {

    private static final double STATURE = 170;
    private static final double PX_PER_CM = 2;
    private static final double GROUND = 600;   // y of the floor in pixels
    private static final double G = 981;        // cm/s²

    private final Random noise = new Random(42);
    private JumpDetector detector;
    private long ts;
    private double frameMs = 1000.0 / 30;
    private double clock; // fractional ms, so 33.3 ms frames don't drift

    // ---------- synthetic athlete ----------

    /**
     * @param liftCm     how far the whole body is off the ground
     * @param dipPx      countermovement: hips lowered, knees forward (feet stay down)
     * @param kneeOutPx  knee pushed forward while airborne (tuck)
     */
    private List<PosePoint> pose(double liftCm, double dipPx, double kneeOutPx) {
        List<PosePoint> p = new ArrayList<>();
        for (int i = 0; i < 33; i++) p.add(new PosePoint(0, 0, 0));
        double up = liftCm * PX_PER_CM;
        double toe = GROUND - up;
        double ankle = toe - 0.04 * STATURE * PX_PER_CM;
        double hip = toe - 0.50 * STATURE * PX_PER_CM + dipPx;
        double knee = (hip + ankle) / 2;
        double kneeX = 100 + kneeOutPx + dipPx * 1.5;
        double shoulder = toe - 0.82 * STATURE * PX_PER_CM + dipPx;

        for (BodySide s : BodySide.values()) {
            p.set(s.toe, pt(110, toe));
            p.set(s.heel, pt(95, toe - 4));
            p.set(s.ankle, pt(100, ankle));
            p.set(s.knee, pt(kneeX, knee));
            p.set(s.hip, pt(100, hip));
            p.set(s.shoulder, pt(100, shoulder));
        }
        return p;
    }

    private PosePoint pt(double x, double y) {
        return new PosePoint(x + noise.nextGaussian() * 0.7, y + noise.nextGaussian() * 0.7, 0.95);
    }

    private void frame(List<PosePoint> p) {
        clock += frameMs;
        ts = Math.round(clock);
        detector.feed(p, ts);
    }

    private void stand(double seconds) {
        for (int i = 0; i < seconds * 30; i++) frame(pose(0, 0, 0));
    }

    /**
     * One jump of the given true height. timeScale > 1 simulates a slowed-down video
     * (more frames per real second, same frame spacing on the timeline).
     */
    private void jump(double heightCm, double timeScale, double tuckKneePx) {
        // countermovement dip
        for (int i = 0; i < 10; i++) frame(pose(0, 40 * Math.sin(Math.PI * i / 10), 0));
        double v = Math.sqrt(2 * G * heightCm);
        double flight = 2 * v / G;
        double dt = 1.0 / 30 / timeScale;
        for (double t = dt; t < flight; t += dt) {
            double z = v * t - G * t * t / 2;
            double frac = t / flight;
            double tuck = (frac > 0.2 && frac < 0.8) ? tuckKneePx : 0;
            frame(pose(z, 0, tuck));
        }
        stand(1.0);
    }

    private void newDetector() {
        detector = new JumpDetector(ExerciseType.VERTICAL_JUMP, STATURE);
        ts = 0;
        clock = 0;
    }

    // ---------- tests ----------

    @Test public void measuresAGenuineJumpAccurately() {
        newDetector();
        stand(1.0);
        jump(40, 1, 0);
        List<JumpDetector.Jump> jumps = detector.getJumps();
        assertEquals(1, jumps.size());
        JumpDetector.Jump j = jumps.get(0);
        assertEquals(JumpDetector.JumpStatus.VALID, j.status);
        assertEquals(40, j.heightCm, 2.5);
        assertEquals(9.81, j.gravity, 1.0);
    }

    @Test public void bestOfThreeIsTheScore() {
        newDetector();
        stand(1.0);
        jump(30, 1, 0);
        jump(45, 1, 0);
        jump(38, 1, 0);
        TestOutcome o = detector.finish(300, 300, 30);
        assertEquals(3, o.valid);
        assertEquals(45, o.score, 2.5);
        assertEquals(SessionVerdict.Status.VERIFIED, o.verdict.status);
    }

    @Test public void slowedDownVideoFailsTheGravityCheck() {
        newDetector();
        stand(1.0);
        jump(20, 2.0, 0); // played at half speed: looks like a ~80 cm jump
        JumpDetector.Jump j = detector.getJumps().get(0);
        assertEquals(JumpDetector.JumpStatus.GRAVITY_MISMATCH, j.status);
        assertTrue("apparent g should be about a quarter of 9.8", j.gravity < 4);
        assertEquals(SessionVerdict.Status.FLAGGED, detector.finish(200, 200, 30).verdict.status);
    }

    @Test public void tuckedKneesAreNotCounted() {
        newDetector();
        stand(1.0);
        jump(40, 1, 70);
        assertEquals(JumpDetector.JumpStatus.TUCKED, detector.getJumps().get(0).status);
        TestOutcome o = detector.finish(200, 200, 30);
        assertEquals(0, o.valid);
        assertEquals(SessionVerdict.Status.NEEDS_REVIEW, o.verdict.status);
    }

    @Test public void countermovementAloneIsNotAJump() {
        newDetector();
        stand(1.0);
        for (int r = 0; r < 3; r++) {
            for (int i = 0; i < 20; i++) frame(pose(0, 60 * Math.sin(Math.PI * i / 20), 0));
        }
        stand(1.0);
        assertEquals(0, detector.getJumps().size());
        assertEquals(SessionVerdict.Status.NO_REPS, detector.finish(100, 100, 30).verdict.status);
    }

    @Test public void lowFrameRateNeedsReview() {
        newDetector();
        frameMs = 1000.0 / 15;
        stand(1.0);
        jump(40, 0.5, 0); // timeScale 0.5 with 15 fps frames = real-time at 15 fps
        TestOutcome o = detector.finish(100, 100, 15);
        assertEquals(1, o.valid);
        assertEquals(SessionVerdict.Status.NEEDS_REVIEW, o.verdict.status);
    }
}

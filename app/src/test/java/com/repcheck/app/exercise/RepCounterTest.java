package com.repcheck.app.exercise;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import com.repcheck.app.pose.BodySide;
import com.repcheck.app.pose.PosePoint;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/** Feeds synthetic push-up poses through the counter. Run with: ./gradlew test */
public class RepCounterTest {

    private long t = 0;

    /** A side-on push-up pose with the given elbow angle; bodySag bends the shoulder-hip-ankle line. */
    private static List<PosePoint> pushUpPose(double elbowAngle, double bodySag) {
        List<PosePoint> pts = new ArrayList<>();
        for (int i = 0; i < 33; i++) pts.add(new PosePoint(0, 0, 0));
        BodySide s = BodySide.LEFT;

        PosePoint shoulder = new PosePoint(0, 0, 1);
        PosePoint elbow = new PosePoint(0, 100, 1);
        // Rotate the elbow→shoulder direction (0,-1) by elbowAngle to place the wrist.
        double r = Math.toRadians(elbowAngle);
        PosePoint wrist = new PosePoint(elbow.x + 100 * Math.sin(r), elbow.y - 100 * Math.cos(r), 1);
        PosePoint hip = new PosePoint(300, 0, 1);
        PosePoint ankle = new PosePoint(600, bodySag, 1);

        pts.set(s.shoulder, shoulder);
        pts.set(s.elbow, elbow);
        pts.set(s.wrist, wrist);
        pts.set(s.hip, hip);
        pts.set(s.ankle, ankle);
        return pts;
    }

    /** Sweeps the elbow from 170° down to 'bottom' and back over durationMs. */
    private void doRep(RepCounter c, double bottom, double sag, long durationMs) {
        int steps = 20;
        long dt = durationMs / (2L * steps);
        for (int i = 0; i <= steps; i++) {
            c.update(pushUpPose(170 - (170 - bottom) * i / steps, sag), t += dt);
        }
        for (int i = 0; i <= steps; i++) {
            c.update(pushUpPose(bottom + (170 - bottom) * i / steps, sag), t += dt);
        }
        // hold at the top so smoothing settles
        for (int i = 0; i < 5; i++) c.update(pushUpPose(170, sag), t += 30);
    }

    private RepCounter readyCounter() {
        RepCounter c = new RepCounter(ExerciseType.PUSH_UP);
        for (int i = 0; i < 5; i++) c.update(pushUpPose(170, 0), t += 30);
        return c;
    }

    @Test public void countsCleanReps() {
        RepCounter c = readyCounter();
        for (int i = 0; i < 3; i++) doRep(c, 70, 0, 1500);
        RepCounter.FrameResult r = c.update(pushUpPose(170, 0), t += 30);
        assertEquals(3, r.validReps);
        assertEquals(0, r.partialReps);
    }

    @Test public void shallowRepIsPartial() {
        RepCounter c = readyCounter();
        doRep(c, 120, 0, 1500);
        RepCounter.FrameResult r = c.update(pushUpPose(170, 0), t += 30);
        assertEquals(0, r.validReps);
        assertEquals(1, r.partialReps);
    }

    @Test public void sagginessIsBadForm() {
        RepCounter c = readyCounter();
        doRep(c, 70, 250, 1500);
        RepCounter.FrameResult r = c.update(pushUpPose(170, 250), t += 30);
        assertEquals(0, r.validReps);
        assertEquals(1, r.badFormReps);
    }

    @Test public void impossiblyFastRepIsFlagged() {
        RepCounter c = readyCounter();
        doRep(c, 70, 0, 200);
        RepCounter.FrameResult r = c.update(pushUpPose(170, 0), t += 30);
        assertEquals(0, r.validReps);
        assertEquals(1, r.tooFastReps);
    }

    @Test public void hiddenBodyIsNotMeasured() {
        RepCounter c = new RepCounter(ExerciseType.PUSH_UP);
        List<PosePoint> pts = new ArrayList<>();
        for (int i = 0; i < 33; i++) pts.add(new PosePoint(0, 0, 0.1));
        assertFalse(c.update(pts, t += 30).bodyVisible);
    }
}

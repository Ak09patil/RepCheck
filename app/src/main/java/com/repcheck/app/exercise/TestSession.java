package com.repcheck.app.exercise;

import com.repcheck.app.pose.PosePoint;

import java.util.List;

/**
 * Analyses one recorded attempt, frame by frame. RepCounter and JumpDetector implement this,
 * so VideoAnalyzer doesn't need to know which test it's running.
 */
public interface TestSession {

    /**
     * @param points      33 landmarks in pixel coordinates (empty if nobody was detected)
     * @param timestampMs frame time; must increase
     * @return true if the athlete was measurable in this frame
     */
    boolean feed(List<PosePoint> points, long timestampMs);

    /** Call once after the last frame. */
    TestOutcome finish(int framesAnalysed, int framesWithBody, double videoFps);
}

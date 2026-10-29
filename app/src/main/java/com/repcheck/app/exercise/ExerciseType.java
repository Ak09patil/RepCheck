package com.repcheck.app.exercise;

import com.repcheck.app.pose.AngleUtils;
import com.repcheck.app.pose.BodySide;
import com.repcheck.app.pose.PosePoint;

import java.util.List;

/**
 * The tests the app can measure. Two kinds:
 *
 *  REPS — counted by RepCounter. Each is described by one "driving" joint angle that opens and
 *         closes every rep, the angles that count as fully EXTENDED / CONTRACTED, a form check,
 *         and the fastest a genuine rep can plausibly be done.
 *
 *  JUMP — measured by JumpDetector from flight time.
 */
public enum ExerciseType {

    SIT_UP("Sit-ups", Kind.REPS,
            "Place the phone side-on at floor level, about 2 m away, in landscape.",
            /* extended (lying back) */ 120, /* contracted (sitting up) */ 70, /* minRepMs */ 800, true) {
        @Override public double drivingAngle(List<PosePoint> p, BodySide s) {
            return AngleUtils.jointAngle(p.get(s.shoulder), p.get(s.hip), p.get(s.knee));
        }
        @Override public String formFault(List<PosePoint> p, BodySide s) {
            double knee = AngleUtils.jointAngle(p.get(s.hip), p.get(s.knee), p.get(s.ankle));
            return knee > 130 ? "Keep your knees bent" : null;
        }
        @Override public int[] requiredLandmarks(BodySide s) {
            return new int[]{s.shoulder, s.hip, s.knee, s.ankle};
        }
    },

    VERTICAL_JUMP("Vertical jump", Kind.JUMP,
            "Place the phone side-on, about 3 m away, in portrait, at waist height. Keep head to feet "
                    + "in frame with space above your head. Stand still, then jump straight up. "
                    + "Land with straight legs. You can jump up to 3 times; the best counts.",
            0, 0, 0, false) {
        @Override public int[] requiredLandmarks(BodySide s) {
            return new int[]{s.hip, s.knee, s.ankle};
        }
    },

    PUSH_UP("Push-ups", Kind.REPS,
            "Place the phone side-on, about 2 m away, in landscape. Your whole body should be in frame.",
            /* extended */ 155, /* contracted */ 90, /* minRepMs */ 600, true) {
        @Override public double drivingAngle(List<PosePoint> p, BodySide s) {
            return AngleUtils.jointAngle(p.get(s.shoulder), p.get(s.elbow), p.get(s.wrist));
        }
        @Override public String formFault(List<PosePoint> p, BodySide s) {
            double bodyLine = AngleUtils.jointAngle(p.get(s.shoulder), p.get(s.hip), p.get(s.ankle));
            return bodyLine < 150 ? "Keep your body straight" : null;
        }
        @Override public int[] requiredLandmarks(BodySide s) {
            return new int[]{s.shoulder, s.elbow, s.wrist, s.hip, s.ankle};
        }
    },

    SQUAT("Squats", Kind.REPS,
            "Place the phone side-on, about 2.5 m away, in portrait. Head to feet should be in frame.",
            /* extended */ 160, /* contracted */ 100, /* minRepMs */ 700, false) {
        @Override public double drivingAngle(List<PosePoint> p, BodySide s) {
            return AngleUtils.jointAngle(p.get(s.hip), p.get(s.knee), p.get(s.ankle));
        }
        @Override public String formFault(List<PosePoint> p, BodySide s) {
            double lean = AngleUtils.leanFromVertical(p.get(s.shoulder), p.get(s.hip));
            return lean > 55 ? "Keep your chest up" : null;
        }
        @Override public int[] requiredLandmarks(BodySide s) {
            return new int[]{s.shoulder, s.hip, s.knee, s.ankle};
        }
    };

    public enum Kind { REPS, JUMP }

    public final String displayName;
    public final Kind kind;
    public final String setupHint;
    public final double extendedAngle;
    public final double contractedAngle;
    public final long minRepMs;
    public final boolean landscape;

    ExerciseType(String displayName, Kind kind, String setupHint, double extendedAngle,
                 double contractedAngle, long minRepMs, boolean landscape) {
        this.displayName = displayName;
        this.kind = kind;
        this.setupHint = setupHint;
        this.extendedAngle = extendedAngle;
        this.contractedAngle = contractedAngle;
        this.minRepMs = minRepMs;
        this.landscape = landscape;
    }

    /**
     * How many video frames per second to analyse. Rep counting is fine at 15;
     * jump height depends on timing, so jumps use every frame.
     */
    public double analysisFps() {
        return kind == Kind.JUMP ? 1000 : 15;
    }

    /**
     * Recording length. Sit-ups follow the SAI/Khelo India curl-up protocol (30 s) so scores
     * are comparable with the benchmarks; 30 s also fits three jumps.
     */
    public long testDurationMs() {
        switch (this) {
            case SIT_UP:
            case VERTICAL_JUMP:
                return 30_000;
            default:
                return 60_000;
        }
    }

    /** Creates the analyser for one recorded attempt. statureCm is the athlete's height (jumps need it for scale). */
    public TestSession newSession(double statureCm) {
        return kind == Kind.JUMP ? new JumpDetector(this, statureCm) : new RepCounter(this);
    }

    /** REPS only: the joint angle that opens and closes each rep. */
    public double drivingAngle(List<PosePoint> points, BodySide side) {
        throw new UnsupportedOperationException(name() + " is not a rep exercise");
    }

    /** REPS only: a short correction message if form is off in this frame, or null if form is fine. */
    public String formFault(List<PosePoint> points, BodySide side) {
        throw new UnsupportedOperationException(name() + " is not a rep exercise");
    }

    /** Landmarks that must be visible for this exercise to be measured. */
    public abstract int[] requiredLandmarks(BodySide side);

    /**
     * The side of the body the camera sees better, or null if neither side's
     * required landmarks are all at least minVisibility.
     */
    public BodySide visibleSide(List<PosePoint> points, double minVisibility) {
        if (points == null || points.size() < 33) return null;
        double left = minVisibility(points, requiredLandmarks(BodySide.LEFT));
        double right = minVisibility(points, requiredLandmarks(BodySide.RIGHT));
        if (Math.max(left, right) < minVisibility) return null;
        return left >= right ? BodySide.LEFT : BodySide.RIGHT;
    }

    private static double minVisibility(List<PosePoint> points, int[] indices) {
        double min = 1;
        for (int i : indices) min = Math.min(min, points.get(i).visibility);
        return min;
    }
}

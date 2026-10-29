package com.repcheck.app.exercise;

import com.repcheck.app.analysis.SessionVerdict;
import com.repcheck.app.pose.BodySide;
import com.repcheck.app.pose.PosePoint;

import java.util.List;

/**
 * Turns a stream of poses into counted reps.
 *
 * State machine (angle = the exercise's driving joint angle):
 *
 *   WAITING ──angle ≥ extended──▶ EXTENDED
 *   EXTENDED ──angle drops past extended − margin──▶ MOVING   (rep starts)
 *   MOVING ──angle ≤ contracted──▶ CONTRACTED
 *   MOVING ──back to ≥ extended without reaching contracted──▶ EXTENDED   (PARTIAL rep)
 *   CONTRACTED ──angle ≥ extended──▶ EXTENDED   (rep finished → judged)
 *
 * A finished rep is judged as:
 *   TOO_FAST  if it took less than the exercise's minimum plausible time,
 *   BAD_FORM  if the form check failed on more than 30% of its frames,
 *   VALID     otherwise.
 *
 * Not thread-safe: call update() from one thread only.
 */
public class RepCounter implements TestSession {

    public enum Phase { WAITING, EXTENDED, MOVING, CONTRACTED }

    /** Degrees the angle must move away from "extended" before we treat it as a rep starting. */
    static final double START_MARGIN = 15;
    /** Smoothing factor for the angle (0..1, higher = more responsive, more jitter). */
    static final double SMOOTHING = 0.5;
    static final double MIN_VISIBILITY = 0.5;
    static final double BAD_FORM_FRACTION = 0.3;

    private final ExerciseType type;

    private Phase phase = Phase.WAITING;
    private double smoothedAngle = Double.NaN;
    private long repStartMs;
    private int repFrames;
    private int repFaultFrames;
    private String lastFault;

    private int validReps, partialReps, badFormReps, tooFastReps;

    public RepCounter(ExerciseType type) {
        this.type = type;
    }

    /**
     * @param points      33 landmarks in PIXEL coordinates (scale MediaPipe's 0..1 output by image size first,
     *                    otherwise angles are distorted on non-square frames)
     * @param timestampMs frame time, must increase
     */
    public FrameResult update(List<PosePoint> points, long timestampMs) {
        if (points == null || points.size() < 33) {
            return snapshot(false, "Step into the frame", null);
        }

        BodySide side = pickVisibleSide(points);
        if (side == null) {
            return snapshot(false, "Make sure your whole body is visible", null);
        }

        double raw = type.drivingAngle(points, side);
        smoothedAngle = Double.isNaN(smoothedAngle)
                ? raw
                : SMOOTHING * raw + (1 - SMOOTHING) * smoothedAngle;
        double a = smoothedAngle;

        String fault = type.formFault(points, side);
        String feedback;

        switch (phase) {
            case WAITING:
                if (a >= type.extendedAngle) {
                    phase = Phase.EXTENDED;
                    feedback = "Ready — start your reps";
                } else {
                    feedback = "Get into the starting position";
                }
                break;

            case EXTENDED:
                if (a < type.extendedAngle - START_MARGIN) {
                    phase = Phase.MOVING;
                    repStartMs = timestampMs;
                    repFrames = 0;
                    repFaultFrames = 0;
                    lastFault = null;
                    trackForm(fault);
                }
                feedback = fault != null ? fault : "Good";
                break;

            case MOVING:
                trackForm(fault);
                if (a <= type.contractedAngle) {
                    phase = Phase.CONTRACTED;
                    feedback = "Good depth";
                } else if (a >= type.extendedAngle) {
                    partialReps++;
                    phase = Phase.EXTENDED;
                    feedback = "Not counted — go further";
                } else {
                    feedback = fault != null ? fault : "Keep going";
                }
                break;

            case CONTRACTED:
            default:
                trackForm(fault);
                if (a >= type.extendedAngle) {
                    feedback = judgeRep(timestampMs - repStartMs);
                    phase = Phase.EXTENDED;
                } else {
                    feedback = fault != null ? fault : "Now back up";
                }
                break;
        }

        return snapshot(true, feedback, side);
    }

    private void trackForm(String fault) {
        repFrames++;
        if (fault != null) {
            repFaultFrames++;
            lastFault = fault;
        }
    }

    private String judgeRep(long durationMs) {
        if (durationMs < type.minRepMs) {
            tooFastReps++;
            return "Too fast to verify — slow down";
        }
        if (repFrames > 0 && (double) repFaultFrames / repFrames > BAD_FORM_FRACTION) {
            badFormReps++;
            return "Counted with form issue: " + lastFault;
        }
        validReps++;
        return "Rep " + validReps + " ✓";
    }

    BodySide pickVisibleSide(List<PosePoint> points) {
        return type.visibleSide(points, MIN_VISIBILITY);
    }

    public void reset() {
        phase = Phase.WAITING;
        smoothedAngle = Double.NaN;
        validReps = partialReps = badFormReps = tooFastReps = 0;
    }

    public ExerciseType getType() { return type; }

    /** Current totals, e.g. once a recorded video has been fully processed. */
    public FrameResult summary() {
        return snapshot(true, "", null);
    }

    // ---------- TestSession ----------

    @Override
    public boolean feed(List<PosePoint> points, long timestampMs) {
        return update(points, timestampMs).bodyVisible;
    }

    @Override
    public TestOutcome finish(int framesAnalysed, int framesWithBody, double videoFps) {
        FrameResult c = summary();
        SessionVerdict verdict = SessionVerdict.judge(c, framesAnalysed, framesWithBody);
        String details = "Valid reps: " + c.validReps
                + "\nPartial (not counted): " + c.partialReps
                + "\nForm issues: " + c.badFormReps
                + "\nToo fast to verify: " + c.tooFastReps;
        return new TestOutcome(c.validReps, "reps", "valid reps", verdict, details,
                c.validReps, c.partialReps, c.badFormReps, c.tooFastReps);
    }

    private FrameResult snapshot(boolean bodyVisible, String feedback, BodySide side) {
        return new FrameResult(bodyVisible, phase, smoothedAngle, feedback, side,
                validReps, partialReps, badFormReps, tooFastReps);
    }

    /** Everything the UI needs after one frame. Immutable, so it's safe to pass to the UI thread. */
    public static final class FrameResult {
        public final boolean bodyVisible;
        public final Phase phase;
        public final double angle;
        public final String feedback;
        public final BodySide side;
        public final int validReps, partialReps, badFormReps, tooFastReps;

        public FrameResult(boolean bodyVisible, Phase phase, double angle, String feedback, BodySide side,
                    int validReps, int partialReps, int badFormReps, int tooFastReps) {
            this.bodyVisible = bodyVisible;
            this.phase = phase;
            this.angle = angle;
            this.feedback = feedback;
            this.side = side;
            this.validReps = validReps;
            this.partialReps = partialReps;
            this.badFormReps = badFormReps;
            this.tooFastReps = tooFastReps;
        }

        /** Every attempt, whether or not it was counted as valid. */
        public int totalAttempts() {
            return validReps + partialReps + badFormReps + tooFastReps;
        }
    }
}

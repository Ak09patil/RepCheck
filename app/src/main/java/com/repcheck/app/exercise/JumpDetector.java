package com.repcheck.app.exercise;

import com.repcheck.app.analysis.SessionVerdict;
import com.repcheck.app.pose.AngleUtils;
import com.repcheck.app.pose.BodySide;
import com.repcheck.app.pose.PosePoint;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Measures standing vertical jumps from flight time.
 *
 * HOW HEIGHT IS MEASURED
 *   A body thrown straight up spends half its flight rising and half falling, so
 *       jump height h = g · t² / 8          (t = flight time, g = 9.81 m/s²)
 *   This needs no tape measure or reference object, only accurate timing.
 *
 *   "Feet" = the lowest visible foot point (toe, heel or ankle) of either foot, so the athlete
 *   only counts as airborne when BOTH feet are off the ground. The ground line is learned
 *   while the athlete stands.
 *
 *   Takeoff and landing are timed where the feet cross a line slightly (3% of leg length)
 *   above the ground, interpolated between frames for sub-frame precision. Because that line is
 *   a small height d above the floor, the exact correction is h = g·tc²/8 + d.
 *
 * PIXEL → CM SCALE
 *   Hip-to-ankle length is ≈ 46% of standing height (anthropometric average). With the athlete's
 *   height from their profile, measured leg length in pixels gives a cm-per-pixel scale.
 *
 * CHECKS ON EACH JUMP (first that applies wins)
 *   GRAVITY_MISMATCH  the hip's flight path doesn't fall at ~9.8 m/s² → video slowed down or edited
 *   IMPLAUSIBLE       higher than any genuine standing jump at this level
 *   TUCKED            knees bent in the air, which keeps feet up longer and inflates flight time
 *   NO_BODY_RISE      feet left the ground but the hips didn't rise to match
 *   VALID             counted; the best valid jump is the score
 *
 * Not thread-safe: feed() from one thread only.
 */
public class JumpDetector implements TestSession {

    // ----- Tunable constants -----
    static final double LEG_TO_STATURE = 0.46;
    /** Feet must rise this fraction of leg length above ground to count as a takeoff. */
    static final double DETECT_FRACTION = 0.06;
    /** Takeoff/landing are timed where the feet cross this fraction of leg length. */
    static final double TIMING_FRACTION = 0.03;
    static final double MIN_FLIGHT_MS = 120;   // shorter = noise or a hop, ignored (≈ 1.8 cm)
    /**
     * Longer = tracking lost, not a jump. Deliberately generous: a slowed-down video still lands
     * inside this window, so it gets caught by the gravity check instead of being silently dropped.
     */
    static final double MAX_FLIGHT_MS = 2500;
    static final long MAX_TRACKING_GAP_MS = 300;
    static final int MIN_GROUNDED_FRAMES = 5;
    static final double STANDING_KNEE_DEG = 160;
    static final double TUCK_KNEE_DEG = 140;
    static final double MIN_BODY_RISE_RATIO = 0.5;
    static final double MAX_PLAUSIBLE_CM = 90;
    static final double G_MIN = 6.5, G_MAX = 13.5;   // m/s², wide because the scale is estimated
    static final int MIN_GRAVITY_SAMPLES = 5;
    static final double MIN_RELIABLE_FPS = 24;
    static final double EMA = 0.2;
    private static final double G_CM = 981;

    public enum JumpStatus { VALID, TUCKED, NO_BODY_RISE, IMPLAUSIBLE, GRAVITY_MISMATCH, TRACKING_LOST }

    /** One detected jump. */
    public static final class Jump {
        public final JumpStatus status;
        public final double heightCm;
        public final double flightMs;
        /** Measured gravity in m/s², NaN if too few frames to estimate. */
        public final double gravity;
        public final double minKneeDeg;
        public final double bodyRiseRatio;

        Jump(JumpStatus status, double heightCm, double flightMs, double gravity,
             double minKneeDeg, double bodyRiseRatio) {
            this.status = status;
            this.heightCm = heightCm;
            this.flightMs = flightMs;
            this.gravity = gravity;
            this.minKneeDeg = minKneeDeg;
            this.bodyRiseRatio = bodyRiseRatio;
        }
    }

    private static final class Sample {
        final long ts;
        final double feetY, hipY, kneeDeg;

        Sample(long ts, double feetY, double hipY, double kneeDeg) {
            this.ts = ts;
            this.feetY = feetY;
            this.hipY = hipY;
            this.kneeDeg = kneeDeg;
        }
    }

    private final ExerciseType type;
    private final double statureCm;

    private boolean airborne;
    private double groundY = Double.NaN;      // pixels, y grows downward
    private double legPx = Double.NaN;        // hip-to-ankle while standing straight
    private double standingHipY = Double.NaN;
    private int groundedFrames;
    private final List<Sample> recent = new ArrayList<>();  // last few grounded frames, for takeoff timing
    private final List<Sample> flight = new ArrayList<>();
    private double takeoffMs;
    private long lastUsableTs = Long.MIN_VALUE;

    private final List<Jump> jumps = new ArrayList<>();

    public JumpDetector(ExerciseType type, double statureCm) {
        this.type = type;
        this.statureCm = statureCm;
    }

    public List<Jump> getJumps() {
        return new ArrayList<>(jumps);
    }

    // ---------- Per frame ----------

    @Override
    public boolean feed(List<PosePoint> points, long ts) {
        BodySide side = type.visibleSide(points, 0.5);
        double feetY = side == null ? Double.NaN : lowestFootY(points);
        if (side == null || Double.isNaN(feetY)) {
            if (airborne && ts - lastUsableTs > MAX_TRACKING_GAP_MS) abandonFlight();
            return false;
        }
        if (airborne && lastUsableTs != Long.MIN_VALUE && ts - lastUsableTs > MAX_TRACKING_GAP_MS) {
            abandonFlight();
        }
        lastUsableTs = ts;

        PosePoint hip = points.get(side.hip), knee = points.get(side.knee), ankle = points.get(side.ankle);
        double kneeDeg = AngleUtils.jointAngle(hip, knee, ankle);
        Sample s = new Sample(ts, feetY, hipY(points, side), kneeDeg);

        if (airborne) onAirborne(s);
        else onGrounded(s, Math.hypot(hip.x - ankle.x, hip.y - ankle.y));
        return true;
    }

    private void onGrounded(Sample s, double legLengthPx) {
        if (Double.isNaN(groundY)) groundY = s.feetY;

        // Calibrate scale and standing hip height only while standing straight.
        if (s.kneeDeg >= STANDING_KNEE_DEG) {
            legPx = Double.isNaN(legPx) ? legLengthPx : ema(legPx, legLengthPx);
            standingHipY = Double.isNaN(standingHipY) ? s.hipY : ema(standingHipY, s.hipY);
        }
        if (Double.isNaN(legPx)) {
            groundY = ema(groundY, s.feetY);
            return;
        }

        double detectLine = groundY - DETECT_FRACTION * legPx;
        double timingLine = groundY - TIMING_FRACTION * legPx;

        if (s.feetY < detectLine && groundedFrames >= MIN_GROUNDED_FRAMES) {
            takeoffMs = takeoffCrossing(s, timingLine);
            airborne = true;
            flight.clear();
            flight.add(s);
            return;
        }

        if (s.feetY >= timingLine) {
            // Feet on the ground: let the ground line follow slow drift (athlete shuffling, camera settling).
            groundY = ema(groundY, s.feetY);
            groundedFrames++;
        }
        recent.add(s);
        if (recent.size() > 15) recent.remove(0);
    }

    private void onAirborne(Sample s) {
        double timingLine = groundY - TIMING_FRACTION * legPx;
        Sample prev = flight.get(flight.size() - 1);
        flight.add(s);

        if (s.feetY >= timingLine) {
            // Landed: interpolate when the feet crossed the timing line between the last two frames.
            double landingMs = interpolate(prev, s, timingLine);
            completeJump(landingMs);
            resetToGround(s);
        } else if (s.ts - takeoffMs > MAX_FLIGHT_MS) {
            abandonFlight();
        }
    }

    // ---------- Jump evaluation ----------

    private void completeJump(double landingMs) {
        double flightMs = landingMs - takeoffMs;
        if (flightMs < MIN_FLIGHT_MS) return; // a twitch, not a jump

        double pxPerCm = legPx / (LEG_TO_STATURE * statureCm);
        double t = flightMs / 1000.0;
        double heightCm = G_CM * t * t / 8 + (TIMING_FRACTION * legPx) / pxPerCm;

        // Only frames clearly in the air (not the landing frame) are used for the checks.
        List<Sample> air = new ArrayList<>(flight.subList(0, flight.size() - 1));

        double minKnee = 180;
        double margin = 0.15 * flightMs; // ignore knee bend right at takeoff/landing
        for (Sample a : air) {
            if (a.ts > takeoffMs + margin && a.ts < landingMs - margin) minKnee = Math.min(minKnee, a.kneeDeg);
        }

        double minHipY = Double.MAX_VALUE;
        for (Sample a : air) minHipY = Math.min(minHipY, a.hipY);
        double bodyRiseCm = (standingHipY - minHipY) / pxPerCm;
        double riseRatio = bodyRiseCm / heightCm;

        double gravity = estimateGravity(air, pxPerCm);

        JumpStatus status;
        if (!Double.isNaN(gravity) && (gravity < G_MIN || gravity > G_MAX)) status = JumpStatus.GRAVITY_MISMATCH;
        else if (heightCm > MAX_PLAUSIBLE_CM) status = JumpStatus.IMPLAUSIBLE;
        else if (minKnee < TUCK_KNEE_DEG) status = JumpStatus.TUCKED;
        else if (riseRatio < MIN_BODY_RISE_RATIO) status = JumpStatus.NO_BODY_RISE;
        else status = JumpStatus.VALID;

        jumps.add(new Jump(status, heightCm, flightMs, gravity, minKnee, riseRatio));
    }

    /**
     * Fits y = a·t² + b·t + c to the hip's flight path. In free fall, a = g/2 (pixels/s²),
     * so g = 2a converted to m/s². A slowed-down video stretches time and shows a much weaker "gravity".
     */
    static double estimateGravity(List<Sample> air, double pxPerCm) {
        if (air.size() < MIN_GRAVITY_SAMPLES) return Double.NaN;
        int n = air.size();
        double t0 = air.get(0).ts;
        double mean = 0;
        for (Sample s : air) mean += (s.ts - t0) / 1000.0;
        mean /= n;

        // Normal equations for least squares, with t centred for numerical stability.
        double s1 = 0, s2 = 0, s3 = 0, s4 = 0, y0 = 0, y1 = 0, y2 = 0;
        for (Sample s : air) {
            double t = (s.ts - t0) / 1000.0 - mean;
            double t2 = t * t;
            s1 += t; s2 += t2; s3 += t2 * t; s4 += t2 * t2;
            y0 += s.hipY; y1 += t * s.hipY; y2 += t2 * s.hipY;
        }
        double a = solveQuadraticA(s4, s3, s2, s1, n, y2, y1, y0);
        if (Double.isNaN(a)) return Double.NaN;
        return 2 * a / pxPerCm / 100.0;
    }

    /** Solves [[s4 s3 s2][s3 s2 s1][s2 s1 n]]·[a b c] = [y2 y1 y0] for a (Cramer's rule). */
    private static double solveQuadraticA(double s4, double s3, double s2, double s1, double n,
                                          double y2, double y1, double y0) {
        double det = det3(s4, s3, s2, s3, s2, s1, s2, s1, n);
        if (Math.abs(det) < 1e-12) return Double.NaN;
        return det3(y2, s3, s2, y1, s2, s1, y0, s1, n) / det;
    }

    private static double det3(double a, double b, double c, double d, double e, double f,
                               double g, double h, double i) {
        return a * (e * i - f * h) - b * (d * i - f * g) + c * (d * h - e * g);
    }

    // ---------- Session result ----------

    @Override
    public TestOutcome finish(int framesAnalysed, int framesWithBody, double videoFps) {
        int valid = 0;
        double best = 0;
        boolean cheat = false, excluded = false;
        StringBuilder details = new StringBuilder();
        List<String> reasons = new ArrayList<>();

        for (int i = 0; i < jumps.size(); i++) {
            Jump j = jumps.get(i);
            details.append(String.format(Locale.US, "Jump %d: %.1f cm (flight %.3f s) — %s\n",
                    i + 1, j.heightCm, j.flightMs / 1000, describe(j)));
            if (j.status == JumpStatus.VALID) {
                valid++;
                best = Math.max(best, j.heightCm);
            } else if (j.status == JumpStatus.GRAVITY_MISMATCH || j.status == JumpStatus.IMPLAUSIBLE) {
                cheat = true;
            } else {
                excluded = true;
            }
        }

        double visibleFraction = framesAnalysed == 0 ? 0 : (double) framesWithBody / framesAnalysed;

        if (jumps.isEmpty()) {
            reasons.add(visibleFraction < 0.6
                    ? "The athlete wasn't clearly visible — check the camera position"
                    : "No jump was detected");
            return new TestOutcome(0, "cm", "cm · best jump",
                    SessionVerdict.of(SessionVerdict.Status.NO_REPS, reasons), "", 0, 0, 0, 0);
        }

        SessionVerdict.Status status = SessionVerdict.Status.VERIFIED;
        if (cheat) {
            status = SessionVerdict.Status.FLAGGED;
            reasons.add("A jump didn't match real-world physics — the video may be slowed down or edited");
        }
        if (valid == 0) {
            status = SessionVerdict.worse(status, SessionVerdict.Status.NEEDS_REVIEW);
            reasons.add("No jump could be counted");
        } else if (excluded) {
            reasons.add("Some jumps weren't counted (see breakdown)");
        }
        if (videoFps > 0 && videoFps < MIN_RELIABLE_FPS) {
            status = SessionVerdict.worse(status, SessionVerdict.Status.NEEDS_REVIEW);
            reasons.add(String.format(Locale.US,
                    "Video is only %.0f fps, so timing is less precise than usual", videoFps));
        }
        if (status == SessionVerdict.Status.VERIFIED) {
            reasons.add("Best of " + valid + " valid jump" + (valid == 1 ? "" : "s")
                    + "; flight path matches real-world gravity");
        }

        details.append(String.format(Locale.US, "Video: %.0f fps", videoFps));
        return new TestOutcome(round1(best), "cm", "cm · best jump", SessionVerdict.of(status, reasons),
                details.toString(), valid, 0, 0, 0);
    }

    static String describe(Jump j) {
        switch (j.status) {
            case VALID: return "✓ counted";
            case TUCKED: return "knees bent in the air, not counted";
            case NO_BODY_RISE: return "hips didn't rise with the feet, not counted";
            case IMPLAUSIBLE: return "higher than humanly plausible";
            case GRAVITY_MISMATCH:
                return String.format(Locale.US, "fell at %.1f m/s² instead of 9.8 — video speed altered?", j.gravity);
            default: return "tracking lost";
        }
    }

    // ---------- helpers ----------

    private double takeoffCrossing(Sample current, double line) {
        // Find the last frame still on the ground, then interpolate to the next frame.
        List<Sample> seq = new ArrayList<>(recent);
        seq.add(current);
        for (int i = seq.size() - 2; i >= 0; i--) {
            if (seq.get(i).feetY >= line) return interpolate(seq.get(i), seq.get(i + 1), line);
        }
        return seq.get(0).ts;
    }

    /** Time at which feetY crossed 'line' between samples a and b (linear). */
    private static double interpolate(Sample a, Sample b, double line) {
        double dy = b.feetY - a.feetY;
        if (dy == 0) return b.ts;
        double f = (line - a.feetY) / dy;
        f = Math.max(0, Math.min(1, f));
        return a.ts + f * (b.ts - a.ts);
    }

    private void abandonFlight() {
        jumps.add(new Jump(JumpStatus.TRACKING_LOST, 0, 0, Double.NaN, 180, 0));
        airborne = false;
        flight.clear();
        recent.clear();
        groundY = Double.NaN; // the scene may have changed; relearn the ground
        groundedFrames = 0;
    }

    private void resetToGround(Sample landing) {
        airborne = false;
        flight.clear();
        recent.clear();
        recent.add(landing);
        groundedFrames = 0; // must settle again before the next jump
    }

    /** Lowest (largest y) visible point of either foot. */
    private static double lowestFootY(List<PosePoint> p) {
        double best = Double.NaN;
        for (BodySide side : BodySide.values()) {
            for (int i : new int[]{side.toe, side.heel, side.ankle}) {
                PosePoint q = p.get(i);
                if (q.visibility >= 0.3 && (Double.isNaN(best) || q.y > best)) best = q.y;
            }
        }
        return best;
    }

    private static double hipY(List<PosePoint> p, BodySide side) {
        PosePoint l = p.get(BodySide.LEFT.hip), r = p.get(BodySide.RIGHT.hip);
        if (l.visibility >= 0.5 && r.visibility >= 0.5) return (l.y + r.y) / 2;
        return p.get(side.hip).y;
    }

    private static double ema(double old, double value) {
        return (1 - EMA) * old + EMA * value;
    }

    private static double round1(double v) {
        return Math.round(v * 10) / 10.0;
    }
}

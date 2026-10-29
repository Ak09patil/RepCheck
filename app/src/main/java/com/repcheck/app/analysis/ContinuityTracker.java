package com.repcheck.app.analysis;

import com.repcheck.app.pose.BodySide;
import com.repcheck.app.pose.PosePoint;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Watches the athlete's body across frames for signs the video was cut or a different person took over.
 *
 * SPLICE: between two frames close in time, the body jumps a long way and STAYS there.
 *   Real movement is continuous: even mid-jump the hips move ~8 cm per frame at 30 fps, about
 *   a sixth of torso length. A cut between two moments looks like a teleport. A one-frame tracking
 *   glitch also teleports, but snaps back on the next frames, so it's ignored.
 *
 * PERSON SWAP: body proportions (torso length ÷ leg length) don't depend on posture or camera
 *   distance, so they stay steady for one person. A lasting change suggests someone else.
 *
 * Plain Java, so it's unit-tested. Feed every analysed frame, then call report().
 */
public final class ContinuityTracker {

    /** Hip movement between consecutive frames, in torso lengths, that counts as a teleport. */
    static final double TELEPORT_TORSOS = 0.5;
    /** After a teleport, the next frames must stay within this distance of the new spot. */
    static final double STAY_TORSOS = 0.25;
    static final int CONFIRM_FRAMES = 3;
    /** Only compare frames this close in time; longer gaps are FrameTiming's job. */
    static final long MAX_COMPARE_GAP_MS = 150;
    static final double PROPORTION_CHANGE = 0.20;
    static final int MIN_PROPORTION_SAMPLES = 15;
    static final double MIN_VISIBILITY = 0.5;

    private static final class Obs {
        final long ts;
        final double hipX, hipY, torso, ratio;

        Obs(long ts, double hipX, double hipY, double torso, double ratio) {
            this.ts = ts;
            this.hipX = hipX;
            this.hipY = hipY;
            this.torso = torso;
            this.ratio = ratio;
        }
    }

    private final List<Obs> obs = new ArrayList<>();

    public void feed(List<PosePoint> points, long ts) {
        if (points == null || points.size() < 33) return;
        BodySide side = bestSide(points);
        if (side == null) return;
        PosePoint sh = points.get(side.shoulder), hip = points.get(side.hip);
        PosePoint knee = points.get(side.knee), ankle = points.get(side.ankle);
        double torso = dist(sh, hip);
        double leg = dist(hip, knee) + dist(knee, ankle);
        if (torso < 1 || leg < 1) return;
        obs.add(new Obs(ts, hip.x, hip.y, torso, torso / leg));
    }

    public IntegrityReport report() {
        IntegrityReport r = new IntegrityReport();

        int splices = 0;
        long firstSpliceTs = -1;
        for (int i = 1; i + CONFIRM_FRAMES < obs.size(); i++) {
            Obs a = obs.get(i - 1), b = obs.get(i);
            if (b.ts - a.ts > MAX_COMPARE_GAP_MS) continue;
            double scale = (a.torso + b.torso) / 2;
            if (move(a, b) / scale < TELEPORT_TORSOS) continue;
            // Snapping back to where the body was two frames ago = the end of a one-frame glitch, not a cut.
            if (i >= 2 && move(obs.get(i - 2), b) / scale < STAY_TORSOS) continue;

            boolean stayed = true;
            for (int k = 1; k <= CONFIRM_FRAMES; k++) {
                if (move(b, obs.get(i + k)) / scale > STAY_TORSOS) {
                    stayed = false;
                    break;
                }
            }
            if (stayed) {
                splices++;
                if (firstSpliceTs < 0) firstSpliceTs = b.ts;
                i += CONFIRM_FRAMES; // don't count the same cut twice
            }
        }
        if (splices > 0) {
            r.add(SessionVerdict.Status.NEEDS_REVIEW, String.format(Locale.US,
                    "The athlete jumped position suddenly %d time%s (first at %.1f s), as if the video was cut",
                    splices, splices == 1 ? "" : "s", firstSpliceTs / 1000.0));
        }

        if (obs.size() >= 3 * MIN_PROPORTION_SAMPLES) {
            int third = obs.size() / 3;
            double early = median(obs.subList(0, third));
            double late = median(obs.subList(obs.size() - third, obs.size()));
            double change = (late - early) / early;
            if (Math.abs(change) > PROPORTION_CHANGE) {
                r.add(SessionVerdict.Status.NEEDS_REVIEW, String.format(Locale.US,
                        "Body proportions changed by %.0f%% between the start and end, possibly a different person",
                        Math.abs(change) * 100));
            }
        }
        return r;
    }

    private static BodySide bestSide(List<PosePoint> p) {
        BodySide best = null;
        double bestVis = MIN_VISIBILITY;
        for (BodySide s : BodySide.values()) {
            double v = Math.min(Math.min(p.get(s.shoulder).visibility, p.get(s.hip).visibility),
                    Math.min(p.get(s.knee).visibility, p.get(s.ankle).visibility));
            if (v >= bestVis) {
                bestVis = v;
                best = s;
            }
        }
        return best;
    }

    private static double move(Obs a, Obs b) {
        return Math.hypot(b.hipX - a.hipX, b.hipY - a.hipY);
    }

    private static double dist(PosePoint a, PosePoint b) {
        return Math.hypot(a.x - b.x, a.y - b.y);
    }

    private static double median(List<Obs> list) {
        double[] v = new double[list.size()];
        for (int i = 0; i < v.length; i++) v[i] = list.get(i).ratio;
        Arrays.sort(v);
        return v[v.length / 2];
    }
}

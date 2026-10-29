package com.repcheck.app.analysis;

import java.util.Arrays;
import java.util.Locale;

/**
 * Checks the video's frame timestamps (read from the file without decoding any pictures).
 *
 * A clip straight from the phone camera has frames at an almost steady interval (about 33 ms at
 * 30 fps), with the odd dropped frame. What gives an edited or replaced file away:
 *  - GAP:        a long stretch with no frames (a pause, or a section cut out)
 *  - DUPLICATE:  two frames with the same timestamp (stitched-together clips)
 *  - LENGTH:     the file's length doesn't match how long the app actually recorded for,
 *                measured independently with the phone's clock (the file was swapped or trimmed)
 *
 * Plain Java, so it's unit-tested.
 */
public final class FrameTiming {
    private FrameTiming() {}

    /** A gap must be this many typical frame intervals long, and at least MIN_GAP_MS. */
    static final double GAP_INTERVALS = 3;
    static final long MIN_GAP_MS = 250;
    /** Gaps this long are reported (shorter ones are ordinary dropped frames). */
    static final long REPORT_GAP_MS = 500;
    /** Allowed difference between file length and the recording clock (encoder start-up/flush). */
    static final long LENGTH_TOLERANCE_MS = 1500;

    /**
     * @param ptsUs              presentation timestamps of every video frame, microseconds (any order)
     * @param expectedDurationMs how long the app recorded for by its own clock, or ≤0 if unknown
     */
    public static IntegrityReport check(long[] ptsUs, long expectedDurationMs) {
        IntegrityReport report = new IntegrityReport();
        if (ptsUs == null || ptsUs.length < 3) return report;

        long[] t = ptsUs.clone();
        Arrays.sort(t); // B-frames are stored out of display order

        long[] deltas = new long[t.length - 1];
        for (int i = 1; i < t.length; i++) deltas[i - 1] = t[i] - t[i - 1];
        long[] sorted = deltas.clone();
        Arrays.sort(sorted);
        long medianUs = sorted[sorted.length / 2];

        int duplicates = 0;
        long longestGapUs = 0;
        int gaps = 0;
        long gapThresholdUs = Math.max((long) (GAP_INTERVALS * medianUs), MIN_GAP_MS * 1000);
        for (long d : deltas) {
            if (d <= 0) duplicates++;
            if (d > gapThresholdUs) {
                longestGapUs = Math.max(longestGapUs, d);
                if (d >= REPORT_GAP_MS * 1000) gaps++;
            }
        }

        if (duplicates > 0) {
            report.add(SessionVerdict.Status.NEEDS_REVIEW,
                    duplicates + " frame" + (duplicates == 1 ? " shares" : "s share")
                            + " a timestamp with another, a sign of joined clips");
        }
        if (gaps > 0) {
            report.add(SessionVerdict.Status.NEEDS_REVIEW, String.format(Locale.US,
                    "Video has %d gap%s with no frames (longest %.1f s): part may be missing",
                    gaps, gaps == 1 ? "" : "s", longestGapUs / 1e6));
        }

        if (expectedDurationMs > 0) {
            long fileMs = (t[t.length - 1] - t[0]) / 1000 + medianUs / 1000;
            if (Math.abs(fileMs - expectedDurationMs) > LENGTH_TOLERANCE_MS) {
                report.add(SessionVerdict.Status.FLAGGED, String.format(Locale.US,
                        "Video is %.1f s long but the app recorded for %.1f s: the file may have been replaced or edited",
                        fileMs / 1000.0, expectedDurationMs / 1000.0));
            }
        }
        return report;
    }
}

package com.repcheck.app.analysis;

import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;

import com.repcheck.app.exercise.ExerciseType;
import com.repcheck.app.exercise.TestOutcome;
import com.repcheck.app.exercise.TestSession;
import com.repcheck.app.pose.PoseDetector;
import com.repcheck.app.pose.PosePoint;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Runs the full on-device analysis of a recorded test video:
 * frame timestamps → decode frames → pose estimation → TestSession (RepCounter or JumpDetector)
 * + ContinuityTracker → outcome, with integrity findings merged into the verdict.
 *
 * Rep tests sample ~15 frames per second of video (plenty for counting, fast on budget phones).
 * Jumps use every frame, because jump height depends on precise timing.
 * Call from a background thread.
 */
public class VideoAnalyzer {

    public interface ProgressListener {
        /** fraction 0..1 */
        void onProgress(float fraction);
    }

    public static final class Result {
        public final TestOutcome outcome;
        public final int framesAnalysed;
        public final int framesWithBody;
        public final long durationMs;
        public final double videoFps;

        Result(TestOutcome outcome, int framesAnalysed, int framesWithBody, long durationMs, double videoFps) {
            this.outcome = outcome;
            this.framesAnalysed = framesAnalysed;
            this.framesWithBody = framesWithBody;
            this.durationMs = durationMs;
            this.videoFps = videoFps;
        }
    }

    /** Frames decoded per call. Bigger is faster but uses more memory. */
    private static final int BATCH = 10;

    private final Context context;
    private final AtomicBoolean cancelled = new AtomicBoolean(false);

    public VideoAnalyzer(Context context) {
        this.context = context.getApplicationContext();
    }

    public void cancel() {
        cancelled.set(true);
    }

    /**
     * @param statureCm          athlete's height, used as the scale reference for jumps
     * @param expectedDurationMs how long the app recorded for by its own clock (≤0 if unknown)
     * @return null if cancelled
     */
    public Result analyze(File video, ExerciseType exercise, double statureCm, long expectedDurationMs,
                          ProgressListener progress) throws IOException {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        PoseDetector detector = null;
        try {
            retriever.setDataSource(video.getAbsolutePath());
            long durationMs = parseLong(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
            int frameCount = (int) parseLong(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT));
            if (durationMs <= 0) throw new IOException("Video is empty or unreadable");
            double videoFps = frameCount > 0 ? frameCount * 1000.0 / durationMs : 0;

            // Timing check reads only the container's timestamps: fast, no decoding.
            IntegrityReport integrity = FrameTiming.check(readFrameTimestamps(video), expectedDurationMs);

            detector = PoseDetector.forVideo(context);
            TestSession session = exercise.newSession(statureCm);
            Tally tally = new Tally();

            if (frameCount > 0) {
                analyzeByIndex(retriever, frameCount, videoFps, exercise.analysisFps(),
                        detector, session, tally, progress);
            } else {
                // Some devices don't report a frame count; fall back to seeking by time (slower).
                analyzeByTime(retriever, durationMs, Math.min(exercise.analysisFps(), 30),
                        detector, session, tally, progress);
            }
            if (cancelled.get()) return null;

            TestOutcome performance = session.finish(tally.analysed, tally.withBody, videoFps);
            integrity.addAll(tally.continuity.report());
            TestOutcome outcome = withIntegrity(performance, integrity);
            return new Result(outcome, tally.analysed, tally.withBody, durationMs, videoFps);
        } catch (RuntimeException e) {
            throw new IOException("Analysis failed: " + e.getMessage(), e);
        } finally {
            if (detector != null) detector.close();
            retriever.release();
        }
    }

    private void analyzeByIndex(MediaMetadataRetriever retriever, int frameCount, double fps, double targetFps,
                                PoseDetector detector, TestSession session, Tally tally,
                                ProgressListener progress) {
        int stride = Math.max(1, (int) Math.round(fps / targetFps));

        MediaMetadataRetriever.BitmapParams params = new MediaMetadataRetriever.BitmapParams();
        params.setPreferredConfig(Bitmap.Config.ARGB_8888); // MediaPipe needs ARGB_8888

        // Decode consecutive batches: seeking to single frames would re-decode from the last keyframe every time.
        for (int start = 0; start < frameCount && !cancelled.get(); start += BATCH) {
            int n = Math.min(BATCH, frameCount - start);
            List<Bitmap> frames = retriever.getFramesAtIndex(start, n, params);
            for (int j = 0; j < frames.size(); j++) {
                int index = start + j;
                Bitmap frame = frames.get(j);
                if (index % stride == 0) {
                    long ts = Math.round(index * 1000.0 / fps);
                    process(frame, ts, detector, session, tally);
                }
                frame.recycle();
            }
            progress.onProgress(Math.min(1f, (start + n) / (float) frameCount));
        }
    }

    private void analyzeByTime(MediaMetadataRetriever retriever, long durationMs, double targetFps,
                               PoseDetector detector, TestSession session, Tally tally,
                               ProgressListener progress) {
        long stepMs = Math.round(1000 / targetFps);
        for (long t = 0; t < durationMs && !cancelled.get(); t += stepMs) {
            Bitmap frame = retriever.getFrameAtTime(t * 1000, MediaMetadataRetriever.OPTION_CLOSEST);
            if (frame == null) continue;
            if (frame.getConfig() != Bitmap.Config.ARGB_8888) {
                Bitmap converted = frame.copy(Bitmap.Config.ARGB_8888, false);
                frame.recycle();
                frame = converted;
            }
            process(frame, t, detector, session, tally);
            frame.recycle();
            progress.onProgress(Math.min(1f, t / (float) durationMs));
        }
    }

    private void process(Bitmap frame, long ts, PoseDetector detector, TestSession session, Tally tally) {
        // MediaPipe's video mode requires strictly increasing timestamps.
        if (ts <= tally.lastTs) ts = tally.lastTs + 1;
        tally.lastTs = ts;

        List<PosePoint> points = detector.detectVideoFrame(frame, ts);
        tally.continuity.feed(points, ts);
        tally.analysed++;
        if (session.feed(points, ts)) tally.withBody++;
    }

    /** The performance result with the video-integrity findings folded into its verdict and breakdown. */
    private static TestOutcome withIntegrity(TestOutcome o, IntegrityReport integrity) {
        String details = o.details == null || o.details.isEmpty()
                ? integrity.summary() : o.details + "\n" + integrity.summary();
        return new TestOutcome(o.score, o.unit, o.scoreLabel, integrity.applyTo(o.verdict), details,
                o.valid, o.partial, o.badForm, o.tooFast);
    }

    /** Presentation time (µs) of every video frame, read from the file's index without decoding. */
    static long[] readFrameTimestamps(File video) throws IOException {
        MediaExtractor ex = new MediaExtractor();
        try {
            ex.setDataSource(video.getAbsolutePath());
            int track = -1;
            for (int i = 0; i < ex.getTrackCount(); i++) {
                String mime = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("video/")) {
                    track = i;
                    break;
                }
            }
            if (track < 0) return new long[0];
            ex.selectTrack(track);
            List<Long> times = new ArrayList<>();
            while (true) {
                long t = ex.getSampleTime();
                if (t < 0) break;
                times.add(t);
                if (!ex.advance()) break;
            }
            long[] out = new long[times.size()];
            for (int i = 0; i < out.length; i++) out[i] = times.get(i);
            return out;
        } finally {
            ex.release();
        }
    }

    private static long parseLong(String s) {
        try {
            return s == null ? 0 : Long.parseLong(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static final class Tally {
        int analysed;
        int withBody;
        long lastTs = -1;
        final ContinuityTracker continuity = new ContinuityTracker();
    }
}

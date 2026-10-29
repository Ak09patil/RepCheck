package com.repcheck.app.pose;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.SystemClock;

import com.google.mediapipe.framework.image.BitmapImageBuilder;
import com.google.mediapipe.framework.image.MPImage;
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark;
import com.google.mediapipe.tasks.core.BaseOptions;
import com.google.mediapipe.tasks.core.Delegate;
import com.google.mediapipe.tasks.vision.core.RunningMode;
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker;
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Wraps MediaPipe Pose Landmarker in one of two modes:
 *  - forLiveStream(): camera preview frames in, results out asynchronously (framing guide while recording)
 *  - forVideo():      frames of a saved video in, results returned directly (the real analysis)
 */
public class PoseDetector {

    public static final String MODEL = "pose_landmarker_lite.task";

    public interface Listener {
        /** Called on a MediaPipe background thread. Empty list = nobody found. */
        void onPose(List<PosePoint> points, int imageWidth, int imageHeight, long timestampMs);

        void onError(String message);
    }

    private PoseLandmarker landmarker;

    private PoseDetector(PoseLandmarker landmarker) {
        this.landmarker = landmarker;
    }

    /** @throws IllegalStateException if the model can't be loaded */
    public static PoseDetector forLiveStream(Context context, Listener listener) {
        PoseLandmarker.PoseLandmarkerOptions options = baseOptions()
                .setRunningMode(RunningMode.LIVE_STREAM)
                .setResultListener((PoseLandmarkerResult result, MPImage input) ->
                        listener.onPose(toPoints(result, input.getWidth(), input.getHeight()),
                                input.getWidth(), input.getHeight(), result.timestampMs()))
                .setErrorListener(e -> listener.onError(e.getMessage()))
                .build();
        return new PoseDetector(create(context, options));
    }

    /** @throws IllegalStateException if the model can't be loaded */
    public static PoseDetector forVideo(Context context) {
        PoseLandmarker.PoseLandmarkerOptions options = baseOptions()
                .setRunningMode(RunningMode.VIDEO)
                .build();
        return new PoseDetector(create(context, options));
    }

    private static PoseLandmarker.PoseLandmarkerOptions.Builder baseOptions() {
        return PoseLandmarker.PoseLandmarkerOptions.builder()
                .setBaseOptions(BaseOptions.builder()
                        .setModelAssetPath(MODEL)
                        .setDelegate(Delegate.CPU) // GPU support varies a lot on budget phones
                        .build())
                .setNumPoses(1)
                .setMinPoseDetectionConfidence(0.5f)
                .setMinPosePresenceConfidence(0.5f)
                .setMinTrackingConfidence(0.5f);
    }

    private static PoseLandmarker create(Context context, PoseLandmarker.PoseLandmarkerOptions options) {
        try {
            return PoseLandmarker.createFromOptions(context, options);
        } catch (RuntimeException e) {
            throw new IllegalStateException("Could not load the pose model. Check that "
                    + MODEL + " is in app/src/main/assets.", e);
        }
    }

    /** Live-stream mode. Bitmap must be upright (and mirrored for the front camera). */
    public void detectAsync(Bitmap bitmap) {
        if (landmarker == null) return;
        MPImage image = new BitmapImageBuilder(bitmap).build();
        landmarker.detectAsync(image, SystemClock.uptimeMillis());
    }

    /**
     * Video mode. Timestamps must strictly increase across calls.
     * @return 33 landmarks in pixel coordinates, or an empty list if nobody was found
     */
    public List<PosePoint> detectVideoFrame(Bitmap bitmap, long timestampMs) {
        if (landmarker == null) return Collections.emptyList();
        MPImage image = new BitmapImageBuilder(bitmap).build();
        PoseLandmarkerResult result = landmarker.detectForVideo(image, timestampMs);
        return toPoints(result, bitmap.getWidth(), bitmap.getHeight());
    }

    /** Scales MediaPipe's 0..1 coordinates to pixels so angles aren't distorted by the frame's aspect ratio. */
    private static List<PosePoint> toPoints(PoseLandmarkerResult result, int w, int h) {
        if (result.landmarks().isEmpty()) return Collections.emptyList();
        List<NormalizedLandmark> raw = result.landmarks().get(0);
        List<PosePoint> points = new ArrayList<>(raw.size());
        for (NormalizedLandmark lm : raw) {
            points.add(new PosePoint(lm.x() * w, lm.y() * h, lm.visibility().orElse(0f)));
        }
        return points;
    }

    public void close() {
        if (landmarker != null) {
            landmarker.close();
            landmarker = null;
        }
    }
}

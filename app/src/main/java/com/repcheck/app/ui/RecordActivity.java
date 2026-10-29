package com.repcheck.app.ui;

import android.Manifest;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Matrix;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.Lifecycle;
import androidx.camera.core.AspectRatio;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.video.FallbackStrategy;
import androidx.camera.video.FileOutputOptions;
import androidx.camera.video.Quality;
import androidx.camera.video.QualitySelector;
import androidx.camera.video.Recorder;
import androidx.camera.video.Recording;
import androidx.camera.video.VideoCapture;
import androidx.camera.video.VideoRecordEvent;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;
import com.repcheck.app.R;
import com.repcheck.app.databinding.ActivityRecordBinding;
import com.repcheck.app.exercise.ExerciseType;
import com.repcheck.app.pose.PoseDetector;
import com.repcheck.app.pose.PosePoint;

import java.io.File;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Records the test video. A light live pose check (~5 fps) tells the athlete whether
 * they're fully in frame; the real measurement happens afterwards on the saved file
 * in AnalysisActivity.
 *
 * Flow: IDLE ─Record─▶ COUNTDOWN (3-2-1) ─▶ RECORDING ─Stop / time limit─▶ analysis screen
 */
public class RecordActivity extends AppCompatActivity implements PoseDetector.Listener {

    public static final String EXTRA_EXERCISE = "exercise";
    private static final String TAG = "RecordActivity";

    static final long MIN_RECORDING_MS = 3_000;
    private static final long LIVE_CHECK_INTERVAL_MS = 200; // ~5 fps is enough for a framing guide
    private static final double FRAMING_MIN_VISIBILITY = 0.5;

    private enum State { IDLE, COUNTDOWN, RECORDING, FINISHING }

    private ActivityRecordBinding binding;
    private ExerciseType exercise;
    private State state = State.IDLE;

    private ProcessCameraProvider cameraProvider;
    private VideoCapture<Recorder> videoCapture;
    private Recording recording;
    private File videoFile;
    private long recordingStartedAt;
    /**
     * When the camera actually started writing, and when we asked it to stop, by the phone's own clock.
     * The difference is checked against the saved file's length to catch a replaced or trimmed file.
     */
    private long cameraStartedAt = -1, stopRequestedAt = -1;
    private boolean discardRecording;

    private ExecutorService analysisExecutor;
    private volatile PoseDetector liveDetector;
    private volatile int lensFacing = CameraSelector.LENS_FACING_BACK;
    private long lastLiveCheck;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private final ActivityResultLauncher<String> cameraPermission =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) startCamera();
                else {
                    Toast.makeText(this, R.string.camera_permission_needed, Toast.LENGTH_LONG).show();
                    finish();
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        exercise = ExerciseType.valueOf(getIntent().getStringExtra(EXTRA_EXERCISE));
        // Lying exercises are easier to frame in landscape; squats in portrait.
        setRequestedOrientation(exercise.landscape
                ? ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                : ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        binding = ActivityRecordBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        binding.txtExercise.setText(exercise.displayName);
        binding.txtHint.setText(exercise.setupHint);
        showFraming(false);
        updateTimer(0);

        analysisExecutor = Executors.newSingleThreadExecutor();
        analysisExecutor.execute(() -> {
            try {
                liveDetector = PoseDetector.forLiveStream(this, this);
            } catch (IllegalStateException e) {
                onError(e.getMessage()); // recording still works, just without the framing guide
            }
        });

        binding.btnRecord.setOnClickListener(v -> onRecordPressed());
        binding.btnFlip.setOnClickListener(v -> {
            lensFacing = lensFacing == CameraSelector.LENS_FACING_BACK
                    ? CameraSelector.LENS_FACING_FRONT
                    : CameraSelector.LENS_FACING_BACK;
            bindCamera();
        });

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            cameraPermission.launch(Manifest.permission.CAMERA);
        }
    }

    // ---------- Camera setup ----------

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            // Push-ups and sit-ups rotate the screen to landscape, which closes and rebuilds this screen.
            // The closed copy can still get this callback: it must not touch the camera or show errors.
            if (isGone()) return;
            try {
                cameraProvider = future.get();
                bindCamera();
            } catch (Exception e) {
                Log.e(TAG, "Camera provider failed", e);
                onError(getString(R.string.camera_unavailable, e.getClass().getSimpleName() + ": " + e.getMessage()));
            }
        }, ContextCompat.getMainExecutor(this));
    }

    @SuppressWarnings("deprecation")
    private void bindCamera() {
        if (cameraProvider == null || isGone()) return;
        int rotation = getWindowManager().getDefaultDisplay().getRotation();

        // Use the requested camera if the device has it, otherwise whichever camera exists.
        CameraSelector selector = pickCamera();
        if (selector == null) {
            onError(getString(R.string.no_camera));
            binding.btnRecord.setEnabled(false);
            return;
        }
        binding.btnFlip.setVisibility(hasBothCameras() ? View.VISIBLE : View.GONE);

        // Try the best setup first and fall back step by step. Phones (and emulators) differ in
        // which combinations of preview + recording + live analysis their camera can run at once.
        Exception last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            Preview preview = new Preview.Builder()
                    .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                    .setTargetRotation(rotation)
                    .build();
            preview.setSurfaceProvider(binding.previewView.getSurfaceProvider());

            // SD (480p) keeps files small for slow uploads and makes on-device decoding quick.
            // Last attempt: any quality the camera offers.
            QualitySelector quality = attempt < 2
                    ? QualitySelector.from(Quality.SD, FallbackStrategy.higherQualityOrLowerThan(Quality.SD))
                    : QualitySelector.from(Quality.LOWEST, FallbackStrategy.higherQualityThan(Quality.LOWEST));
            videoCapture = VideoCapture.withOutput(new Recorder.Builder().setQualitySelector(quality).build());
            videoCapture.setTargetRotation(rotation);

            try {
                cameraProvider.unbindAll();
                if (attempt == 0) {
                    ImageAnalysis analysis = new ImageAnalysis.Builder()
                            .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                            .setTargetRotation(rotation)
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                            .build();
                    analysis.setAnalyzer(analysisExecutor, this::analyzeLiveFrame);
                    cameraProvider.bindToLifecycle(this, selector, preview, videoCapture, analysis);
                } else {
                    // Without live analysis there's no framing guide, but recording still works.
                    cameraProvider.bindToLifecycle(this, selector, preview, videoCapture);
                    binding.txtFraming.setText("");
                    binding.overlay.clear();
                }
                if (attempt > 0) Log.w(TAG, "Camera bound with fallback setup " + attempt, last);
                binding.btnRecord.setEnabled(true);
                return;
            } catch (Exception e) {
                last = e;
            }
        }
        Log.e(TAG, "Could not start the camera", last);
        binding.btnRecord.setEnabled(false);
        onError(getString(R.string.camera_unavailable,
                last == null ? "" : last.getClass().getSimpleName() + ": " + last.getMessage()));
    }

    /** The requested lens if present, else the other one; null if the device has no usable camera. */
    private CameraSelector pickCamera() {
        int[] order = lensFacing == CameraSelector.LENS_FACING_BACK
                ? new int[]{CameraSelector.LENS_FACING_BACK, CameraSelector.LENS_FACING_FRONT}
                : new int[]{CameraSelector.LENS_FACING_FRONT, CameraSelector.LENS_FACING_BACK};
        for (int lens : order) {
            CameraSelector s = new CameraSelector.Builder().requireLensFacing(lens).build();
            try {
                if (cameraProvider.hasCamera(s)) {
                    lensFacing = lens;
                    return s;
                }
            } catch (Exception ignored) {
                // hasCamera throws if the camera service is unavailable; try the next one
            }
        }
        return null;
    }

    private boolean hasBothCameras() {
        try {
            return cameraProvider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)
                    && cameraProvider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA);
        } catch (Exception e) {
            return false;
        }
    }

    // ---------- Live framing guide (analysisExecutor thread) ----------

    private void analyzeLiveFrame(@NonNull ImageProxy image) {
        long now = SystemClock.uptimeMillis();
        if (liveDetector == null || now - lastLiveCheck < LIVE_CHECK_INTERVAL_MS) {
            image.close();
            return;
        }
        lastLiveCheck = now;

        Bitmap frame = image.toBitmap();
        int rotation = image.getImageInfo().getRotationDegrees();
        image.close();

        Matrix m = new Matrix();
        m.postRotate(rotation);
        if (lensFacing == CameraSelector.LENS_FACING_FRONT) m.postScale(-1f, 1f); // match mirrored preview
        Bitmap upright = Bitmap.createBitmap(frame, 0, 0, frame.getWidth(), frame.getHeight(), m, true);

        PoseDetector d = liveDetector;
        if (d != null) d.detectAsync(upright);
    }

    @Override
    public void onPose(List<PosePoint> points, int imageWidth, int imageHeight, long timestampMs) {
        boolean framed = exercise.visibleSide(points, FRAMING_MIN_VISIBILITY) != null;
        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) return;
            if (points.isEmpty()) binding.overlay.clear();
            else binding.overlay.setPose(points, imageWidth, imageHeight, framed);
            showFraming(framed);
        });
    }

    @Override
    public void onError(String message) {
        runOnUiThread(() -> {
            if (!isGone()) Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        });
    }

    /** True once this copy of the screen is closing or closed (e.g. replaced after rotating). */
    private boolean isGone() {
        return isFinishing() || isDestroyed()
                || getLifecycle().getCurrentState() == Lifecycle.State.DESTROYED;
    }

    private void showFraming(boolean framed) {
        binding.txtFraming.setText(framed ? R.string.framing_ok : R.string.framing_bad);
        binding.txtFraming.setTextColor(Color.parseColor(framed ? "#4ADE80" : "#FBBF24"));
    }

    // ---------- Recording ----------

    private void onRecordPressed() {
        switch (state) {
            case IDLE: startCountdown(); break;
            case COUNTDOWN: cancelCountdown(); break;
            case RECORDING: stopRecording(false); break;
            default: break;
        }
    }

    private void startCountdown() {
        if (videoCapture == null) return;
        state = State.COUNTDOWN;
        binding.btnRecord.setText(R.string.cancel);
        binding.btnFlip.setEnabled(false);
        binding.txtCountdown.setVisibility(View.VISIBLE);
        countdownTick(3);
    }

    private void countdownTick(int n) {
        if (state != State.COUNTDOWN) return;
        if (n == 0) {
            binding.txtCountdown.setVisibility(View.GONE);
            startRecording();
            return;
        }
        binding.txtCountdown.setText(String.valueOf(n));
        handler.postDelayed(() -> countdownTick(n - 1), 1000);
    }

    private void cancelCountdown() {
        handler.removeCallbacksAndMessages(null);
        binding.txtCountdown.setVisibility(View.GONE);
        resetToIdle();
    }

    private void startRecording() {
        File dir = new File(getFilesDir(), "videos"); // app-private: other apps can't swap the file
        if (!dir.exists() && !dir.mkdirs()) {
            onError("Couldn't create video folder");
            resetToIdle();
            return;
        }
        cameraStartedAt = stopRequestedAt = -1;
        videoFile = new File(dir, exercise.name().toLowerCase(Locale.US) + "_" + System.currentTimeMillis() + ".mp4");
        discardRecording = false;

        // No .withAudioEnabled(): audio isn't needed, so we don't ask for the microphone.
        recording = videoCapture.getOutput()
                .prepareRecording(this, new FileOutputOptions.Builder(videoFile).build())
                .start(ContextCompat.getMainExecutor(this), this::onRecordEvent);

        state = State.RECORDING;
        recordingStartedAt = SystemClock.elapsedRealtime();
        binding.btnRecord.setText(R.string.stop);
        tickTimer();
    }

    private void tickTimer() {
        if (state != State.RECORDING) return;
        long elapsed = SystemClock.elapsedRealtime() - recordingStartedAt;
        updateTimer(elapsed);
        if (elapsed >= exercise.testDurationMs()) {
            stopRecording(false);
            return;
        }
        handler.postDelayed(this::tickTimer, 250);
    }

    private void updateTimer(long elapsedMs) {
        long s = elapsedMs / 1000;
        long max = exercise.testDurationMs() / 1000;
        binding.txtTimer.setText(String.format(Locale.US, "%d:%02d / %d:%02d", s / 60, s % 60, max / 60, max % 60));
    }

    private void stopRecording(boolean discard) {
        if (recording == null) return;
        long elapsed = SystemClock.elapsedRealtime() - recordingStartedAt;
        if (!discard && elapsed < MIN_RECORDING_MS) {
            Toast.makeText(this, R.string.too_short, Toast.LENGTH_SHORT).show();
            discard = true;
        }
        discardRecording = discard;
        state = State.FINISHING;
        handler.removeCallbacksAndMessages(null);
        binding.btnRecord.setEnabled(false);
        stopRequestedAt = SystemClock.elapsedRealtime();
        recording.stop(); // result arrives in onRecordEvent as a Finalize event
        recording = null;
    }

    private void onRecordEvent(VideoRecordEvent event) {
        if (event instanceof VideoRecordEvent.Start) {
            cameraStartedAt = SystemClock.elapsedRealtime();
            return;
        }
        if (!(event instanceof VideoRecordEvent.Finalize)) return;
        VideoRecordEvent.Finalize fin = (VideoRecordEvent.Finalize) event;

        boolean failed = fin.hasError();
        if (discardRecording || failed || !videoFile.exists()) {
            if (failed && !discardRecording) onError("Recording failed (code " + fin.getError() + "). Try again.");
            //noinspection ResultOfMethodCallIgnored
            videoFile.delete();
            resetToIdle();
            return;
        }

        Intent intent = new Intent(this, AnalysisActivity.class);
        intent.putExtra(AnalysisActivity.EXTRA_EXERCISE, exercise.name());
        intent.putExtra(AnalysisActivity.EXTRA_VIDEO_PATH, videoFile.getAbsolutePath());
        long expected = cameraStartedAt > 0 && stopRequestedAt > cameraStartedAt
                ? stopRequestedAt - cameraStartedAt : -1;
        intent.putExtra(AnalysisActivity.EXTRA_EXPECTED_DURATION_MS, expected);
        startActivity(intent);
        finish();
    }

    private void resetToIdle() {
        state = State.IDLE;
        if (isFinishing() || isDestroyed()) return;
        binding.btnRecord.setEnabled(true);
        binding.btnRecord.setText(R.string.record);
        binding.btnFlip.setEnabled(true);
        updateTimer(0);
    }

    @Override
    protected void onStop() {
        super.onStop();
        // Leaving the app mid-test invalidates the attempt.
        if (state == State.COUNTDOWN) cancelCountdown();
        if (state == State.RECORDING) stopRecording(true);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
        if (cameraProvider != null) cameraProvider.unbindAll();
        analysisExecutor.execute(() -> {
            PoseDetector d = liveDetector;
            liveDetector = null;
            if (d != null) d.close();
        });
        analysisExecutor.shutdown();
    }
}

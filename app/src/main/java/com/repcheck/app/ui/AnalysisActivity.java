package com.repcheck.app.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;

import com.repcheck.app.R;
import com.repcheck.app.analysis.FileHasher;
import com.repcheck.app.analysis.SessionVerdict;
import com.repcheck.app.cloud.SyncWorker;
import com.repcheck.app.progress.Badges;
import com.repcheck.app.progress.Benchmarks;
import com.repcheck.app.progress.Rating;
import com.repcheck.app.analysis.VideoAnalyzer;
import com.repcheck.app.data.AppDatabase;
import com.repcheck.app.data.Athlete;
import com.repcheck.app.data.TestResult;
import com.repcheck.app.databinding.ActivityAnalysisBinding;
import com.repcheck.app.exercise.ExerciseType;
import com.repcheck.app.exercise.TestOutcome;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Analyses the recorded video on the phone, saves the result and shows it.
 * Steps (background thread): hash file → pose analysis → verdict → benchmark rating
 * → save to Room → check for newly earned badges.
 */
public class AnalysisActivity extends AppCompatActivity {

    public static final String EXTRA_EXERCISE = "exercise";
    public static final String EXTRA_VIDEO_PATH = "video_path";
    /** How long RecordActivity recorded for by its own clock; -1 if unknown. */
    public static final String EXTRA_EXPECTED_DURATION_MS = "expected_duration_ms";

    private ActivityAnalysisBinding binding;
    private ExerciseType exercise;
    private VideoAnalyzer analyzer;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private boolean done;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityAnalysisBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        exercise = ExerciseType.valueOf(getIntent().getStringExtra(EXTRA_EXERCISE));
        File video = new File(getIntent().getStringExtra(EXTRA_VIDEO_PATH));
        binding.txtExercise.setText(exercise.displayName);

        binding.btnDone.setOnClickListener(v -> finish());
        binding.btnRetake.setOnClickListener(v -> {
            Intent intent = new Intent(this, RecordActivity.class);
            intent.putExtra(RecordActivity.EXTRA_EXERCISE, exercise.name());
            startActivity(intent);
            finish();
        });

        // Backing out mid-analysis cancels it and throws the video away, so it never half-saves.
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (!done) {
                    analyzer.cancel();
                    //noinspection ResultOfMethodCallIgnored
                    video.delete();
                }
                finish();
            }
        });

        analyzer = new VideoAnalyzer(this);
        worker.execute(() -> run(video));
    }

    /** Background thread. */
    private void run(File video) {
        try {
            String hash = FileHasher.sha256(video);

            AppDatabase db = AppDatabase.get(this);
            Athlete athlete = db.athleteDao().getCurrent();
            if (athlete == null) throw new IllegalStateException("No athlete profile");

            long expectedMs = getIntent().getLongExtra(EXTRA_EXPECTED_DURATION_MS, -1);
            VideoAnalyzer.Result r = analyzer.analyze(video, exercise, athlete.heightCm, expectedMs, fraction ->
                    runOnUiThread(() -> {
                        int pct = Math.round(fraction * 100);
                        binding.progress.setProgressCompat(pct, true);
                        binding.txtProgress.setText(getString(R.string.analysing_pct, pct));
                    }));
            if (r == null) return; // cancelled
            TestOutcome o = r.outcome;

            TestResult t = new TestResult();
            t.athleteId = athlete.id;
            t.exercise = exercise.name();
            t.recordedAt = video.lastModified();
            t.videoDurationMs = r.durationMs;
            t.videoFps = r.videoFps;
            t.scoreValue = o.score;
            t.scoreUnit = o.unit;
            t.validReps = o.valid;
            t.partialReps = o.partial;
            t.badFormReps = o.badForm;
            t.tooFastReps = o.tooFast;
            t.framesAnalysed = r.framesAnalysed;
            t.framesWithBody = r.framesWithBody;
            t.status = o.verdict.status.name();
            t.reasons = o.verdict.reasonsText();
            t.details = o.details;
            t.videoPath = video.getAbsolutePath();
            t.videoSha256 = hash;
            t.synced = false;

            // Benchmark only verified attempts: a rating on a flagged video would mean nothing.
            boolean verified = o.verdict.status == SessionVerdict.Status.VERIFIED;
            Rating rating = verified
                    ? Benchmarks.rate(exercise, athlete.gender, athlete.ageOn(t.recordedAt), o.score)
                    : null;
            t.ratingLevel = rating == null ? 0 : rating.level;

            // Badges before and after this attempt, to celebrate any new ones.
            List<TestResult> history = new ArrayList<>(db.testResultDao().all(athlete.id));
            List<Badges.Badge> before = Badges.evaluate(ResultFormat.toAttempts(history));
            t.id = db.testResultDao().insert(t);
            SyncWorker.enqueue(getApplicationContext()); // uploads now, or whenever there's a connection
            history.add(t);
            List<Badges.Badge> fresh = Badges.newlyEarned(before,
                    Badges.evaluate(ResultFormat.toAttempts(history)));

            runOnUiThread(() -> showResult(t, o.scoreLabel, rating, verified, fresh));
        } catch (Exception e) {
            runOnUiThread(() -> showError(e.getMessage()));
        }
    }

    private void showResult(TestResult t, String scoreLabel, Rating rating, boolean verified,
                            List<Badges.Badge> newBadges) {
        if (isFinishing() || isDestroyed()) return;
        done = true;
        binding.panelProgress.setVisibility(View.GONE);
        binding.panelResult.setVisibility(View.VISIBLE);

        showRating(rating, verified);
        if (!newBadges.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (Badges.Badge b : newBadges) {
                if (sb.length() > 0) sb.append('\n');
                sb.append(b.emoji).append("  ").append(b.title).append(" — ").append(b.description);
            }
            binding.txtNewBadges.setText(sb);
            binding.cardNewBadges.setVisibility(View.VISIBLE);
        }

        binding.txtScore.setText(ResultFormat.number(t.scoreValue));
        binding.txtScoreLabel.setText(scoreLabel);
        binding.txtStatus.setText(ResultFormat.statusLabel(t.status));
        binding.txtStatus.setTextColor(ResultFormat.statusColor(t.status));
        binding.txtReasons.setText(bullets(t.reasons));
        String details = t.details == null || t.details.isEmpty() ? "" : t.details + "\n\n";
        binding.txtBreakdown.setText(details + getString(R.string.video_stats,
                t.videoDurationMs / 1000.0,
                t.framesAnalysed == 0 ? 0 : Math.round(100.0 * t.framesWithBody / t.framesAnalysed)));
        binding.txtHash.setText(getString(R.string.hash_label, t.videoSha256.substring(0, 16)));
    }

    private void showRating(Rating rating, boolean verified) {
        if (rating != null) {
            binding.txtRating.setText(rating.label());
            binding.txtRating.setTextColor(ResultFormat.ratingColor(rating.level));
            StringBuilder detail = new StringBuilder(getString(R.string.rating_group, rating.group));
            String next = rating.nextStep();
            if (next != null) detail.append('\n').append(next);
            if (rating.provisional) detail.append('\n').append(getString(R.string.rating_provisional));
            binding.txtRatingDetail.setText(detail);
        } else if (!verified) {
            binding.txtRating.setText(R.string.rating_not_verified_title);
            binding.txtRatingDetail.setText(R.string.rating_not_verified);
        } else if (!Benchmarks.hasBenchmark(exercise)) {
            binding.txtRating.setText(R.string.rating_none_title);
            binding.txtRatingDetail.setText(R.string.rating_no_benchmark);
        } else {
            binding.txtRating.setText(R.string.rating_none_title);
            binding.txtRatingDetail.setText(R.string.rating_no_group);
        }
        binding.cardRating.setVisibility(View.VISIBLE);
    }

    private void showError(String message) {
        if (isFinishing() || isDestroyed()) return;
        done = true;
        binding.panelProgress.setVisibility(View.GONE);
        binding.panelResult.setVisibility(View.VISIBLE);
        binding.txtScore.setText("—");
        binding.txtScoreLabel.setText("");
        binding.txtStatus.setText(R.string.analysis_failed);
        binding.txtReasons.setText(message);
    }

    private static String bullets(String lines) {
        if (lines == null || lines.isEmpty()) return "";
        return "• " + lines.replace("\n", "\n• ");
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (!done) analyzer.cancel();
        worker.shutdown();
    }
}

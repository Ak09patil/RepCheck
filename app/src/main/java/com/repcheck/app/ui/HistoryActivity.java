package com.repcheck.app.ui;

import android.os.Bundle;
import android.view.View;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.chip.Chip;
import com.repcheck.app.R;
import com.repcheck.app.analysis.SessionVerdict;
import com.repcheck.app.data.AppDatabase;
import com.repcheck.app.data.Athlete;
import com.repcheck.app.data.TestResult;
import com.repcheck.app.databinding.ActivityHistoryBinding;
import com.repcheck.app.exercise.ExerciseType;
import com.repcheck.app.progress.Benchmarks;
import com.repcheck.app.progress.Rating;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Progress per test: chart against benchmarks, personal best, and every attempt. */
public class HistoryActivity extends AppCompatActivity {

    private ActivityHistoryBinding binding;
    private final ResultAdapter adapter = new ResultAdapter();
    private Athlete athlete;
    private List<TestResult> all = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityHistoryBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        binding.listAttempts.setAdapter(adapter);

        for (ExerciseType type : ExerciseType.values()) {
            Chip chip = (Chip) getLayoutInflater().inflate(R.layout.item_chip, binding.chipsTests, false);
            chip.setText(type.displayName);
            chip.setTag(type);
            chip.setId(View.generateViewId());
            binding.chipsTests.addView(chip);
        }
        binding.chipsTests.setOnCheckedStateChangeListener((group, ids) -> {
            if (ids.isEmpty()) return;
            show((ExerciseType) group.findViewById(ids.get(0)).getTag());
        });

        AppDatabase db = AppDatabase.get(this);
        AppDatabase.IO.execute(() -> {
            Athlete a = db.athleteDao().getCurrent();
            List<TestResult> results = a == null ? new ArrayList<>() : db.testResultDao().all(a.id);
            runOnUiThread(() -> {
                athlete = a;
                all = results;
                // Start on the most recently used test.
                ExerciseType start = results.isEmpty() ? ExerciseType.SIT_UP
                        : ExerciseType.valueOf(results.get(results.size() - 1).exercise);
                binding.chipsTests.check(chipFor(start).getId());
            });
        });
    }

    private Chip chipFor(ExerciseType type) {
        for (int i = 0; i < binding.chipsTests.getChildCount(); i++) {
            Chip c = (Chip) binding.chipsTests.getChildAt(i);
            if (c.getTag() == type) return c;
        }
        throw new IllegalStateException();
    }

    private void show(ExerciseType type) {
        if (athlete == null) return;
        List<TestResult> forTest = new ArrayList<>();
        for (TestResult r : all) if (type.name().equals(r.exercise)) forTest.add(r);

        List<ProgressChartView.Point> points = new ArrayList<>();
        double best = -1;
        TestResult bestResult = null;
        int latestRating = 0;
        for (TestResult r : forTest) {
            boolean verified = SessionVerdict.Status.VERIFIED.name().equals(r.status);
            points.add(new ProgressChartView.Point(r.scoreValue, verified));
            if (verified && r.scoreValue > best) {
                best = r.scoreValue;
                bestResult = r;
            }
            if (verified && r.ratingLevel > 0) latestRating = r.ratingLevel;
        }
        binding.chart.setData(points, Benchmarks.cutoffs(type, athlete.gender, athlete.age()));

        binding.txtBest.setText(bestResult == null ? "—" : ResultFormat.score(bestResult));
        binding.txtAttempts.setText(String.valueOf(forTest.size()));
        binding.txtLatestRating.setText(latestRating == 0 ? "—" : Rating.label(latestRating));
        binding.txtLatestRating.setTextColor(ResultFormat.ratingColor(latestRating));

        List<TestResult> newestFirst = new ArrayList<>(forTest);
        Collections.reverse(newestFirst);
        adapter.submit(newestFirst);
        binding.txtEmpty.setVisibility(forTest.isEmpty() ? View.VISIBLE : View.GONE);
    }
}

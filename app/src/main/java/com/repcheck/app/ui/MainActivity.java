package com.repcheck.app.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.firebase.auth.FirebaseUser;
import com.repcheck.app.R;
import com.repcheck.app.cloud.Cloud;
import com.repcheck.app.cloud.SubmissionMapper;
import com.repcheck.app.cloud.SyncWorker;
import com.repcheck.app.data.AppDatabase;
import com.repcheck.app.data.Athlete;
import com.repcheck.app.data.TestResult;
import com.repcheck.app.databinding.ActivityMainBinding;
import com.repcheck.app.exercise.ExerciseType;
import com.repcheck.app.progress.Badges;

import java.util.List;
import java.util.Locale;

/** Home: athlete card, test picker and recent attempts. Sends first-time users to the profile screen. */
public class MainActivity extends AppCompatActivity {

    private ActivityMainBinding binding;
    private final ResultAdapter adapter = new ResultAdapter();
    private Athlete athlete;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // An official's phone opens straight into the review dashboard.
        if (Cloud.isOfficial(this)) {
            startActivity(new Intent(this, OfficialActivity.class));
            finish();
            return;
        }
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        binding.listResults.setAdapter(adapter);
        binding.btnEditProfile.setOnClickListener(v ->
                startActivity(new Intent(this, ProfileActivity.class)));
        binding.btnSitUps.setOnClickListener(v -> startTest(ExerciseType.SIT_UP));
        binding.btnVerticalJump.setOnClickListener(v -> startTest(ExerciseType.VERTICAL_JUMP));
        binding.btnPushUps.setOnClickListener(v -> startTest(ExerciseType.PUSH_UP));
        binding.btnSquats.setOnClickListener(v -> startTest(ExerciseType.SQUAT));
        binding.btnHistory.setOnClickListener(v -> startActivity(new Intent(this, HistoryActivity.class)));
        binding.btnBadges.setOnClickListener(v -> startActivity(new Intent(this, BadgesActivity.class)));
        binding.btnLeaderboard.setOnClickListener(v -> startActivity(new Intent(this, LeaderboardActivity.class)));
        binding.btnSync.setOnClickListener(v -> {
            if (Cloud.isSignedIn(this)) {
                Cloud.signOut(this);
                refresh();
            } else {
                startActivity(new Intent(this, LoginActivity.class));
            }
        });
        // Catch up on anything recorded while offline.
        SyncWorker.enqueue(this);

        // Pick up a role changed in the Firebase console since the last sign-in.
        Cloud.refreshRole(this, role -> {
            if (isFinishing()) return;
            if (SubmissionMapper.ROLE_OFFICIAL.equals(role)) {
                startActivity(new Intent(this, OfficialActivity.class));
                finish();
            } else if (!Cloud.isKnownRole(role)) {
                Toast.makeText(this, getString(R.string.err_role_unknown, role), Toast.LENGTH_LONG).show();
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        if (binding == null) return;
        // Reload every time we come back: the profile may have changed or a new result been saved.
        AppDatabase db = AppDatabase.get(this);
        AppDatabase.IO.execute(() -> {
            Athlete a = db.athleteDao().getCurrent();
            List<TestResult> recent = a == null ? null : db.testResultDao().recent(a.id, 20);
            List<Badges.Badge> badges = a == null ? null
                    : Badges.evaluate(ResultFormat.toAttempts(db.testResultDao().all(a.id)));
            int pending = db.testResultDao().countUnsynced();
            runOnUiThread(() -> {
                show(a, recent, badges);
                showSync(pending);
            });
        });
    }

    private void show(Athlete a, List<TestResult> recent, List<Badges.Badge> badges) {
        if (isFinishing()) return;
        if (a == null) {
            startActivity(new Intent(this, ProfileActivity.class));
            finish();
            return;
        }
        athlete = a;
        binding.txtAthleteName.setText(a.name);
        binding.txtAthleteDetails.setText(getString(R.string.athlete_details,
                a.age(), a.genderLabel(), place(a)));
        binding.txtAthleteBody.setText(String.format(Locale.US, "%.0f cm · %.1f kg · BMI %.1f",
                a.heightCm, a.weightKg, a.bmi()));

        binding.btnBadges.setText(getString(R.string.badges_button, Badges.earnedCount(badges), badges.size()));
        adapter.submit(recent);
        binding.txtNoResults.setVisibility(recent.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void showSync(int pending) {
        if (isFinishing()) return;
        if (!Cloud.isConfigured(this)) {
            binding.txtSync.setText(R.string.sync_off);
            binding.btnSync.setVisibility(View.GONE);
            return;
        }
        FirebaseUser user = Cloud.user(this);
        binding.btnSync.setVisibility(View.VISIBLE);
        if (user == null) {
            binding.txtSync.setText(getString(R.string.sync_signed_out, pending));
            binding.btnSync.setText(R.string.sign_in);
        } else {
            binding.txtSync.setText(pending == 0
                    ? getString(R.string.sync_done, user.getEmail())
                    : getString(R.string.sync_pending, user.getEmail(), pending));
            binding.btnSync.setText(R.string.sign_out);
        }
    }

    private static String place(Athlete a) {
        boolean hasDistrict = a.district != null && !a.district.isEmpty();
        boolean hasState = a.state != null && !a.state.isEmpty();
        if (hasDistrict && hasState) return a.district + ", " + a.state;
        if (hasDistrict) return a.district;
        if (hasState) return a.state;
        return "—";
    }

    private void startTest(ExerciseType type) {
        if (athlete == null) return;
        Intent intent = new Intent(this, RecordActivity.class);
        intent.putExtra(RecordActivity.EXTRA_EXERCISE, type.name());
        startActivity(intent);
    }
}

package com.repcheck.app.ui;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.RecyclerView;

import com.repcheck.app.R;
import com.repcheck.app.data.AppDatabase;
import com.repcheck.app.data.Athlete;
import com.repcheck.app.data.TestResult;
import com.repcheck.app.databinding.ActivityBadgesBinding;
import com.repcheck.app.databinding.ItemBadgeBinding;
import com.repcheck.app.progress.Badges;

import java.util.ArrayList;
import java.util.List;

/** All badges; locked ones are faded so the athlete can see what to aim for. */
public class BadgesActivity extends AppCompatActivity {

    private ActivityBadgesBinding binding;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityBadgesBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        AppDatabase db = AppDatabase.get(this);
        AppDatabase.IO.execute(() -> {
            Athlete a = db.athleteDao().getCurrent();
            List<TestResult> results = a == null ? new ArrayList<>() : db.testResultDao().all(a.id);
            List<Badges.Badge> badges = Badges.evaluate(ResultFormat.toAttempts(results));
            runOnUiThread(() -> {
                binding.txtBadgeCount.setText(getString(R.string.badge_count,
                        Badges.earnedCount(badges), badges.size()));
                binding.gridBadges.setAdapter(new BadgeAdapter(badges));
            });
        });
    }

    private static final class BadgeAdapter extends RecyclerView.Adapter<BadgeAdapter.Holder> {
        private final List<Badges.Badge> badges;

        BadgeAdapter(List<Badges.Badge> badges) {
            this.badges = badges;
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new Holder(ItemBadgeBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull Holder h, int position) {
            Badges.Badge b = badges.get(position);
            h.b.txtEmoji.setText(b.earned ? b.emoji : "🔒");
            h.b.txtTitle.setText(b.title);
            h.b.txtDescription.setText(b.description);
            h.b.card.setAlpha(b.earned ? 1f : 0.55f);
        }

        @Override
        public int getItemCount() {
            return badges.size();
        }

        static final class Holder extends RecyclerView.ViewHolder {
            final ItemBadgeBinding b;

            Holder(ItemBadgeBinding b) {
                super(b.getRoot());
                this.b = b;
            }
        }
    }
}

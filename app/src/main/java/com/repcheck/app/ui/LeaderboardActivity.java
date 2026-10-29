package com.repcheck.app.ui;

import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.chip.Chip;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.Query;
import com.repcheck.app.R;
import com.repcheck.app.cloud.Cloud;
import com.repcheck.app.cloud.LeaderboardMapper;
import com.repcheck.app.data.AppDatabase;
import com.repcheck.app.data.Athlete;
import com.repcheck.app.databinding.ActivityLeaderboardBinding;
import com.repcheck.app.databinding.ItemLeaderBinding;
import com.repcheck.app.exercise.ExerciseType;
import com.repcheck.app.progress.Rating;

import java.util.ArrayList;
import java.util.List;

/**
 * Leaderboards per test. Fetches the top scores once per test, then filters on the phone:
 *  - My group: same gender and age group (the fair comparison)
 *  - My state
 *  - Everyone
 */
public class LeaderboardActivity extends AppCompatActivity {

    /** Enough for a class or district; a national rollout would page or pre-split boards by group. */
    private static final int FETCH_LIMIT = 300;

    private static final class Entry {
        String uid, name, gender, ageGroup, state, district, unit;
        double score;
        int ratingLevel;
    }

    private ActivityLeaderboardBinding binding;
    private final LeaderAdapter adapter = new LeaderAdapter();
    private final List<Entry> fetched = new ArrayList<>();
    private Athlete me;
    private String myUid;
    private ExerciseType current = ExerciseType.SIT_UP;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityLeaderboardBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        binding.listEntries.setAdapter(adapter);

        FirebaseUser user = Cloud.user(this);
        if (user == null) {
            message(Cloud.isConfigured(this) ? R.string.leaderboard_sign_in : R.string.leaderboard_offline_build);
            binding.groupScope.setVisibility(View.GONE);
            return;
        }
        myUid = user.getUid();

        for (ExerciseType type : ExerciseType.values()) {
            Chip chip = (Chip) getLayoutInflater().inflate(R.layout.item_chip, binding.chipsTests, false);
            chip.setText(type.displayName);
            chip.setTag(type);
            chip.setId(View.generateViewId());
            binding.chipsTests.addView(chip);
            if (type == current) chip.setChecked(true);
        }
        binding.chipsTests.setOnCheckedStateChangeListener((group, ids) -> {
            if (ids.isEmpty()) return;
            current = (ExerciseType) group.findViewById(ids.get(0)).getTag();
            load();
        });
        binding.groupScope.addOnButtonCheckedListener((group, id, checked) -> {
            if (checked) render();
        });

        AppDatabase db = AppDatabase.get(this);
        AppDatabase.IO.execute(() -> {
            Athlete a = db.athleteDao().getCurrent();
            runOnUiThread(() -> {
                me = a;
                load();
            });
        });
    }

    private void load() {
        message(R.string.leaderboard_loading);
        fetched.clear();
        adapter.submit(new ArrayList<>(), new int[0]);
        ExerciseType requested = current;
        FirebaseFirestore.getInstance()
                .collection(LeaderboardMapper.LEADERBOARDS).document(requested.name())
                .collection(LeaderboardMapper.ENTRIES)
                .orderBy("bestScore", Query.Direction.DESCENDING)
                .limit(FETCH_LIMIT)
                .get()
                .addOnSuccessListener(snap -> {
                    if (requested != current || isFinishing()) return; // user switched test meanwhile
                    for (DocumentSnapshot d : snap.getDocuments()) fetched.add(toEntry(d));
                    render();
                })
                .addOnFailureListener(e -> {
                    if (requested != current || isFinishing()) return;
                    binding.txtMessage.setText(getString(R.string.leaderboard_error, e.getMessage()));
                    binding.txtMessage.setVisibility(View.VISIBLE);
                });
    }

    private void render() {
        int scope = binding.groupScope.getCheckedButtonId();
        String myGroup = me == null ? "" : LeaderboardMapper.ageGroup(me.age());
        String myGender = me == null ? "" : me.gender;
        String myState = me == null || me.state == null ? "" : me.state;

        List<Entry> shown = new ArrayList<>();
        for (Entry e : fetched) {
            if (scope == R.id.btnScopeGroup && !(e.gender.equals(myGender) && e.ageGroup.equals(myGroup))) continue;
            if (scope == R.id.btnScopeState && !e.state.equalsIgnoreCase(myState)) continue;
            shown.add(e);
        }

        if (scope == R.id.btnScopeGroup) {
            binding.txtScope.setText(getString(R.string.scope_group_desc, me == null ? "" : me.genderLabel(), myGroup));
        } else if (scope == R.id.btnScopeState) {
            binding.txtScope.setText(myState.isEmpty() ? getString(R.string.scope_state_missing)
                    : getString(R.string.scope_state_desc, myState));
        } else {
            binding.txtScope.setText(R.string.scope_all_desc);
        }

        double[] scores = new double[shown.size()];
        for (int i = 0; i < scores.length; i++) scores[i] = shown.get(i).score;
        adapter.submit(shown, LeaderboardMapper.ranks(scores));

        boolean meListed = false;
        for (Entry e : shown) meListed |= e.uid.equals(myUid);
        if (shown.isEmpty()) {
            message(me != null && me.showOnLeaderboard ? R.string.leaderboard_empty : R.string.leaderboard_empty_opt_in);
        } else if (!meListed && me != null && !me.showOnLeaderboard) {
            message(R.string.leaderboard_not_opted_in);
        } else {
            binding.txtMessage.setVisibility(View.GONE);
        }
    }

    private void message(int res) {
        binding.txtMessage.setText(res);
        binding.txtMessage.setVisibility(View.VISIBLE);
    }

    private static Entry toEntry(DocumentSnapshot d) {
        Entry e = new Entry();
        e.uid = d.getId();
        e.name = orEmpty(d.getString("displayName"));
        e.gender = orEmpty(d.getString("gender"));
        e.ageGroup = orEmpty(d.getString("ageGroup"));
        e.state = orEmpty(d.getString("state"));
        e.district = orEmpty(d.getString("district"));
        e.unit = orEmpty(d.getString("unit"));
        Double score = d.getDouble("bestScore");
        e.score = score == null ? 0 : score;
        Long rating = d.getLong("ratingLevel");
        e.ratingLevel = rating == null ? 0 : rating.intValue();
        return e;
    }

    private static String orEmpty(String s) {
        return s == null ? "" : s;
    }

    // ---------- List ----------

    private final class LeaderAdapter extends RecyclerView.Adapter<LeaderAdapter.Holder> {
        private final List<Entry> items = new ArrayList<>();
        private int[] ranks = new int[0];

        void submit(List<Entry> list, int[] r) {
            items.clear();
            items.addAll(list);
            ranks = r;
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new Holder(ItemLeaderBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull Holder h, int position) {
            Entry e = items.get(position);
            int rank = ranks[position];
            h.b.txtRank.setText(rank == 1 ? "🥇" : rank == 2 ? "🥈" : rank == 3 ? "🥉" : String.valueOf(rank));

            boolean isMe = e.uid.equals(myUid);
            h.b.txtName.setText(isMe ? getString(R.string.leaderboard_you, e.name) : e.name);
            h.b.txtName.setTypeface(null, isMe ? Typeface.BOLD : Typeface.NORMAL);
            if (isMe) {
                h.b.card.setStrokeColor(ResultFormat.ratingColor(4));
                h.b.card.setStrokeWidth(h.defaultStrokeWidth * 2);
            } else {
                h.b.card.setStrokeColor(h.defaultStrokeColor);
                h.b.card.setStrokeWidth(h.defaultStrokeWidth);
            }

            String where = e.district.isEmpty() ? e.state
                    : e.state.isEmpty() ? e.district : e.district + ", " + e.state;
            h.b.txtWhere.setText((where.isEmpty() ? "" : where + " · ") + e.ageGroup);
            h.b.txtScore.setText(ResultFormat.number(e.score) + " " + e.unit);
            h.b.txtRating.setText(e.ratingLevel > 0 ? Rating.label(e.ratingLevel) : "");
            h.b.txtRating.setTextColor(ResultFormat.ratingColor(e.ratingLevel));
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        final class Holder extends RecyclerView.ViewHolder {
            final ItemLeaderBinding b;
            /** The theme's normal outline, restored when a recycled row isn't "you". */
            final ColorStateList defaultStrokeColor;
            final int defaultStrokeWidth;

            Holder(ItemLeaderBinding b) {
                super(b.getRoot());
                this.b = b;
                defaultStrokeColor = b.card.getStrokeColorStateList();
                defaultStrokeWidth = Math.max(1, b.card.getStrokeWidth());
            }
        }
    }
}

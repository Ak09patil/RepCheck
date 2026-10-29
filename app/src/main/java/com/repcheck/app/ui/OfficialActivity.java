package com.repcheck.app.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.Query;
import com.repcheck.app.R;
import com.repcheck.app.analysis.SessionVerdict;
import com.repcheck.app.cloud.Cloud;
import com.repcheck.app.cloud.LeaderboardMapper;
import com.repcheck.app.cloud.Submission;
import com.repcheck.app.cloud.SubmissionMapper;
import com.repcheck.app.databinding.ActivityOfficialBinding;
import com.repcheck.app.databinding.ItemSubmissionBinding;
import com.repcheck.app.progress.Rating;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The officials' dashboard: a live list of every submission, newest first, with filters,
 * and a detail view where an official approves or rejects the attempt.
 */
public class OfficialActivity extends AppCompatActivity {

    private static final int LIMIT = 300;

    private enum Filter { ALL, TO_REVIEW, FLAGGED, VERIFIED }

    private ActivityOfficialBinding binding;
    private final SubmissionAdapter adapter = new SubmissionAdapter();
    private final List<Submission> all = new ArrayList<>();
    private Filter filter = Filter.ALL;
    private ListenerRegistration listener;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!Cloud.isOfficial(this)) { // signed out, or not an official any more
            startActivity(new Intent(this, MainActivity.class));
            finish();
            return;
        }
        binding = ActivityOfficialBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        binding.listSubmissions.setAdapter(adapter);

        binding.chipsFilter.setOnCheckedStateChangeListener((group, ids) -> {
            if (ids.isEmpty()) return;
            int id = ids.get(0);
            filter = id == R.id.chipToReview ? Filter.TO_REVIEW
                    : id == R.id.chipFlagged ? Filter.FLAGGED
                    : id == R.id.chipVerified ? Filter.VERIFIED : Filter.ALL;
            render();
        });
        binding.btnSignOut.setOnClickListener(v -> {
            Cloud.signOut(this);
            Intent i = new Intent(this, MainActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(i);
            finish();
        });
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (binding == null) return;
        // Live: new uploads and other officials' reviews appear without refreshing.
        listener = FirebaseFirestore.getInstance()
                .collection(SubmissionMapper.SUBMISSIONS)
                .orderBy("recordedAt", Query.Direction.DESCENDING)
                .limit(LIMIT)
                .addSnapshotListener((snap, e) -> {
                    if (e != null) {
                        binding.txtEmpty.setText(getString(R.string.official_error, e.getMessage()));
                        binding.txtEmpty.setVisibility(View.VISIBLE);
                        return;
                    }
                    if (snap == null) return;
                    all.clear();
                    for (DocumentSnapshot d : snap.getDocuments()) all.add(Submission.from(d));
                    render();
                });
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (listener != null) listener.remove();
    }

    private void render() {
        int flagged = 0, review = 0, unreviewed = 0;
        List<Submission> shown = new ArrayList<>();
        for (Submission s : all) {
            boolean isFlagged = SessionVerdict.Status.FLAGGED.name().equals(s.status);
            boolean needsReview = SessionVerdict.Status.NEEDS_REVIEW.name().equals(s.status);
            boolean verified = SessionVerdict.Status.VERIFIED.name().equals(s.status);
            if (isFlagged) flagged++;
            if (needsReview) review++;
            if (!s.isReviewed()) unreviewed++;

            boolean include;
            switch (filter) {
                case TO_REVIEW: include = !s.isReviewed() && (isFlagged || needsReview || s.clockSuspicious()); break;
                case FLAGGED: include = isFlagged; break;
                case VERIFIED: include = verified; break;
                default: include = true;
            }
            if (include) shown.add(s);
        }
        binding.txtSummary.setText(getString(R.string.official_summary, all.size(), flagged, review, unreviewed));
        adapter.submit(shown);
        binding.txtEmpty.setText(R.string.official_empty);
        binding.txtEmpty.setVisibility(shown.isEmpty() ? View.VISIBLE : View.GONE);
    }

    // ---------- Detail + review ----------

    private void openDetail(Submission s) {
        StringBuilder msg = new StringBuilder();
        msg.append(s.athleteName).append(" · ").append(genderLabel(s.gender)).append(", ").append(s.ageAtTest).append(" yrs\n");
        msg.append(place(s)).append('\n');
        msg.append(String.format(Locale.US, "%.0f cm · %.1f kg\n\n", s.heightCm, s.weightKg));

        msg.append(s.exerciseName).append(": ").append(ResultFormat.number(s.score)).append(' ').append(s.unit).append('\n');
        msg.append(ResultFormat.statusLabel(s.status));
        if (s.ratingLevel > 0) msg.append(" · ").append(Rating.label((int) s.ratingLevel));
        msg.append('\n');
        if (!s.reasons.isEmpty()) msg.append("• ").append(s.reasons.replace("\n", "\n• ")).append('\n');
        if (s.clockSuspicious()) msg.append("⚠ ").append(getString(R.string.clock_warning)).append('\n');
        if (!s.details.isEmpty()) msg.append('\n').append(s.details).append('\n');

        msg.append('\n').append(getString(R.string.recorded_at, ResultFormat.date(s.recordedAt)));
        if (s.receivedAt != null) msg.append('\n').append(getString(R.string.received_at, ResultFormat.date(s.receivedAt)));
        msg.append('\n').append(getString(R.string.frames_line, s.framesWithBody, s.framesAnalysed, s.videoFps));
        msg.append('\n').append(getString(R.string.hash_label, s.videoSha256.length() > 16 ? s.videoSha256.substring(0, 16) : s.videoSha256));

        if (s.isReviewed()) {
            msg.append("\n\n").append(getString(R.string.review_line, s.reviewDecision, s.reviewByEmail));
            if (s.reviewNote != null && !s.reviewNote.isEmpty()) msg.append("\n“").append(s.reviewNote).append('”');
        }

        EditText note = new EditText(this);
        note.setHint(R.string.review_note_hint);
        FrameLayout box = new FrameLayout(this);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, 0, pad, 0);
        box.addView(note);

        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.submission_title)
                .setMessage(msg)
                .setView(box)
                .setPositiveButton(R.string.approve, (d, w) -> review(s, Submission.APPROVED, note.getText().toString()))
                .setNegativeButton(R.string.reject, (d, w) -> review(s, Submission.REJECTED, note.getText().toString()))
                .setNeutralButton(R.string.close, null)
                .show();
    }

    private void review(Submission s, String decision, String note) {
        FirebaseUser me = FirebaseAuth.getInstance().getCurrentUser();
        if (me == null) return;
        Map<String, Object> review = new HashMap<>();
        review.put("decision", decision);
        review.put("note", note.trim());
        review.put("by", me.getUid());
        review.put("byEmail", me.getEmail());
        review.put("at", FieldValue.serverTimestamp());
        FirebaseFirestore.getInstance().collection(SubmissionMapper.SUBMISSIONS).document(s.id)
                .update("review", review)
                .addOnSuccessListener(x -> {
                    Toast.makeText(this, R.string.review_saved, Toast.LENGTH_SHORT).show();
                    if (Submission.REJECTED.equals(decision)) removeFromLeaderboard(s);
                })
                .addOnFailureListener(e -> Toast.makeText(this,
                        getString(R.string.official_error, e.getMessage()), Toast.LENGTH_LONG).show());
    }

    /** A rejected attempt must not stay on a leaderboard. Removes the entry if it came from this submission. */
    private void removeFromLeaderboard(Submission s) {
        if (s.athleteUid.isEmpty() || s.exercise.isEmpty()) return;
        DocumentReference entry = FirebaseFirestore.getInstance()
                .collection(LeaderboardMapper.LEADERBOARDS).document(s.exercise)
                .collection(LeaderboardMapper.ENTRIES).document(s.athleteUid);
        entry.get().addOnSuccessListener(snap -> {
            // The athlete's app reposts their next-best verified score at its next sync.
            if (snap.exists() && s.id.equals(snap.getString("submissionId"))) entry.delete();
        });
    }

    private static String genderLabel(String g) {
        if ("MALE".equals(g)) return "Male";
        if ("FEMALE".equals(g)) return "Female";
        return "Other";
    }

    private static String place(Submission s) {
        if (!s.district.isEmpty() && !s.state.isEmpty()) return s.district + ", " + s.state;
        if (!s.district.isEmpty()) return s.district;
        if (!s.state.isEmpty()) return s.state;
        return "—";
    }

    // ---------- List ----------

    private final class SubmissionAdapter extends RecyclerView.Adapter<SubmissionAdapter.Holder> {
        private final List<Submission> items = new ArrayList<>();

        void submit(List<Submission> list) {
            items.clear();
            items.addAll(list);
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new Holder(ItemSubmissionBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull Holder h, int position) {
            Submission s = items.get(position);
            h.b.txtName.setText(s.athleteName);
            h.b.txtWho.setText(String.format(Locale.US, "%s, %d · %s", genderLabel(s.gender), s.ageAtTest, place(s)));
            h.b.txtTest.setText(s.exerciseName + " · " + ResultFormat.date(s.recordedAt));
            String status = ResultFormat.statusLabel(s.status);
            if (s.ratingLevel > 0) status += " · " + Rating.label((int) s.ratingLevel);
            if (s.clockSuspicious()) status += " · ⚠ clock";
            h.b.txtStatus.setText(status);
            h.b.txtStatus.setTextColor(ResultFormat.statusColor(s.status));
            h.b.txtScore.setText(ResultFormat.number(s.score) + " " + s.unit);
            if (s.isReviewed()) {
                boolean ok = Submission.APPROVED.equals(s.reviewDecision);
                h.b.txtReview.setText(ok ? "Approved" : "Rejected");
                h.b.txtReview.setTextColor(ResultFormat.statusColor(ok ? "VERIFIED" : "FLAGGED"));
            } else {
                h.b.txtReview.setText(R.string.not_reviewed);
                h.b.txtReview.setTextColor(ResultFormat.statusColor("NO_REPS"));
            }
            h.b.card.setOnClickListener(v -> openDetail(s));
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        final class Holder extends RecyclerView.ViewHolder {
            final ItemSubmissionBinding b;

            Holder(ItemSubmissionBinding b) {
                super(b.getRoot());
                this.b = b;
            }
        }
    }
}

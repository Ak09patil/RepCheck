package com.repcheck.app.ui;

import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.repcheck.app.data.TestResult;
import com.repcheck.app.databinding.ItemResultBinding;
import com.repcheck.app.progress.Rating;

import java.util.ArrayList;
import java.util.List;

/** List of past attempts on the home screen. */
public class ResultAdapter extends RecyclerView.Adapter<ResultAdapter.Holder> {

    private final List<TestResult> items = new ArrayList<>();

    public void submit(List<TestResult> results) {
        items.clear();
        items.addAll(results);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new Holder(ItemResultBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull Holder h, int position) {
        TestResult r = items.get(position);
        h.b.txtExercise.setText(ResultFormat.exerciseName(r.exercise));
        h.b.txtDate.setText(ResultFormat.date(r.recordedAt));
        h.b.txtScore.setText(ResultFormat.score(r));
        String status = ResultFormat.statusLabel(r.status);
        if (r.ratingLevel > 0) status += " · " + Rating.label(r.ratingLevel);
        h.b.txtStatus.setText(status);
        h.b.txtStatus.setTextColor(ResultFormat.statusColor(r.status));
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class Holder extends RecyclerView.ViewHolder {
        final ItemResultBinding b;

        Holder(ItemResultBinding b) {
            super(b.getRoot());
            this.b = b;
        }
    }
}

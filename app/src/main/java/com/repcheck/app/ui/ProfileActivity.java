package com.repcheck.app.ui;

import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.datepicker.CalendarConstraints;
import com.google.android.material.datepicker.DateValidatorPointBackward;
import com.google.android.material.datepicker.MaterialDatePicker;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.repcheck.app.R;
import com.repcheck.app.cloud.SyncWorker;
import com.repcheck.app.data.AppDatabase;
import com.repcheck.app.data.Athlete;
import com.repcheck.app.databinding.ActivityProfileBinding;

import java.text.DateFormat;
import java.util.Date;
import java.util.TimeZone;

/** Create or edit the athlete's profile. Shown automatically on first launch. */
public class ProfileActivity extends AppCompatActivity {

    private ActivityProfileBinding binding;
    private Athlete athlete;          // null until loaded / for a brand-new profile
    private long dobMillis = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityProfileBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        binding.edtDob.setOnClickListener(v -> pickDob());
        binding.btnSave.setOnClickListener(v -> save());
        // Officials don't need an athlete profile; this is their way in on a fresh install.
        binding.btnOfficial.setOnClickListener(v -> startActivity(
                new Intent(this, LoginActivity.class).putExtra(LoginActivity.EXTRA_OFFICIAL, true)));

        AppDatabase db = AppDatabase.get(this);
        AppDatabase.IO.execute(() -> {
            Athlete existing = db.athleteDao().getCurrent();
            runOnUiThread(() -> fill(existing));
        });
    }

    private void fill(@Nullable Athlete a) {
        athlete = a;
        if (a == null) {
            binding.btnMale.setChecked(true);
            return;
        }
        binding.txtTitle.setText(R.string.profile_title_edit);
        binding.btnOfficial.setVisibility(android.view.View.GONE);
        binding.edtName.setText(a.name);
        setDob(a.dobMillis);
        if (Athlete.FEMALE.equals(a.gender)) binding.btnFemale.setChecked(true);
        else if (Athlete.OTHER.equals(a.gender)) binding.btnOther.setChecked(true);
        else binding.btnMale.setChecked(true);
        binding.edtHeight.setText(trim(a.heightCm));
        binding.edtWeight.setText(trim(a.weightKg));
        binding.edtState.setText(a.state);
        binding.edtDistrict.setText(a.district);
        binding.switchLeaderboard.setChecked(a.showOnLeaderboard);
    }

    private void pickDob() {
        MaterialDatePicker.Builder<Long> builder = MaterialDatePicker.Builder.datePicker()
                .setTitleText(R.string.hint_dob)
                .setInputMode(MaterialDatePicker.INPUT_MODE_TEXT) // typing a birth year beats scrolling
                .setCalendarConstraints(new CalendarConstraints.Builder()
                        .setValidator(DateValidatorPointBackward.now())
                        .build());
        if (dobMillis > 0) builder.setSelection(dobMillis);
        MaterialDatePicker<Long> picker = builder.build();
        picker.addOnPositiveButtonClickListener(this::setDob);
        picker.show(getSupportFragmentManager(), "dob");
    }

    private void setDob(long millis) {
        dobMillis = millis;
        // The picker returns UTC midnight, so format in UTC to avoid showing the previous day.
        DateFormat fmt = DateFormat.getDateInstance(DateFormat.MEDIUM);
        fmt.setTimeZone(TimeZone.getTimeZone("UTC"));
        binding.edtDob.setText(fmt.format(new Date(millis)));
    }

    private void save() {
        String name = text(binding.edtName);
        double height = number(binding.edtHeight);
        double weight = number(binding.edtWeight);

        boolean ok = true;
        ok &= check(binding.tilName, !TextUtils.isEmpty(name), R.string.err_name);
        ok &= check(binding.tilDob, dobMillis > 0, R.string.err_dob);
        ok &= check(binding.tilHeight, height >= 80 && height <= 250, R.string.err_height);
        ok &= check(binding.tilWeight, weight >= 15 && weight <= 200, R.string.err_weight);
        if (!ok) return;

        boolean isNew = athlete == null;
        Athlete a = isNew ? new Athlete() : athlete;
        a.name = name;
        a.dobMillis = dobMillis;
        int checked = binding.groupGender.getCheckedButtonId();
        a.gender = checked == R.id.btnFemale ? Athlete.FEMALE
                : checked == R.id.btnOther ? Athlete.OTHER : Athlete.MALE;
        a.heightCm = height;
        a.weightKg = weight;
        a.state = text(binding.edtState);
        a.district = text(binding.edtDistrict);
        a.showOnLeaderboard = binding.switchLeaderboard.isChecked();
        if (isNew) a.createdAt = System.currentTimeMillis();

        binding.btnSave.setEnabled(false);
        AppDatabase db = AppDatabase.get(this);
        AppDatabase.IO.execute(() -> {
            if (isNew) a.id = db.athleteDao().insert(a);
            else db.athleteDao().update(a);
            SyncWorker.enqueue(getApplicationContext()); // push the updated profile when online
            runOnUiThread(() -> {
                // First run: MainActivity closed itself to send us here, so reopen it.
                if (isNew) startActivity(new Intent(this, MainActivity.class));
                finish();
            });
        });
    }

    // ---------- helpers ----------

    private static boolean check(TextInputLayout field, boolean valid, int errorRes) {
        field.setError(valid ? null : field.getContext().getString(errorRes));
        return valid;
    }

    private static String text(TextInputEditText e) {
        return e.getText() == null ? "" : e.getText().toString().trim();
    }

    private static double number(TextInputEditText e) {
        try {
            return Double.parseDouble(text(e));
        } catch (NumberFormatException ex) {
            return -1;
        }
    }

    private static String trim(double d) {
        return d == Math.rint(d) ? String.valueOf((long) d) : String.valueOf(d);
    }
}

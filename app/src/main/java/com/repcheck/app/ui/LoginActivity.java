package com.repcheck.app.ui;

import android.content.Intent;
import android.os.Bundle;
import android.util.Patterns;
import android.view.View;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.textfield.TextInputEditText;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException;
import com.google.firebase.auth.FirebaseAuthInvalidUserException;
import com.google.firebase.auth.FirebaseAuthUserCollisionException;
import com.google.firebase.auth.FirebaseAuthWeakPasswordException;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.FirebaseFirestoreException;
import com.repcheck.app.R;
import com.repcheck.app.cloud.Cloud;
import com.repcheck.app.cloud.SubmissionMapper;
import com.repcheck.app.cloud.SyncWorker;
import com.repcheck.app.databinding.ActivityLoginBinding;

import java.util.HashMap;
import java.util.Map;

/**
 * Email sign-in with an Athlete / SAI official switch.
 *
 * Athletes sign in or create an account freely.
 * Officials need the official access code to register, or to turn an existing account into an
 * official one. The code is stored in Firestore at config/officials (field "code"), set once by an
 * administrator. The security rules compare it on the server, so the app can't be modified to skip it,
 * and no client can ever read the code.
 *
 * Whatever tab is chosen, the account's real role decides where the user lands.
 */
public class LoginActivity extends AppCompatActivity {

    /** Open with the "SAI official" tab selected. */
    public static final String EXTRA_OFFICIAL = "official";

    private ActivityLoginBinding binding;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityLoginBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        if (!Cloud.isConfigured(this)) {
            binding.txtLoginNote.setText(R.string.cloud_not_configured);
            binding.btnSignIn.setEnabled(false);
            binding.btnCreate.setEnabled(false);
            binding.groupMode.setEnabled(false);
            return;
        }
        if (getIntent().getBooleanExtra(EXTRA_OFFICIAL, false)) binding.groupMode.check(R.id.btnModeOfficial);
        binding.groupMode.addOnButtonCheckedListener((g, id, checked) -> {
            if (checked) showMode();
        });
        showMode();

        binding.btnSignIn.setOnClickListener(v -> submit(false));
        binding.btnCreate.setOnClickListener(v -> submit(true));
    }

    private boolean officialMode() {
        return binding.groupMode.getCheckedButtonId() == R.id.btnModeOfficial;
    }

    private void showMode() {
        boolean official = officialMode();
        binding.tilCode.setVisibility(official ? View.VISIBLE : View.GONE);
        binding.tilCode.setError(null);
        binding.btnCreate.setText(official ? R.string.create_official : R.string.create_account);
        binding.txtModeNote.setText(official ? R.string.login_official_note : R.string.login_athlete_note);
    }

    private void submit(boolean create) {
        String email = text(binding.edtEmail);
        String password = text(binding.edtPassword);
        boolean official = officialMode();
        String code = official ? text(binding.edtCode) : "";

        boolean ok = Patterns.EMAIL_ADDRESS.matcher(email).matches();
        binding.tilEmail.setError(ok ? null : getString(R.string.err_email));
        boolean pwOk = password.length() >= 6;
        binding.tilPassword.setError(pwOk ? null : getString(R.string.err_password));
        boolean codeOk = !(official && create && code.isEmpty());
        binding.tilCode.setError(codeOk ? null : getString(R.string.err_code_required));
        if (!ok || !pwOk || !codeOk) return;

        busy(true);
        FirebaseAuth auth = FirebaseAuth.getInstance();
        (create ? auth.createUserWithEmailAndPassword(email, password)
                : auth.signInWithEmailAndPassword(email, password))
                .addOnSuccessListener(r -> afterAuth(email, official, code))
                .addOnFailureListener(e -> fail(authMessage(e)));
    }

    /**
     * Reads users/{uid}. New account: creates it as an athlete, or as an official with the code.
     * Existing athlete on the Official tab: upgrades it with the code.
     */
    private void afterAuth(String email, boolean official, String code) {
        String uid = FirebaseAuth.getInstance().getUid();
        if (uid == null) {
            fail(getString(R.string.err_generic));
            return;
        }
        DocumentReference ref = FirebaseFirestore.getInstance().collection(SubmissionMapper.USERS).document(uid);
        ref.get()
                .addOnSuccessListener(snap -> {
                    String role = snap.exists() ? snap.getString("role") : null;

                    if (role == null) { // brand-new account
                        Map<String, Object> user = new HashMap<>();
                        user.put("email", email);
                        if (official) {
                            if (code.isEmpty()) {
                                signOutAndFail(getString(R.string.err_code_required));
                                return;
                            }
                            user.put("role", SubmissionMapper.ROLE_OFFICIAL);
                            user.put("accessCode", code);
                        } else {
                            user.put("role", SubmissionMapper.ROLE_ATHLETE);
                        }
                        String newRole = (String) user.get("role");
                        ref.set(user)
                                .addOnSuccessListener(x -> done(newRole))
                                .addOnFailureListener(e -> signOutAndFail(official ? firestoreMessage(e)
                                        : getString(R.string.err_generic) + " " + e.getMessage()));
                        return;
                    }

                    if (official && SubmissionMapper.ROLE_ATHLETE.equals(role)) {
                        if (code.isEmpty()) {
                            signOutAndFail(getString(R.string.err_need_code_upgrade));
                            return;
                        }
                        ref.update("role", SubmissionMapper.ROLE_OFFICIAL, "accessCode", code)
                                .addOnSuccessListener(x -> done(SubmissionMapper.ROLE_OFFICIAL))
                                .addOnFailureListener(e -> signOutAndFail(firestoreMessage(e)));
                        return;
                    }

                    done(role); // the account's real role decides, whichever tab was chosen
                })
                .addOnFailureListener(e -> signOutAndFail(getString(R.string.err_generic) + " " + e.getMessage()));
    }

    private void done(String role) {
        if (!Cloud.isKnownRole(role)) {
            // e.g. "Official" or "official " typed in the console: neither the app nor the rules accept it.
            signOutAndFail(getString(R.string.err_role_unknown, role));
            return;
        }
        Cloud.cacheRole(this, role);
        if (SubmissionMapper.ROLE_OFFICIAL.equals(role)) {
            Intent i = new Intent(this, OfficialActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(i);
        } else {
            SyncWorker.enqueue(this);
        }
        finish();
    }

    private void signOutAndFail(String message) {
        Cloud.signOut(this);
        fail(message);
    }

    private void fail(String message) {
        busy(false);
        binding.txtLoginNote.setText(message);
    }

    private void busy(boolean busy) {
        binding.progress.setVisibility(busy ? View.VISIBLE : View.GONE);
        binding.btnSignIn.setEnabled(!busy);
        binding.btnCreate.setEnabled(!busy);
    }

    private String authMessage(Exception e) {
        if (e instanceof FirebaseAuthWeakPasswordException) return getString(R.string.err_password);
        if (e instanceof FirebaseAuthUserCollisionException) return getString(R.string.err_email_taken);
        if (e instanceof FirebaseAuthInvalidUserException
                || e instanceof FirebaseAuthInvalidCredentialsException) return getString(R.string.err_credentials);
        return getString(R.string.err_generic) + " " + e.getMessage();
    }

    /** The rules answer PERMISSION_DENIED when the access code is wrong (or not set up yet). */
    private String firestoreMessage(Exception e) {
        if (e instanceof FirebaseFirestoreException
                && ((FirebaseFirestoreException) e).getCode() == FirebaseFirestoreException.Code.PERMISSION_DENIED) {
            return getString(R.string.err_code_wrong);
        }
        return getString(R.string.err_generic) + " " + e.getMessage();
    }

    private static String text(TextInputEditText e) {
        return e.getText() == null ? "" : e.getText().toString().trim();
    }
}

package com.repcheck.app.cloud;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.Nullable;

import com.google.firebase.FirebaseApp;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.Source;

/**
 * Small helper around Firebase so the rest of the app never crashes when cloud sync isn't set up.
 * The signed-in user's role is cached locally so the app opens the right screen while offline.
 */
public final class Cloud {
    private Cloud() {}

    private static final String PREFS = "cloud";
    private static final String KEY_ROLE = "role";

    /** False when the app was built without google-services.json: everything stays offline. */
    public static boolean isConfigured(Context context) {
        return !FirebaseApp.getApps(context).isEmpty();
    }

    @Nullable
    public static FirebaseUser user(Context context) {
        return isConfigured(context) ? FirebaseAuth.getInstance().getCurrentUser() : null;
    }

    public static boolean isSignedIn(Context context) {
        return user(context) != null;
    }

    public static boolean isOfficial(Context context) {
        return isSignedIn(context) && SubmissionMapper.ROLE_OFFICIAL.equals(cachedRole(context));
    }

    @Nullable
    public static String cachedRole(Context context) {
        return prefs(context).getString(KEY_ROLE, null);
    }

    public static void cacheRole(Context context, String role) {
        prefs(context).edit().putString(KEY_ROLE, role).apply();
    }

    /** Receives the role read from the server: "athlete", "official", or whatever else was typed in. */
    public interface RoleListener {
        void onRole(String role);
    }

    /**
     * Re-reads users/{uid}.role from the server and updates the cached copy, so a role changed in the
     * Firebase console takes effect the next time the app opens, without signing out and in.
     * Does nothing when signed out or offline (the cached role is kept).
     */
    public static void refreshRole(Context context, RoleListener listener) {
        FirebaseUser u = user(context);
        if (u == null) return;
        Context app = context.getApplicationContext();
        FirebaseFirestore.getInstance().collection(SubmissionMapper.USERS).document(u.getUid())
                .get(Source.SERVER)
                .addOnSuccessListener(snap -> {
                    String role = snap.getString("role");
                    if (role == null) return;
                    cacheRole(app, role);
                    listener.onRole(role);
                });
    }

    /** True for the two roles the app and the security rules understand (exact, lowercase). */
    public static boolean isKnownRole(String role) {
        return SubmissionMapper.ROLE_ATHLETE.equals(role) || SubmissionMapper.ROLE_OFFICIAL.equals(role);
    }

    public static void signOut(Context context) {
        if (isConfigured(context)) FirebaseAuth.getInstance().signOut();
        prefs(context).edit().remove(KEY_ROLE).apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}

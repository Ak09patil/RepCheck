package com.repcheck.app.cloud;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.Constraints;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.google.android.gms.tasks.Tasks;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.SetOptions;
import com.google.firebase.firestore.Source;
import com.repcheck.app.data.AppDatabase;
import com.repcheck.app.data.Athlete;
import com.repcheck.app.data.TestResult;
import com.repcheck.app.exercise.ExerciseType;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Uploads the athlete's profile and every not-yet-synced result.
 *
 * Runs only when there's a network connection (WorkManager waits until there is one, even after
 * a reboot), retries with back-off on failure, and is safe to run any number of times: each result's
 * document id is its video hash, so a result that already reached the server is just marked synced.
 */
public class SyncWorker extends Worker {

    private static final String TAG = "SyncWorker";
    private static final String UNIQUE_NAME = "repcheck-sync";
    private static final long TIMEOUT_S = 30;

    public SyncWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    /** Ask for a sync. Cheap to call often: a sync already waiting absorbs new requests. */
    public static void enqueue(Context context) {
        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(SyncWorker.class)
                .setConstraints(new Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build())
                .build();
        WorkManager.getInstance(context)
                .enqueueUniqueWork(UNIQUE_NAME, ExistingWorkPolicy.KEEP, request);
    }

    @NonNull
    @Override
    public Result doWork() {
        Context ctx = getApplicationContext();
        FirebaseUser user = Cloud.user(ctx);
        // Nothing to do (not set up, signed out, or an official's phone): that's not a failure.
        if (user == null || Cloud.isOfficial(ctx)) return Result.success();

        AppDatabase db = AppDatabase.get(ctx);
        Athlete athlete = db.athleteDao().getCurrent();
        if (athlete == null) return Result.success();

        FirebaseFirestore fs = FirebaseFirestore.getInstance();
        String uid = user.getUid();
        try {
            Map<String, Object> profile = SubmissionMapper.profile(athlete);
            profile.put("updatedAt", FieldValue.serverTimestamp());
            Tasks.await(fs.collection(SubmissionMapper.ATHLETES).document(uid)
                    .set(profile, SetOptions.merge()), TIMEOUT_S, TimeUnit.SECONDS);

            int uploaded = 0;
            for (TestResult r : db.testResultDao().unsynced()) {
                if (isStopped()) return Result.retry();
                if (r.videoSha256 == null || r.videoSha256.isEmpty()) continue;

                DocumentReference ref = fs.collection(SubmissionMapper.SUBMISSIONS).document(r.videoSha256);
                DocumentSnapshot existing = Tasks.await(ref.get(Source.SERVER), TIMEOUT_S, TimeUnit.SECONDS);
                if (!existing.exists()) {
                    Map<String, Object> doc = SubmissionMapper.submission(r, athlete, uid, appVersion(ctx));
                    doc.put("receivedAt", FieldValue.serverTimestamp());
                    Tasks.await(ref.set(doc), TIMEOUT_S, TimeUnit.SECONDS);
                    uploaded++;
                }
                db.testResultDao().markSynced(r.id);
            }
            Log.i(TAG, "Sync done, uploaded " + uploaded);
            updateLeaderboards(fs, db, athlete, uid);
            return Result.success();
        } catch (Exception e) {
            Log.w(TAG, "Sync failed, will retry", e);
            return getRunAttemptCount() < 5 ? Result.retry() : Result.failure();
        }
    }

    /**
     * Posts (or removes) this athlete's best verified score for each test. Runs after the results
     * themselves are on the server, because the security rules check each entry against its submission.
     * Problems here never fail the whole sync: the leaderboard is a bonus, the results are what matter.
     */
    private void updateLeaderboards(FirebaseFirestore fs, AppDatabase db, Athlete athlete, String uid) {
        List<TestResult> all = db.testResultDao().all(athlete.id);
        for (ExerciseType type : ExerciseType.values()) {
            DocumentReference ref = fs.collection(LeaderboardMapper.LEADERBOARDS).document(type.name())
                    .collection(LeaderboardMapper.ENTRIES).document(uid);
            try {
                if (!athlete.showOnLeaderboard) {
                    Tasks.await(ref.delete(), TIMEOUT_S, TimeUnit.SECONDS); // opted out: remove any old entry
                    continue;
                }
                // Best first; if an official rejected the best, the rules refuse it and we try the next.
                int tries = 0;
                for (TestResult r : LeaderboardMapper.candidates(all, type.name())) {
                    if (tries++ == 3) break;
                    Map<String, Object> entry = LeaderboardMapper.entry(r, athlete, uid);
                    entry.put("updatedAt", FieldValue.serverTimestamp());
                    try {
                        Tasks.await(ref.set(entry), TIMEOUT_S, TimeUnit.SECONDS);
                        break;
                    } catch (Exception refused) {
                        Log.i(TAG, "Leaderboard entry refused for " + type + ", trying next best", refused);
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "Leaderboard update failed for " + type, e);
            }
        }
    }

    private static String appVersion(Context ctx) {
        try {
            return ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "unknown";
        }
    }
}

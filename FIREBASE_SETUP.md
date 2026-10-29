# Setting up cloud sync (Firebase, free plan)

The app builds and works fully offline without any of this. These steps switch on login, upload and the
officials' dashboard. Everything used here is on Firebase's free **Spark** plan, so no payment card is needed.

> Videos stay on the phone. Uploading video files would need Cloud Storage, which isn't on the free plan.
> Instead each result carries the video's SHA-256 fingerprint, so a disputed video can later be checked
> against the exact file that was analysed.

## 1. Create the project

1. Go to <https://console.firebase.google.com> and click **Create a project** (Google Analytics: off is fine).
2. Click the Android icon to **add an Android app**. Package name: `com.repcheck.app`. Nickname and SHA-1 are optional.
3. Download **google-services.json** and put it in the `app/` folder (next to `app/build.gradle`):
   ```
   mv ~/Downloads/google-services.json ~/path/to/RepCheck/app/
   ```
4. Skip the rest of the wizard: the Gradle setup is already done.

## 2. Turn on email login

**Build → Authentication → Get started → Sign-in method → Email/Password → Enable → Save.**

## 3. Create the database and add the rules

1. **Build → Firestore Database → Create database.** Pick a location (e.g. `asia-south1`, Mumbai) and start in **production mode**.
2. Open the **Rules** tab, replace everything with the contents of `firestore.rules` from this project, and click **Publish**.

> **Already published the rules before?** The leaderboard added new rules. Paste the current
> `firestore.rules` again and click **Publish**, or leaderboard entries will be refused.

## 4. Rebuild and try it

1. In Android Studio: **File → Sync Project with Gradle Files**, then **Run**.
2. On the home screen, the sync card now offers **Sign in**. Create an athlete account, record a test, and watch the
   card go from "1 waiting to upload" to "all results uploaded ✓". Uploads wait for a connection, so try it in
   airplane mode too.
3. In the console, **Firestore → Data** shows `users`, `athletes` and `submissions`.

## 5. Make an official account

Officials can't be created from the app, on purpose.

1. On a second phone (or after signing out), use **Create athlete account** with the official's email.
2. In the console: **Firestore → Data → users →** that account's document → change `role` from `athlete`
   to `official`.
3. Sign in with that account. The app opens the **Submissions** dashboard instead of the athlete screens.

On a fresh install, an official taps **"Are you an SAI official? Sign in"** under the profile form.

## What the officials' dashboard does

- Live list of every submission, newest first (new uploads appear without refreshing).
- Filters: **All**, **To review** (flagged, needs-review or clock-warning and not yet reviewed), **Flagged**, **Verified**.
- Tap a submission to see the athlete, score, rating, every cheat-check reason, the jump/rep breakdown,
  frame stats, the video fingerprint, then **Approve** or **Reject** with an optional note.
- **Clock check:** if the phone's recorded date is more than 10 minutes ahead of the server's receive time,
  the submission is marked "⚠ clock": a sign the phone's date was changed.

## Security, in brief

| Rule | Why |
|---|---|
| New accounts are always `athlete` | Nobody can make themselves an official |
| Athletes can create submissions but never edit or delete them | Results can't be changed after upload |
| Submission id must equal the video's SHA-256 | Each video can be submitted once; the id is the fingerprint |
| `receivedAt` must be the server's time | The upload time can't be faked |
| Officials can change only the `review` field | Officials can't alter scores either |
| A leaderboard entry must match the athlete's own VERIFIED, unrejected submission with the same score | Leaderboard scores can't be invented |
| Leaderboards are opt-in and show first name + initial only | Privacy for under-18 athletes |

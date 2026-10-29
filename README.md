# RepCheck

Offline fitness-test app for Android, built for the SAI talent-assessment problem statement. An athlete records a test on their phone. The app analyses the saved video on the device, counts reps, checks the attempt is genuine, and stores the result with a fingerprint of the video. No internet is needed.

## Run it

1. Open this folder in **Android Studio** (Koala or newer) → *File → Open*.
2. Let Gradle sync. The first build downloads the MediaPipe pose model (~5 MB) into `app/src/main/assets/`.
3. Plug in an Android phone (**Android 9+**) with USB debugging on and press **Run**. Use a real phone; the emulator camera is too weak.

Logic tests (no phone needed): right-click the `app/src/test/java` folder → *Run 'Tests in java'*. There are 45 tests.

> If the model download fails on your network, fetch it manually from the project folder:
> ```
> curl -L -o app/src/main/assets/pose_landmarker_lite.task https://storage.googleapis.com/mediapipe-models/pose_landmarker/pose_landmarker_lite/float16/latest/pose_landmarker_lite.task
> ```

## App flow

```
First launch ─▶ Profile (name, DOB, gender, height, weight, state, district)
                    │
Home ─ pick test ─▶ Record ─ 3-2-1 ─▶ recording (≤60 s, live "in frame" guide)
                                            │ stop
                                            ▼
                      Analysis (on the saved video, on the phone)
                      hash video → frame timing check → pose per frame → reps or jumps + continuity → verdict → rating → save → badges
                                            │
                                            ▼
                      Result: score, Verified / Needs review / Flagged + reasons + breakdown
```

## Why record first, then analyse

The problem statement asks for verification of *recorded* videos. Analysing the saved file, rather than the live feed, means:

- the result is reproducible: the same file always gives the same numbers;
- the video can be uploaded with the result for officials to check;
- the file can be fingerprinted (SHA-256) before analysis, so the server can tell if a different or edited file is sent later;
- frame-timing checks for sped-up or spliced videos become possible (next milestone).

During recording, a light pose check runs at about 5 fps and only tells the athlete whether they're fully in frame.

## Cheat and quality checks (so far)

| Check | Where | Effect |
|---|---|---|
| Partial rep (didn't reach full depth) | per rep | not counted |
| Bad form for >30% of a rep | per rep | counted separately, not as valid |
| Rep faster than humanly plausible | per rep | **Flagged**: may be a sped-up video |
| Athlete out of frame >40% of the video | per video | **Needs review** |
| More than half the attempts partial or bad form | per video | **Needs review** |
| Leaving the app mid-recording | recording | attempt discarded |
| Every attempt is saved, including bad ones | database | officials see how many tries it took |
| SHA-256 of the video | before analysis | detects a swapped file at upload |

## Design choices for entry-level phones

- Video is recorded at **480p** without audio: small files for slow uploads, fast decoding, and no microphone permission needed.
- Analysis samples about **15 frames per second** of video and uses the **lite** pose model on the **CPU** (GPU support is unreliable on budget phones).
- If a phone can't run preview, video and live analysis at once, the live guide is turned off automatically and recording still works.

## Vertical jump

**Height from flight time.** Anything thrown straight up spends half its flight rising and half falling, so

```
jump height = g × t² / 8        (t = time in the air, g = 9.81 m/s²)
```

The method needs no tape measure or reference object, only accurate timing, which suits a phone camera well.

- **"In the air"** means *both* feet are off the ground. The app tracks the lowest visible foot point and learns where the floor is while the athlete stands.
- **Timing is sub-frame.** Takeoff and landing are interpolated between frames where the feet cross a line just above the floor (3% of leg length), then corrected exactly for that line's height. Every frame is analysed for jumps, not 15 per second.
- **Scale comes from the profile.** Hip-to-ankle length is about 46% of standing height, so the athlete's height gives a cm-per-pixel scale. That scale is only used for the checks below and a tiny correction; the height itself comes from time.
- **Best of several.** The athlete can jump up to 3 times in one video; each jump is found automatically (auto-segmentation) and the best valid one is the score.

Each jump is checked:

| Check | How | Result |
|---|---|---|
| **Gravity check** | Fits a parabola to the hips' flight path. A real body falls at ~9.8 m/s²; a video slowed to half speed shows ~2.5 m/s² | **Flagged**: video speed altered |
| Implausible height | Over 90 cm | **Flagged** |
| Tucked knees | Knee angle under 140° mid-flight (tucking keeps feet up longer and inflates the time) | not counted |
| Body didn't rise | Hips rose less than half the measured height | not counted |
| Low frame rate | Video under 24 fps | **Needs review** (less precise) |

In simulation (synthetic athlete with real projectile physics and landmark jitter) the measured height is within 1 cm at 30 fps and within about 2 cm at 24 fps, and the gravity estimate lands at 9.3–10.2 m/s². Expect larger errors on real video, because pose tracking of feet is noisier than the simulation; validate against a few tape-measured jumps.

## Video integrity (tamper detection)

Separate from *how well* the athlete did, the app checks whether the **video itself** can be trusted.
Findings are merged into the verdict and listed for officials under "Integrity".

| Check | How | Result |
|---|---|---|
| **Length vs recording clock** | The app times the recording with the phone's own clock. If the analysed file's length differs by more than 1.5 s, it isn't the file that was recorded | **Flagged** |
| **Gaps** | Frame timestamps are read from the file (no decoding). A camera clip has frames every ~33 ms; a gap of 0.5 s or more means part of the video is missing | Needs review |
| **Duplicate timestamps** | Two frames at the same instant: a sign of clips joined together | Needs review |
| **Splices** | The athlete's hips "teleport" more than half a torso length between neighbouring frames *and stay there*. (One-frame tracking glitches snap back and are ignored.) | Needs review |
| **Person swap** | Torso ÷ leg length doesn't change with posture or camera distance; a change of over 20% between the start and end of the video suggests a different person | Needs review |
| Sped-up reps | Rep faster than humanly plausible (see rep rules) | Flagged |
| Slowed-down jumps | Flight path doesn't fall at 9.8 m/s² (see vertical jump) | Flagged |
| Swapped after analysis | SHA-256 fingerprint stored with the result and uploaded | Detectable by officials |
| Wrong date | Recording date ahead of the server's receive time | "⚠ clock" for officials |

### Threat model and limits (for the report)

The app records the video itself into private storage, so editing a clip in another app and importing it
isn't possible in normal use. The checks above are defence in depth for a rooted phone or a modified app,
and they catch mistakes such as an interrupted recording. Known gaps:

- **Filming a screen.** Pointing the camera at another phone playing someone else's video isn't detected,
  though the physics checks still catch a replay that's been sped up or slowed down. Liveness checks
  (e.g. asking the athlete to wave at a random moment) would be the next step.
- **Look-alike swap.** Two people with very similar proportions could swap mid-video unnoticed. Face matching
  against the profile would close this, at a privacy cost.
- **A fully re-encoded fake** with steady timing and smooth motion passes the file checks. That's why the
  fingerprint is uploaded and officials review flagged and borderline results.

## Cloud sync and the officials' dashboard

Optional. Without setup, the app builds and runs fully offline. To switch it on, follow **FIREBASE_SETUP.md**
(about 10 minutes, free Firebase plan, no payment card).

- **Athletes** sign in with email. Results upload in the background with **WorkManager**: it waits for a
  connection (even across reboots), retries with back-off, and the home screen shows how many are waiting.
- Each upload's document id is the **video's SHA-256**, so retries never duplicate and the id is the fingerprint.
- **Officials** sign in to a live **Submissions** dashboard: filters (To review / Flagged / Verified),
  full detail for each attempt, and **Approve / Reject** with a note.
- **Security rules** (`firestore.rules`): nobody can make themselves an official, athletes can't edit
  results after upload, officials can only add a review, and upload time is stamped by the server.
- **Clock check:** a recording dated more than 10 minutes after the server received it is marked "⚠ clock".
- Videos stay on the phone (Cloud Storage isn't on the free plan); the fingerprint lets a disputed video be
  matched to the exact file that was analysed.

## Leaderboards

Per test, with three views: **My group** (same gender and age group, the fair comparison), **My state**
and **Everyone**. Medals for the top three; your own row is highlighted.

- **Opt-in.** A switch in the profile, off by default, because many athletes will be under 18. Only first name
  + last initial, district/state, gender and age group are shown; never date of birth, height or weight.
- **Only verified scores, and they can't be faked.** Each entry names the submission it came from. The security
  rules refuse the entry unless that submission is the athlete's own, VERIFIED, has exactly that score, and
  hasn't been rejected by an official. A modified app can't post a made-up number.
- **Rejection removes it.** When an official rejects an attempt, its leaderboard entry is deleted; the athlete's
  app reposts their next-best verified score at the next sync.
- Board layout `leaderboards/{TEST}/entries/{uid}` keeps each board a single "order by score" query with no
  custom index. Filtering by group/state happens on the phone over the top 300, which suits a district or
  class; a national rollout would pre-split boards by group.

## Benchmarks, badges and progress

**Benchmarking.** Each *verified* score is rated against the athlete's gender and age on the test day:
Needs work → Below average → Average → Good → Excellent. The result screen also gives instant feedback
such as "3 more reps to reach Good".

The benchmark table lives in one file, `progress/Benchmarks.java`. The current rows are **provisional**,
taken from published sources, and the app says so on screen:

| Test | Source | Note |
|---|---|---|
| Sit-ups (30 s) | Davis et al. (2000), *Physical Education and the Study of Sport*, 4th ed., p.124 | Ages 16–19 norms, applied to all ages for now |
| Vertical jump | EUROFIT adult percentiles (Council of Europe) | Under-20s use the 20–29 band for now |
| Push-ups, squats | — | Not in the SAI battery, so no benchmark |

When the official SAI norms (Annexure A) are available, replace the `row(...)` lines; nothing else changes.

**Timed tests.** Sit-ups follow the SAI/Khelo India curl-up protocol: 30 seconds, auto-stop. Vertical jump also
records 30 s (room for 3 jumps). Push-ups and squats record up to 60 s.

**Badges** (only verified attempts count, so flagged videos can't farm them):

| Badge | How to earn it |
|---|---|
| 👟 First step | First verified test |
| 🎯 All-rounder | Verified result in every SAI test (sit-ups + vertical jump) |
| 📈 Personal best | Beat your own best in any test |
| 🛡️ Clean sheet | 3 verified attempts in a row |
| 📅 Regular | Test on 3 different days |
| ⭐ Above the bar | Good rating in any test |
| 🏆 Excellent | Excellent rating in any test |
| 💪 Core of steel | 25 sit-ups in 30 s |
| 🚀 Sky high | 40 cm jump |

New badges pop up on the result screen. The Badges screen shows all of them, with locked ones faded.

**History screen.** Choose a test to see a progress chart (verified attempts as a line, unverified as hollow dots,
benchmark levels as dashed lines), your personal best, attempt count, latest rating and every attempt.
The chart is a hand-drawn custom `View`, so it needs no chart library.

## Rep rules

| Exercise | Driving angle | Top | Bottom | Form check | Min rep time |
|---|---|---|---|---|---|
| Sit-up | shoulder–hip–knee | ≥120° | ≤70° | knees stay bent (≤130°) | 0.8 s |
| Push-up | shoulder–elbow–wrist | ≥155° | ≤90° | body line (shoulder–hip–ankle) ≥150° | 0.6 s |
| Squat | hip–knee–ankle | ≥160° | ≤100° | torso lean ≤55° | 0.7 s |

Tune these in `exercise/ExerciseType.java` after testing on real people.

## Code map

```
app/src/main/java/com/repcheck/app/
├── analysis/
│   ├── VideoAnalyzer.java     decodes the saved video, runs pose + the test's analyser per frame
│   ├── SessionVerdict.java    Verified / Needs review / Flagged + reasons   (unit-tested)
│   ├── FrameTiming.java       gaps, duplicate timestamps, length vs clock   (unit-tested)
│   ├── ContinuityTracker.java splices and person swaps from pose           (unit-tested)
│   ├── IntegrityReport.java   integrity findings, merged into the verdict
│   └── FileHasher.java        SHA-256 of the video
├── cloud/
│   ├── Cloud.java             is Firebase set up? signed in? role (cached for offline)
│   ├── SyncWorker.java        WorkManager upload of profile + unsynced results
│   ├── SubmissionMapper.java  local records → Firestore documents         (unit-tested)
│   ├── LeaderboardMapper.java leaderboard entries, privacy, ranking         (unit-tested)
│   └── Submission.java        a submission as the officials' screen reads it
├── data/                      Room database
│   ├── Athlete.java, TestResult.java          tables
│   ├── AthleteDao.java, TestResultDao.java    queries
│   └── AppDatabase.java
├── exercise/
│   ├── ExerciseType.java      the tests: per-exercise angles, thresholds, setup hints
│   ├── TestSession.java       interface every test analyser implements
│   ├── TestOutcome.java       score + verdict + breakdown for one attempt
│   ├── RepCounter.java        state machine that counts and judges reps     (unit-tested)
│   └── JumpDetector.java      flight-time jump height + physics checks      (unit-tested)
├── progress/
│   ├── Benchmarks.java        age/gender norm table (provisional)          (unit-tested)
│   ├── Rating.java            Needs work … Excellent + "N more to reach X"
│   └── Badges.java            badge rules from attempt history              (unit-tested)
├── pose/
│   ├── PoseDetector.java      MediaPipe wrapper: live-stream and video modes
│   ├── PosePoint.java, BodySide.java, AngleUtils.java
└── ui/
    ├── MainActivity.java      home: athlete card, tests, recent attempts
    ├── ProfileActivity.java   create/edit athlete profile
    ├── RecordActivity.java    camera + recording + framing guide
    ├── AnalysisActivity.java  progress, then result
    ├── PoseOverlayView.java   skeleton drawn over the preview
    ├── LoginActivity.java     email sign-in / athlete sign-up
    ├── OfficialActivity.java  officials' live dashboard + approve/reject
    ├── LeaderboardActivity.java  per-test boards: my group / my state / everyone
    ├── HistoryActivity.java   progress chart, personal best, attempts per test
    ├── BadgesActivity.java    badge grid
    ├── ProgressChartView.java hand-drawn chart with benchmark lines
    ├── ResultAdapter.java, ResultFormat.java
```

## Next milestones

- [x] Record → analyse pipeline, Room database, athlete profile
- [x] Vertical jump (flight-time method + gravity check)
- [x] Benchmarks by age/gender, badges, history charts
- [x] Firebase login + background upload with WorkManager
- [x] Officials' view (live dashboard, review, security rules)
- [x] Video integrity: frame timing, length check, splice and person-swap detection
- [x] Leaderboards (opt-in, server-verified scores) and app icon

## Real-device test plan

Do these on a real phone before the demo. Each line is something an examiner might try.

1. **Sit-ups:** 10 clean reps → Verified, count 10. Then 5 half-reps → listed as Partial, not counted.
2. **Push-ups with sagging hips** → Form issues.
3. **Vertical jump:** 3 jumps against a wall with a tape measure; compare with the app (expect a few cm).
4. **Tucked jump** (knees up in the air) → not counted.
5. **Walk out of frame for half the test** → Needs review.
6. **Leave the app mid-recording** → attempt discarded.
7. **Airplane mode:** record a test, then reconnect → uploads automatically (after Firebase setup).
8. **Official account:** approve one submission, reject one with a note.
   Reject a submission that's on the leaderboard → it disappears; the athlete's next-best appears after their next sync.
9. **Leaderboard:** turn on "Show me on leaderboards" in the profile, record a verified test, check you appear (with medal and highlight).
10. **Tune thresholds** in `ExerciseType.java` (rep angles) if your counts are off on real people.

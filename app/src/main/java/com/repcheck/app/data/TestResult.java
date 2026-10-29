package com.repcheck.app.data;

import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/**
 * One recorded attempt at one test. Every attempt is saved, including bad ones,
 * so officials can see how many tries it took (you can't quietly retake until it looks good).
 */
@Entity(tableName = "test_results",
        foreignKeys = @ForeignKey(entity = Athlete.class, parentColumns = "id",
                childColumns = "athleteId", onDelete = ForeignKey.CASCADE),
        indices = @Index("athleteId"))
public class TestResult {

    @PrimaryKey(autoGenerate = true)
    public long id;

    public long athleteId;
    /** ExerciseType.name(), e.g. "SIT_UP". */
    public String exercise;
    public long recordedAt;
    public long videoDurationMs;

    /**
     * The headline score. Reps for rep-based tests; later tests (vertical jump in cm,
     * run time in seconds) reuse these two columns, so no schema change is needed.
     */
    public double scoreValue;
    public String scoreUnit;

    /** Rep tests: valid reps. Jump: number of valid jumps. */
    public int validReps;
    public int partialReps;
    public int badFormReps;
    public int tooFastReps;

    public int framesAnalysed;
    public int framesWithBody;

    /** SessionVerdict.Status name: VERIFIED / NEEDS_REVIEW / FLAGGED / NO_REPS. */
    public String status;
    /** Human-readable reasons for the status, one per line. */
    public String reasons;
    /** Readable breakdown (rep counts, or each jump's height and checks), one item per line. */
    public String details;
    /** Frame rate of the recorded video; jump precision depends on it. */
    public double videoFps;

    /** Benchmark rating 1 (Needs work) .. 5 (Excellent); 0 = not rated (no benchmark, or not verified). */
    public int ratingLevel;

    /** Video stored in the app's private storage. */
    public String videoPath;
    /** SHA-256 of the video file, taken before analysis. Lets the server detect a swapped or edited file. */
    public String videoSha256;

    /** Set once the result has been uploaded to the server. */
    public boolean synced;
}

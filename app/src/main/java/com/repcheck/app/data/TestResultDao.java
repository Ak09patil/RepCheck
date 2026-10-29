package com.repcheck.app.data;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;

import java.util.List;

@Dao
public interface TestResultDao {

    @Insert
    long insert(TestResult result);

    @Query("SELECT * FROM test_results WHERE athleteId = :athleteId ORDER BY recordedAt DESC LIMIT :limit")
    List<TestResult> recent(long athleteId, int limit);

    @Query("SELECT * FROM test_results WHERE athleteId = :athleteId AND exercise = :exercise ORDER BY recordedAt ASC")
    List<TestResult> history(long athleteId, String exercise);

    @Query("SELECT * FROM test_results WHERE athleteId = :athleteId ORDER BY recordedAt ASC")
    List<TestResult> all(long athleteId);

    /** Results still waiting to be uploaded (used by the sync worker later). */
    @Query("SELECT * FROM test_results WHERE synced = 0 ORDER BY recordedAt ASC")
    List<TestResult> unsynced();

    @Query("SELECT COUNT(*) FROM test_results WHERE synced = 0")
    int countUnsynced();

    @Query("UPDATE test_results SET synced = 1 WHERE id = :id")
    void markSynced(long id);
}

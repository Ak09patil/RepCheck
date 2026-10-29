package com.repcheck.app.data;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;
import androidx.room.Update;

@Dao
public interface AthleteDao {

    @Insert
    long insert(Athlete athlete);

    @Update
    void update(Athlete athlete);

    /** This app has one athlete per phone; returns null until the profile is created. */
    @Query("SELECT * FROM athletes ORDER BY id LIMIT 1")
    Athlete getCurrent();
}

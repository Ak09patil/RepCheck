package com.repcheck.app.data;

import android.content.Context;

import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Database(entities = {Athlete.class, TestResult.class}, version = 4, exportSchema = false)
public abstract class AppDatabase extends RoomDatabase {

    public abstract AthleteDao athleteDao();

    public abstract TestResultDao testResultDao();

    /** Room refuses to run queries on the main thread; use this for all database work. */
    public static final ExecutorService IO = Executors.newSingleThreadExecutor();

    private static volatile AppDatabase instance;

    public static AppDatabase get(Context context) {
        if (instance == null) {
            synchronized (AppDatabase.class) {
                if (instance == null) {
                    instance = Room.databaseBuilder(context.getApplicationContext(),
                                    AppDatabase.class, "repcheck.db")
                            // Fine while developing: schema changes wipe local data instead of crashing.
                            .fallbackToDestructiveMigration()
                            .build();
                }
            }
        }
        return instance;
    }
}

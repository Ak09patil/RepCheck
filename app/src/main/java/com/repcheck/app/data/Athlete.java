package com.repcheck.app.data;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

import java.util.Calendar;

/** The athlete using this phone. Height and weight are two of the SAI test battery measurements. */
@Entity(tableName = "athletes")
public class Athlete {

    public static final String MALE = "MALE";
    public static final String FEMALE = "FEMALE";
    public static final String OTHER = "OTHER";

    @PrimaryKey(autoGenerate = true)
    public long id;

    public String name;
    /** Date of birth, epoch millis (UTC midnight, as returned by the date picker). */
    public long dobMillis;
    /** One of MALE / FEMALE / OTHER. Benchmarks are split by gender. */
    public String gender;
    public double heightCm;
    public double weightKg;
    public String state;
    public String district;
    public long createdAt;
    /** Opt-in: appear on public leaderboards (first name, last initial and district only). */
    public boolean showOnLeaderboard;

    /** Age in whole years today. */
    public int age() {
        return ageOn(System.currentTimeMillis());
    }

    /** Age in whole years on a given date (benchmarks use the age on the day of the test). */
    public int ageOn(long millis) {
        Calendar dob = Calendar.getInstance();
        dob.setTimeInMillis(dobMillis);
        Calendar on = Calendar.getInstance();
        on.setTimeInMillis(millis);
        int age = on.get(Calendar.YEAR) - dob.get(Calendar.YEAR);
        boolean birthdayNotYet = on.get(Calendar.MONTH) < dob.get(Calendar.MONTH)
                || (on.get(Calendar.MONTH) == dob.get(Calendar.MONTH)
                && on.get(Calendar.DAY_OF_MONTH) < dob.get(Calendar.DAY_OF_MONTH));
        if (birthdayNotYet) age--;
        return Math.max(0, age);
    }

    public double bmi() {
        if (heightCm <= 0) return 0;
        double m = heightCm / 100.0;
        return weightKg / (m * m);
    }

    public String genderLabel() {
        if (MALE.equals(gender)) return "Male";
        if (FEMALE.equals(gender)) return "Female";
        return "Other";
    }
}

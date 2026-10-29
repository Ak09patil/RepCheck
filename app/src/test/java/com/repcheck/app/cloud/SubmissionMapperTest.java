package com.repcheck.app.cloud;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.repcheck.app.data.Athlete;
import com.repcheck.app.data.TestResult;

import org.junit.Test;

import java.util.Calendar;
import java.util.Map;
import java.util.TimeZone;

public class SubmissionMapperTest {

    private static Athlete athlete() {
        Athlete a = new Athlete();
        a.name = "Test Athlete";
        Calendar dob = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        dob.clear();
        dob.set(2008, Calendar.MARCH, 15);
        a.dobMillis = dob.getTimeInMillis();
        a.gender = Athlete.FEMALE;
        a.heightCm = 160;
        a.weightKg = 50;
        a.state = "Telangana";
        a.district = null; // optional field left blank
        return a;
    }

    private static TestResult result(long recordedAt) {
        TestResult r = new TestResult();
        r.exercise = "VERTICAL_JUMP";
        r.recordedAt = recordedAt;
        r.scoreValue = 38.4;
        r.scoreUnit = "cm";
        r.status = "VERIFIED";
        r.videoSha256 = "ab".repeat(32);
        r.videoPath = "/data/user/0/com.repcheck.app/files/videos/x.mp4";
        return r;
    }

    private static long date(int y, int m, int d) {
        Calendar c = Calendar.getInstance();
        c.clear();
        c.set(y, m, d, 12, 0);
        return c.getTimeInMillis();
    }

    @Test public void submissionCarriesWhatOfficialsNeed() {
        Map<String, Object> m = SubmissionMapper.submission(result(date(2026, Calendar.OCTOBER, 27)),
                athlete(), "uid123", "0.1");
        assertEquals("uid123", m.get("athleteUid"));
        assertEquals("Vertical jump", m.get("exerciseName"));
        assertEquals(38.4, (Double) m.get("score"), 1e-9);
        assertEquals("ab".repeat(32), m.get("videoSha256"));
        assertEquals("", m.get("district"));
        assertEquals("", m.get("reasons"));
    }

    @Test public void localVideoPathNeverLeavesThePhone() {
        Map<String, Object> m = SubmissionMapper.submission(result(date(2026, Calendar.OCTOBER, 27)),
                athlete(), "u", "0.1");
        for (Object v : m.values()) {
            assertFalse(String.valueOf(v).contains("/data/user"));
        }
        assertFalse(m.containsKey("videoPath"));
        assertFalse("reviews are set only by officials", m.containsKey("review"));
    }

    @Test public void ageIsAgeOnTheDayOfTheTest() {
        // Born 15 March 2008: turns 18 on 15 March 2026, still 17 the day before.
        assertEquals(18, SubmissionMapper.submission(result(date(2026, Calendar.MARCH, 15)), athlete(), "u", "v").get("ageAtTest"));
        assertEquals(17, SubmissionMapper.submission(result(date(2026, Calendar.MARCH, 14)), athlete(), "u", "v").get("ageAtTest"));
    }

    @Test public void profileHasNoNulls() {
        Map<String, Object> p = SubmissionMapper.profile(athlete());
        for (Map.Entry<String, Object> e : p.entrySet()) assertTrue(e.getKey(), e.getValue() != null);
    }
}

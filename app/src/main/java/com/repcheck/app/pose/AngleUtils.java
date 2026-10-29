package com.repcheck.app.pose;

public final class AngleUtils {
    private AngleUtils() {}

    /** Angle at point b (in degrees, 0..180) formed by a-b-c. */
    public static double jointAngle(PosePoint a, PosePoint b, PosePoint c) {
        double abx = a.x - b.x, aby = a.y - b.y;
        double cbx = c.x - b.x, cby = c.y - b.y;
        double dot = abx * cbx + aby * cby;
        double mag = Math.hypot(abx, aby) * Math.hypot(cbx, cby);
        if (mag == 0) return 0;
        double cos = Math.max(-1, Math.min(1, dot / mag));
        return Math.toDegrees(Math.acos(cos));
    }

    /** How far the line from 'top' to 'bottom' leans away from vertical, in degrees (0 = upright). */
    public static double leanFromVertical(PosePoint top, PosePoint bottom) {
        double dx = Math.abs(top.x - bottom.x);
        double dy = Math.abs(top.y - bottom.y);
        return Math.toDegrees(Math.atan2(dx, dy));
    }
}

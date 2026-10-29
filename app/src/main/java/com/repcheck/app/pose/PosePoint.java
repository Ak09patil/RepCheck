package com.repcheck.app.pose;

/**
 * One body keypoint in pixel coordinates.
 * Kept free of MediaPipe classes so the counting logic can be unit-tested on a laptop.
 */
public final class PosePoint {
    public final double x;
    public final double y;
    /** 0..1 — how confident the model is that this point is visible in frame. */
    public final double visibility;

    public PosePoint(double x, double y, double visibility) {
        this.x = x;
        this.y = y;
        this.visibility = visibility;
    }
}

package com.repcheck.app.pose;

/**
 * MediaPipe Pose landmark indices for one side of the body.
 * Full list: https://ai.google.dev/edge/mediapipe/solutions/vision/pose_landmarker
 */
public enum BodySide {
    LEFT(11, 13, 15, 23, 25, 27, 29, 31),
    RIGHT(12, 14, 16, 24, 26, 28, 30, 32);

    public final int shoulder, elbow, wrist, hip, knee, ankle, heel, toe;

    BodySide(int shoulder, int elbow, int wrist, int hip, int knee, int ankle, int heel, int toe) {
        this.shoulder = shoulder;
        this.elbow = elbow;
        this.wrist = wrist;
        this.hip = hip;
        this.knee = knee;
        this.ankle = ankle;
        this.heel = heel;
        this.toe = toe;
    }

    /** Pairs of landmark indices to draw as the skeleton. */
    public static final int[][] SKELETON = {
            {11, 12}, {11, 13}, {13, 15}, {12, 14}, {14, 16},
            {11, 23}, {12, 24}, {23, 24},
            {23, 25}, {25, 27}, {24, 26}, {26, 28}
    };
}

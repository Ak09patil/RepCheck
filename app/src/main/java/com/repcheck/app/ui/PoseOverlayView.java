package com.repcheck.app.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

import com.repcheck.app.pose.BodySide;
import com.repcheck.app.pose.PosePoint;

import java.util.Collections;
import java.util.List;

/**
 * Draws the detected skeleton on top of the camera preview.
 * Matches PreviewView's FILL_CENTER scaling so dots line up with the body on screen.
 */
public class PoseOverlayView extends View {

    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pointPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private List<PosePoint> points = Collections.emptyList();
    private int imageWidth = 1, imageHeight = 1;

    public PoseOverlayView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        linePaint.setColor(Color.parseColor("#4ADE80"));
        linePaint.setStrokeWidth(8f);
        linePaint.setStrokeCap(Paint.Cap.ROUND);
        pointPaint.setColor(Color.WHITE);
    }

    /** Safe to call from the UI thread only. */
    public void setPose(List<PosePoint> points, int imageWidth, int imageHeight, boolean good) {
        this.points = points;
        this.imageWidth = Math.max(1, imageWidth);
        this.imageHeight = Math.max(1, imageHeight);
        linePaint.setColor(Color.parseColor(good ? "#4ADE80" : "#FBBF24"));
        invalidate();
    }

    public void clear() {
        points = Collections.emptyList();
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (points.size() < 33) return;

        float scale = Math.max(getWidth() / (float) imageWidth, getHeight() / (float) imageHeight);
        float offsetX = (getWidth() - imageWidth * scale) / 2f;
        float offsetY = (getHeight() - imageHeight * scale) / 2f;

        for (int[] bone : BodySide.SKELETON) {
            PosePoint a = points.get(bone[0]);
            PosePoint b = points.get(bone[1]);
            if (a.visibility < 0.5 || b.visibility < 0.5) continue;
            canvas.drawLine(
                    (float) a.x * scale + offsetX, (float) a.y * scale + offsetY,
                    (float) b.x * scale + offsetX, (float) b.y * scale + offsetY,
                    linePaint);
        }
        for (int i = 11; i <= 28; i++) {
            PosePoint p = points.get(i);
            if (p.visibility < 0.5) continue;
            canvas.drawCircle((float) p.x * scale + offsetX, (float) p.y * scale + offsetY, 10f, pointPaint);
        }
    }
}

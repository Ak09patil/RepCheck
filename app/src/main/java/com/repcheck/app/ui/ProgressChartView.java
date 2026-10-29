package com.repcheck.app.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.View;

import androidx.annotation.Nullable;

import com.repcheck.app.progress.Rating;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Small hand-drawn line chart of scores over attempts (no chart library needed).
 *  - filled dots + line: verified attempts
 *  - hollow dots: attempts that weren't verified (shown, but not part of the trend)
 *  - dashed lines: benchmark cut-offs for Average / Good / Excellent
 */
public class ProgressChartView extends View {

    public static final class Point {
        final double value;
        final boolean verified;

        public Point(double value, boolean verified) {
            this.value = value;
            this.verified = verified;
        }
    }

    private final List<Point> points = new ArrayList<>();
    private double[] cutoffs; // Below avg, Average, Good, Excellent — may be null

    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hollow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint guide = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint axis = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float density;

    public ProgressChartView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;

        TypedValue tv = new TypedValue();
        context.getTheme().resolveAttribute(android.R.attr.textColorSecondary, tv, true);
        int textColor = tv.resourceId != 0 ? context.getColor(tv.resourceId) : Color.GRAY;

        line.setColor(Color.parseColor("#16A34A"));
        line.setStrokeWidth(3 * density);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeJoin(Paint.Join.ROUND);
        dot.setColor(Color.parseColor("#16A34A"));
        hollow.setColor(Color.parseColor("#D97706"));
        hollow.setStyle(Paint.Style.STROKE);
        hollow.setStrokeWidth(2 * density);
        guide.setStyle(Paint.Style.STROKE);
        guide.setStrokeWidth(1.5f * density);
        guide.setPathEffect(new DashPathEffect(new float[]{8 * density, 6 * density}, 0));
        axis.setColor(textColor);
        axis.setAlpha(80);
        axis.setStrokeWidth(density);
        text.setColor(textColor);
        text.setTextSize(11 * getResources().getDisplayMetrics().scaledDensity);
    }

    public void setData(List<Point> data, @Nullable double[] benchmarkCutoffs) {
        points.clear();
        points.addAll(data);
        cutoffs = benchmarkCutoffs;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float left = 36 * density, right = getWidth() - 64 * density;
        float top = 12 * density, bottom = getHeight() - 16 * density;

        if (points.isEmpty()) {
            text.setTextAlign(Paint.Align.CENTER);
            canvas.drawText("No attempts yet", getWidth() / 2f, getHeight() / 2f, text);
            return;
        }

        // Y range covers all scores and the benchmark lines, starting at 0.
        double max = 1;
        for (Point p : points) max = Math.max(max, p.value);
        if (cutoffs != null) max = Math.max(max, cutoffs[3]);
        max = niceCeil(max * 1.1);

        canvas.drawLine(left, bottom, right, bottom, axis);
        text.setTextAlign(Paint.Align.RIGHT);
        canvas.drawText("0", left - 6 * density, bottom, text);
        canvas.drawText(format(max), left - 6 * density, top + 10 * density, text);

        // Benchmark lines (Average, Good, Excellent).
        if (cutoffs != null) {
            text.setTextAlign(Paint.Align.LEFT);
            for (int level = 3; level <= 5; level++) {
                float y = yFor(cutoffs[level - 2], max, top, bottom);
                guide.setColor(ResultFormat.ratingColor(level));
                guide.setAlpha(140);
                canvas.drawLine(left, y, right, y, guide);
                canvas.drawText(Rating.label(level), right + 6 * density, y + 4 * density, text);
            }
        }

        // Line through verified points.
        float step = points.size() == 1 ? 0 : (right - left) / (points.size() - 1);
        Path path = new Path();
        boolean started = false;
        for (int i = 0; i < points.size(); i++) {
            Point p = points.get(i);
            if (!p.verified) continue;
            float x = points.size() == 1 ? (left + right) / 2 : left + i * step;
            float y = yFor(p.value, max, top, bottom);
            if (started) path.lineTo(x, y);
            else {
                path.moveTo(x, y);
                started = true;
            }
        }
        canvas.drawPath(path, line);

        for (int i = 0; i < points.size(); i++) {
            Point p = points.get(i);
            float x = points.size() == 1 ? (left + right) / 2 : left + i * step;
            float y = yFor(p.value, max, top, bottom);
            if (p.verified) canvas.drawCircle(x, y, 5 * density, dot);
            else canvas.drawCircle(x, y, 5 * density, hollow);
        }
    }

    private static float yFor(double v, double max, float top, float bottom) {
        return (float) (bottom - (v / max) * (bottom - top));
    }

    private static double niceCeil(double v) {
        if (v <= 10) return Math.ceil(v);
        if (v <= 50) return Math.ceil(v / 5) * 5;
        return Math.ceil(v / 10) * 10;
    }

    private static String format(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.format(Locale.US, "%.1f", v);
    }
}

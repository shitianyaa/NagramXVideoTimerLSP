package com.shitianyaa.nagramx.videotimer;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

/** AC icon set. Pure Android Canvas; no host resource or bitmap required. */
final class ListenVideoIcons extends Drawable {
    private static final float VIEWPORT = 24f;
    private static final float STROKE = 1.65f;

    private final boolean timer;
    private final int sizePx;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF arc = new RectF();
    private final int color;
    private int alpha = 255;
    private ColorFilter colorFilter;
    private ColorStateList tint;
    private PorterDuff.Mode tintMode = PorterDuff.Mode.SRC_IN;
    private PorterDuffColorFilter tintFilter;
    private boolean hasTintFilter;
    private int lastTintColor;
    private PorterDuff.Mode lastTintMode;

    static ListenVideoIcons listen(Context context, int color) {
        return new ListenVideoIcons(context, false, color);
    }

    static ListenVideoIcons timer(Context context, int color) {
        return new ListenVideoIcons(context, true, color);
    }

    private ListenVideoIcons(Context context, boolean timer, int color) {
        this.timer = timer;
        this.color = color;
        sizePx = Math.max(1, Math.round(VIEWPORT * context.getResources().getDisplayMetrics().density));
        setBounds(0, 0, sizePx, sizePx);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
    }

    @Override
    public int getIntrinsicWidth() {
        return sizePx;
    }

    @Override
    public int getIntrinsicHeight() {
        return sizePx;
    }

    @Override
    public void draw(Canvas canvas) {
        Rect b = getBounds();
        float size = Math.min(b.width(), b.height());
        if (size <= 0f) return;

        paint.setColor(color);
        paint.setAlpha(Math.round((color >>> 24) * alpha / 255f));
        paint.setColorFilter(colorFilter != null ? colorFilter : tintFilter);

        int save = canvas.save();
        canvas.translate(b.exactCenterX() - size / 2f, b.exactCenterY() - size / 2f);
        canvas.scale(size / VIEWPORT, size / VIEWPORT);
        if (timer) drawTimer(canvas);
        else drawListen(canvas);
        canvas.restoreToCount(save);
    }

    private void drawListen(Canvas canvas) {
        // Rounded headband: a true semicircle, joined to hollow capsule earcups.
        arc.set(4.4f, 4f, 19.6f, 19.2f);
        canvas.drawArc(arc, 180f, 180f, false, paint);
        canvas.drawRoundRect(2.3f, 11.6f, 6.5f, 20.1f, 2.1f, 2.1f, paint);
        canvas.drawRoundRect(17.5f, 11.6f, 21.7f, 20.1f, 2.1f, 2.1f, paint);

        // Three round-ended equalizer bars, centered between the earcups.
        bar(canvas, 9.3f, 15.5f, 2f);
        bar(canvas, 12f, 15.5f, 5f);
        bar(canvas, 14.7f, 15.5f, 2f);
    }

    private void drawTimer(Canvas canvas) {
        // Stopwatch body: one long arc and one short arc, both with rounded ends.
        arc.set(4f, 5.4f, 20f, 21.4f);
        canvas.drawArc(arc, 0f, 270f, false, paint);
        canvas.drawArc(arc, -62f, 42f, false, paint);

        // Small pill-shaped crown. No sharp clock hands or arrowheads.
        canvas.drawLine(10f, 2.7f, 14f, 2.7f, paint);
        canvas.drawLine(12f, 2.7f, 12f, 5.4f, paint);

        // Four rounded bars: short, medium, tall, medium-short.
        bar(canvas, 8f, 14f, 1.5f);
        bar(canvas, 10.65f, 14f, 4f);
        bar(canvas, 13.3f, 14f, 6.2f);
        bar(canvas, 15.95f, 14f, 2.8f);
    }

    private void bar(Canvas canvas, float x, float cy, float len) {
        float half = len / 2f;
        canvas.drawLine(x, cy - half, x, cy + half, paint);
    }

    @Override
    public void setAlpha(int alpha) {
        int clamped = Math.max(0, Math.min(255, alpha));
        if (this.alpha != clamped) {
            this.alpha = clamped;
            invalidateSelf();
        }
    }

    @Override
    public int getAlpha() {
        return alpha;
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
        if (this.colorFilter != colorFilter) {
            this.colorFilter = colorFilter;
            invalidateSelf();
        }
    }

    @Override
    public void setTintList(ColorStateList tint) {
        this.tint = tint;
        updateTintFilter();
    }

    @Override
    public void setTintMode(PorterDuff.Mode tintMode) {
        this.tintMode = tintMode != null ? tintMode : PorterDuff.Mode.SRC_IN;
        updateTintFilter();
    }

    @Override
    public boolean isStateful() {
        return super.isStateful() || (tint != null && tint.isStateful());
    }

    @Override
    protected boolean onStateChange(int[] state) {
        return updateTintFilter();
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }

    private boolean updateTintFilter() {
        if (tint == null || tintMode == null) {
            if (!hasTintFilter) {
                return false;
            }
            tintFilter = null;
            hasTintFilter = false;
            lastTintColor = 0;
            lastTintMode = null;
            invalidateSelf();
            return true;
        }
        int nextColor = tint.getColorForState(getState(), tint.getDefaultColor());
        if (hasTintFilter && nextColor == lastTintColor && tintMode == lastTintMode) {
            return false;
        }
        tintFilter = new PorterDuffColorFilter(nextColor, tintMode);
        hasTintFilter = true;
        lastTintColor = nextColor;
        lastTintMode = tintMode;
        invalidateSelf();
        return true;
    }
}

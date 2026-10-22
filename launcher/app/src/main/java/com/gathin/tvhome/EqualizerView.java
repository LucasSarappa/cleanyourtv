package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;

/**
 * The "music playing" bars next to the artist (as in the reference video): four rounded bars bouncing out of step
 * while something plays, resting low when paused. Redraws only while playing and on screen.
 */
public class EqualizerView extends View {
    private static final float[] SPEED = {6.1f, 4.3f, 7.4f, 5.2f};
    private static final float[] PHASE = {0f, 1.7f, 0.6f, 2.6f};
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF bar = new RectF();
    private boolean playing;

    public EqualizerView(Context context, AttributeSet attrs) {
        super(context, attrs);
        paint.setColor(0xFF4ADE80);
    }

    void setPlaying(boolean playing) {
        if (this.playing == playing) return;
        this.playing = playing;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int n = SPEED.length;
        float gap = getWidth() * 0.12f;
        float w = (getWidth() - gap * (n - 1)) / n;
        float h = getHeight();
        float t = SystemClock.uptimeMillis() / 1000f;
        for (int i = 0; i < n; i++) {
            float level = playing ? 0.3f + 0.7f * Math.abs((float) Math.sin(t * SPEED[i] + PHASE[i])) : 0.25f;
            float left = i * (w + gap);
            bar.set(left, h * (1f - level), left + w, h);
            canvas.drawRoundRect(bar, w / 2f, w / 2f, paint);
        }
        if (playing && isShown()) postInvalidateOnAnimation();
    }
}

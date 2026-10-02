package com.herohan.uvcapp.activity;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

public class BufferProgressView extends View {
    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint progressPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float progress;
    private int seconds;
    private int target;

    public BufferProgressView(Context context) { super(context); init(); }
    public BufferProgressView(Context context, @Nullable AttributeSet attrs) { super(context, attrs); init(); }
    public BufferProgressView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) { super(context, attrs, defStyleAttr); init(); }

    private void init() {
        trackPaint.setStyle(Paint.Style.STROKE);
        trackPaint.setStrokeWidth(dp(3));
        trackPaint.setColor(0x662A2340);
        progressPaint.setStyle(Paint.Style.STROKE);
        progressPaint.setStrokeWidth(dp(3));
        progressPaint.setStrokeCap(Paint.Cap.ROUND);
        progressPaint.setColor(0xFF00E5FF);
        textPaint.setColor(0xFFFFFFFF);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTextSize(dp(10));
        textPaint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        progressPaint.setShadowLayer(dp(6), 0, 0, 0x9900E5FF);
    }

    private float dp(float value) { return value * getResources().getDisplayMetrics().density; }

    public void setProgress(int seconds, int target) {
        this.seconds = Math.max(0, seconds);
        this.target = Math.max(1, target);
        progress = Math.min(1f, this.seconds / (float) this.target);
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float pad = dp(4);
        float size = Math.min(getWidth(), getHeight()) - pad * 2;
        RectF oval = new RectF((getWidth()-size)/2f, (getHeight()-size)/2f,
                (getWidth()+size)/2f, (getHeight()+size)/2f);
        canvas.drawArc(oval, -90, 360, false, trackPaint);
        canvas.drawArc(oval, -90, progress * 360, false, progressPaint);
        Paint.FontMetrics fm = textPaint.getFontMetrics();
        float baseline = getHeight()/2f - (fm.ascent + fm.descent)/2f;
        canvas.drawText(seconds + "/" + target + "s", getWidth()/2f, baseline, textPaint);
    }
}
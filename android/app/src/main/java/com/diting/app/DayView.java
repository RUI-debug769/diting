package com.diting.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * 一天的一页：一行一件事，左边是你说过的原话，右边是谛听的话。
 *
 * 为什么整页自己画，而不用 LinearLayout + TextView：
 * 两栏等高对齐、中间一条贯通的竖线、右栏压暗，这三件事在嵌套的 LinearLayout
 * 里要靠 weight 与 MATCH_PARENT 互相迁就，实测会塌成零宽。自己画就没有这种
 * 测量上的循环依赖：每一行的行高由两栏里较高的那个 StaticLayout 决定，竖线只
 * 是画上去的一条线。
 */
public class DayView extends View {

    private static final int BG     = 0xFF12100E;
    private static final int INK    = 0xFFE8E2D8;
    private static final int MUTED  = 0xFF8A8074;
    private static final int FAINT  = 0xFF6B635A;
    private static final int DIVIDE = 0xFF2A2622;

    private final float density;

    private final TextPaint leftPaint  = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint rightPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private String[][] events = new String[0][];
    private int deniedIndex = -1;

    private final List<Row> rows = new ArrayList<>();
    private int measuredHeight = 0;

    /** 一行在测量阶段算好的东西，onDraw 直接用，避免重复排版。 */
    private static final class Row {
        StaticLayout left;
        StaticLayout right;
        int top;
        int height;
    }

    public DayView(Context c) {
        super(c);
        density = c.getResources().getDisplayMetrics().density;
        setBackgroundColor(BG);

        leftPaint.setColor(INK);
        leftPaint.setTextSize(sp(15f));

        rightPaint.setColor(MUTED);
        rightPaint.setTextSize(sp(15f));

        linePaint.setColor(DIVIDE);
        linePaint.setStrokeWidth(Math.max(1f, dp(1f)));
    }

    public void setEvents(String[][] data, int denied) {
        this.events = data == null ? new String[0][] : data;
        this.deniedIndex = denied;
        requestLayout();
        invalidate();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int w = MeasureSpec.getSize(widthMeasureSpec);
        if (w <= 0) {
            w = getResources().getDisplayMetrics().widthPixels;
        }

        build(w);

        int h = MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY
                ? MeasureSpec.getSize(heightMeasureSpec)
                : measuredHeight;
        setMeasuredDimension(w, Math.max(h, measuredHeight));
    }

    private void build(int width) {
        rows.clear();

        int pad    = (int) dp(16f);
        int gap    = (int) dp(14f);
        int colW   = (width - pad * 2 - gap * 2 - 1) / 2;
        int vPad   = (int) dp(12f);

        if (colW < 32) {
            colW = 32;
        }

        int y = (int) dp(4f);

        for (int i = 0; i < events.length; i++) {
            boolean denied = (i == deniedIndex);

            TextPaint rp = rightPaint;
            if (denied) {
                rp = new TextPaint(rightPaint);
                rp.setColor(FAINT);
                rp.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.ITALIC));
            }

            StaticLayout l = layout(events[i][0], leftPaint, colW);
            StaticLayout r = layout(events[i][1], rp, colW);

            Row row = new Row();
            row.left  = l;
            row.right = r;
            row.top   = y;
            row.height = Math.max(l.getHeight(), r.getHeight()) + vPad * 2;
            rows.add(row);

            y += row.height;
        }

        measuredHeight = y + (int) dp(8f);
    }

    private StaticLayout layout(String text, TextPaint paint, int width) {
        if (android.os.Build.VERSION.SDK_INT >= 23) {
            return StaticLayout.Builder
                    .obtain(text, 0, text.length(), paint, width)
                    .setLineSpacing(dp(5f), 1f)
                    .setIncludePad(false)
                    .build();
        }
        return new StaticLayout(text, paint, width, Layout.Alignment.ALIGN_NORMAL,
                dp(5f), 1f, false);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        int pad  = (int) dp(16f);
        int gap  = (int) dp(14f);
        float midX = pad + (getWidth() - pad * 2 - gap * 2 - 1) / 2f + gap;

        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            int textTop = row.top + (int) dp(12f);

            canvas.save();
            canvas.translate(pad, textTop);
            row.left.draw(canvas);
            canvas.restore();

            canvas.save();
            canvas.translate(pad + (getWidth() - pad * 2 - gap * 2 - 1) / 2f + gap + 1 + gap, textTop);
            row.right.draw(canvas);
            canvas.restore();

            // 行之间的横线
            if (i < rows.size() - 1) {
                int yy = row.top + row.height;
                canvas.drawLine(pad, yy, getWidth() - pad, yy, linePaint);
            }
        }

        // 整条竖线在循环外一次画完：从页面顶到底，分段画会在行间留下接缝。
        // 一条记录都没有时不画孤线。
        if (!rows.isEmpty()) {
            canvas.drawLine(midX, 0, midX, getHeight(), linePaint);
        }
    }

    private float sp(float v) {
        return v * getResources().getDisplayMetrics().scaledDensity;
    }

    private float dp(float v) {
        return v * density;
    }
}

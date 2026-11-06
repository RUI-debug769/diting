package com.diting.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;

/** 一个只会画图标的方块。点的响应交给外面（setOnClickListener）。 */
public class IconView extends View {

    private final Paint paint = new Paint();
    private int icon;
    private int color = Theme.MUTED;
    private float sizeDp = 22f;

    public IconView(Context ctx, int icon) {
        super(ctx);
        this.icon = icon;
    }

    public IconView icon(int id) {
        icon = id;
        invalidate();
        return this;
    }

    public IconView tint(int c) {
        color = c;
        invalidate();
        return this;
    }

    public IconView size(float dp) {
        sizeDp = dp;
        requestLayout();
        return this;
    }

    public int color() {
        return color;
    }

    @Override protected void onMeasure(int wSpec, int hSpec) {
        int s = dp(sizeDp);
        setMeasuredDimension(s, s);
    }

    @Override protected void onDraw(Canvas c) {
        float s = Math.min(getWidth(), getHeight()) * 0.82f;
        c.save();
        c.translate((getWidth() - s) / 2f, (getHeight() - s) / 2f);
        Icons.draw(c, icon, s, paint, color);
        c.restore();
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}

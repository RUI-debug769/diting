package com.diting.app;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;

/**
 * 细线图标。全部在 24×24 的设计格子里画，调用方给出边长，自己缩。
 *
 * 为什么不用 emoji 或图片：这个工程不编译 res/、不生成 R，emoji 在不同
 * 手机上长相不一样；所以图标一律用 Path 画，线宽跟着尺寸走，永远 1.5dp 左右。
 */
public final class Icons {

    public static final int CALENDAR = 1;
    public static final int SUN = 2;
    public static final int MOON = 3;
    public static final int CLOCK = 4;
    public static final int MIC = 5;
    public static final int TRASH = 6;
    public static final int WIFI = 7;
    public static final int SEND = 8;
    public static final int CLOUD = 9;
    public static final int KEY = 10;
    public static final int ERASE = 11;
    public static final int BACK = 12;
    public static final int PLUS = 13;
    public static final int MINUS = 14;
    public static final int CHECK = 15;
    public static final int CHEVRON_L = 16;
    public static final int CHEVRON_R = 17;
    public static final int GEAR = 18;
    public static final int CONTRAST = 19;
    public static final int SOUND = 20;

    private Icons() {
    }

    /** 在 (0,0)-(sz,sz) 里画一个图标；p 的描边宽度由这里设，调用方不用管。 */
    public static void draw(Canvas c, int id, float sz, Paint p, int color) {
        float k = sz / 24f;
        p.reset();
        p.setAntiAlias(true);
        p.setColor(color);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(1.7f * k);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);

        c.save();
        c.scale(k, k);
        Path path = new Path();
        RectF r = new RectF();

        switch (id) {
            case CALENDAR:
                r.set(3, 5, 21, 21);
                c.drawRoundRect(r, 3, 3, p);
                c.drawLine(3, 10, 21, 10, p);
                c.drawLine(8, 2.5f, 8, 6.5f, p);
                c.drawLine(16, 2.5f, 16, 6.5f, p);
                p.setStyle(Paint.Style.FILL);
                c.drawCircle(8, 14.5f, 1.3f, p);
                c.drawCircle(12, 14.5f, 1.3f, p);
                c.drawCircle(16, 14.5f, 1.3f, p);
                c.drawCircle(8, 18, 1.3f, p);
                p.setStyle(Paint.Style.STROKE);
                break;

            case SUN:
                c.drawCircle(12, 12, 4.6f, p);
                for (int i = 0; i < 8; i++) {
                    double a = Math.PI * i / 4.0;
                    float x0 = 12 + (float) Math.cos(a) * 7.6f;
                    float y0 = 12 + (float) Math.sin(a) * 7.6f;
                    float x1 = 12 + (float) Math.cos(a) * 10.2f;
                    float y1 = 12 + (float) Math.sin(a) * 10.2f;
                    c.drawLine(x0, y0, x1, y1, p);
                }
                break;

            case MOON:
                path.moveTo(15.5f, 3.2f);
                path.quadTo(7.0f, 4.2f, 7.0f, 12.0f);
                path.quadTo(7.0f, 19.8f, 15.5f, 20.8f);
                path.quadTo(10.0f, 16.6f, 10.0f, 12.0f);
                path.quadTo(10.0f, 7.4f, 15.5f, 3.2f);
                path.close();
                c.drawPath(path, p);
                break;

            case CLOCK:
                c.drawCircle(12, 12, 9, p);
                c.drawLine(12, 6.5f, 12, 12.4f, p);
                c.drawLine(12, 12.4f, 16, 14.6f, p);
                break;

            case MIC:
                r.set(9, 2.5f, 15, 13.5f);
                c.drawRoundRect(r, 3, 3, p);
                r.set(5.5f, 8.5f, 18.5f, 17.5f);
                c.drawArc(r, 0, 180, false, p);
                c.drawLine(12, 17.5f, 12, 21.5f, p);
                c.drawLine(8.5f, 21.5f, 15.5f, 21.5f, p);
                break;

            case TRASH:
                c.drawLine(3.5f, 6.5f, 20.5f, 6.5f, p);
                c.drawLine(9, 6.5f, 9.5f, 3.5f, p);
                c.drawLine(15, 6.5f, 14.5f, 3.5f, p);
                c.drawLine(9.5f, 3.5f, 14.5f, 3.5f, p);
                path.moveTo(6, 6.5f);
                path.lineTo(7.2f, 20.5f);
                path.lineTo(16.8f, 20.5f);
                path.lineTo(18, 6.5f);
                c.drawPath(path, p);
                c.drawLine(10.2f, 10, 10.6f, 17.5f, p);
                c.drawLine(13.8f, 10, 13.4f, 17.5f, p);
                break;

            case WIFI:
                p.setStyle(Paint.Style.FILL);
                c.drawCircle(12, 18.6f, 1.4f, p);
                p.setStyle(Paint.Style.STROKE);
                r.set(4.2f, 6.4f, 19.8f, 21.6f);
                c.drawArc(r, 215, 110, false, p);
                r.set(7.6f, 10.2f, 16.4f, 20.0f);
                c.drawArc(r, 215, 110, false, p);
                break;

            case SEND:
                path.moveTo(3.2f, 11.4f);
                path.lineTo(21.0f, 3.4f);
                path.lineTo(13.2f, 20.8f);
                path.lineTo(11.4f, 13.6f);
                path.close();
                c.drawPath(path, p);
                break;

            case CLOUD:
                path.moveTo(7.4f, 18.6f);
                path.quadTo(3.2f, 18.6f, 3.6f, 14.9f);
                path.quadTo(4.0f, 11.7f, 7.4f, 11.9f);
                path.quadTo(8.2f, 6.4f, 13.0f, 6.6f);
                path.quadTo(17.2f, 6.8f, 18.0f, 11.2f);
                path.quadTo(21.4f, 11.6f, 21.0f, 15.2f);
                path.quadTo(20.6f, 18.6f, 16.8f, 18.6f);
                path.close();
                c.drawPath(path, p);
                break;

            case KEY:
                c.drawCircle(8.6f, 8.6f, 4.4f, p);
                c.drawLine(11.4f, 11.4f, 20.4f, 20.4f, p);
                c.drawLine(16.2f, 16.2f, 18.4f, 14.0f, p);
                c.drawLine(18.6f, 18.6f, 20.8f, 16.4f, p);
                break;

            case ERASE:
                path.moveTo(8.2f, 19.4f);
                path.lineTo(15.4f, 12.2f);
                path.lineTo(20.6f, 17.4f);
                path.lineTo(17.4f, 20.6f);
                path.lineTo(10.2f, 20.6f);
                path.close();
                c.drawPath(path, p);
                c.drawLine(15.4f, 12.2f, 10.4f, 7.2f, p);
                c.drawLine(10.4f, 7.2f, 3.4f, 14.2f, p);
                c.drawLine(3.4f, 14.2f, 8.2f, 19.4f, p);
                c.drawLine(3.6f, 20.6f, 20.4f, 20.6f, p);
                break;

            case BACK:
                c.drawLine(19.5f, 12, 4.8f, 12, p);
                c.drawLine(10.6f, 5.6f, 4.2f, 12, p);
                c.drawLine(10.6f, 18.4f, 4.2f, 12, p);
                break;

            case PLUS:
                c.drawLine(12, 4.5f, 12, 19.5f, p);
                c.drawLine(4.5f, 12, 19.5f, 12, p);
                break;

            case MINUS:
                c.drawLine(4.5f, 12, 19.5f, 12, p);
                break;

            case CHEVRON_L:
                c.drawLine(15f, 5.5f, 8.5f, 12f, p);
                c.drawLine(8.5f, 12f, 15f, 18.5f, p);
                break;

            case CONTRAST:
                // 一个圆，左半边填实：一眼看出是「切换明暗」
                c.drawCircle(12, 12, 8.5f, p);
                Path half = new Path();
                half.addArc(3.5f, 3.5f, 20.5f, 20.5f, 90, 180);
                half.close();
                p.setStyle(Paint.Style.FILL);
                c.drawPath(half, p);
                break;

            case GEAR:
                c.drawCircle(12, 12, 4.2f, p);
                for (int i = 0; i < 8; i++) {
                    double a = Math.PI * i / 4.0;
                    float x1 = (float) (12 + Math.cos(a) * 6.4);
                    float y1 = (float) (12 + Math.sin(a) * 6.4);
                    float x2 = (float) (12 + Math.cos(a) * 9.2);
                    float y2 = (float) (12 + Math.sin(a) * 9.2);
                    c.drawLine(x1, y1, x2, y2, p);
                }
                break;

            case CHEVRON_R:
                c.drawLine(9f, 5.5f, 15.5f, 12f, p);
                c.drawLine(15.5f, 12f, 9f, 18.5f, p);
                break;

            case SOUND:
                // 一只小喇叭 + 两道弧：出声，不是「语音助手」。
                Path sp = new Path();
                sp.moveTo(4f, 9.5f);
                sp.lineTo(7.5f, 9.5f);
                sp.lineTo(11.5f, 5.5f);
                sp.lineTo(11.5f, 18.5f);
                sp.lineTo(7.5f, 14.5f);
                sp.lineTo(4f, 14.5f);
                sp.close();
                c.drawPath(sp, p);
                c.drawArc(new RectF(12.5f, 8.5f, 17f, 15.5f), -55, 110, false, p);
                c.drawArc(new RectF(12.5f, 5.5f, 20.5f, 18.5f), -55, 110, false, p);
                break;

            default:
                break;
        }

        c.restore();
    }
}

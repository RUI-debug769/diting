package com.diting.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;

/**
 * 像素石兽：一块会呼吸、会听话、会摇头的镇纸。
 *
 * 它演的不是随机动画，是这一台机器此刻的真实状态：
 *
 *   IDLE     待机            呼吸、每四秒眨眼
 *   SLEEP    三分钟没人理     闭眼、呼吸变慢、右上角冒 zzz
 *   OFFLINE  云端/挂坠断了     耳朵耷下、闭眼、身子沉一格
 *   LISTEN   收到一句话        耳朵竖起、身子抬一下
 *   ACCEPT   这句是你的，记了  眯眼笑、点一下头
 *   REJECT   这句不是你的     闭眼摇头（左右晃一格）
 *   THINK    刚点「让它写」    眼睛往上看，右上角三个点在走
 *   TYPE     模型在吐字        眼睛往下、流苏左右摆
 *   DONE     三栏写完了        ^ ^ 笑眼、跳一下
 *   ERROR    没写成            耳朵耷下、闭眼
 *   POKE     被戳              瞪眼、张嘴、跳一下
 *   WAKE     睡着被吵醒        抖一下再回待机
 *
 * 整只是 20×18 的字符画，一格一个方块画上去。没有图片资源
 * （build_apk.sh 不编译 res/，也不生成 R）。
 */
public class PetView extends View {

    public static final int IDLE = 0;
    public static final int SLEEP = 1;
    public static final int OFFLINE = 2;
    public static final int LISTEN = 3;
    public static final int ACCEPT = 4;
    public static final int REJECT = 5;
    public static final int THINK = 6;
    public static final int TYPE = 7;
    public static final int DONE = 8;
    public static final int ERROR = 9;
    public static final int POKE = 10;
    public static final int WAKE = 11;

    public interface OnPoke {
        void onPoke();
    }

    private static final int W = 20;
    private static final int H = 18;
    private static final long SLEEP_AFTER_MS = 180_000L;

    private static final int C_OUT = 0xFF262219;
    private static final int C_BODY = 0xFF605544;
    private static final int C_HI = 0xFF7C6E5A;
    private static final int C_EYE = 0xFFF0E8D6;
    private static final int C_CORAL = 0xFFD97757;

    private static final String[] F_IDLE = {
            ".........aa.........",
            ".........aa.........",
            "....o...aaaa...o....",
            "...oo...aaaa...oo...",
            "...ooo........ooo...",
            ".....oooooooooo.....",
            "...oobbbbbbbbbboo...",
            "..obhhbbbbbbbbbbbo..",
            "..obhbbbbbbbbbbbbo..",
            "..obbbwwbbbbwwbbbo..",
            "..obbbwwbbbbwwbbbo..",
            "..obbbbbbbbbbbbbbo..",
            "..obbbbbbbbbbbbbbo..",
            "..obbbbbbbbbbbbbbo..",
            "...oobbbbbbbbbboo...",
            ".....oooooooooo.....",
            ".....ooo....ooo.....",
            "....................",
    };

    private static final String[] F_BLINK = {
            ".........aa.........",
            ".........aa.........",
            "....o...aaaa...o....",
            "...oo...aaaa...oo...",
            "...ooo........ooo...",
            ".....oooooooooo.....",
            "...oobbbbbbbbbboo...",
            "..obhhbbbbbbbbbbbo..",
            "..obhbbbbbbbbbbbbo..",
            "..obbbwwwbbwwwbbbo..",
            "..obbbbbbbbbbbbbbo..",
            "..obbbbbbbbbbbbbbo..",
            "..obbbbbbbbbbbbbbo..",
            "..obbbbbbbbbbbbbbo..",
            "...oobbbbbbbbbboo...",
            ".....oooooooooo.....",
            ".....ooo....ooo.....",
            "....................",
    };

    private static final String[] F_LISTEN = {
            ".........aa.........",
            "....o....aa....o....",
            "...oo...aaaa...oo...",
            "...ooo..aaaa..ooo...",
            "...ooo........ooo...",
            ".....oooooooooo.....",
            "...oobbbbbbbbbboo...",
            "..obhhbbbbbbbbbbbo..",
            "..obhbwwbbbbwwbbbo..",
            "..obbbwwbbbbwwbbbo..",
            "..obbbbbbbbbbbbbbo..",
            "..obbbbbbbbbbbbbbo..",
            "..obbbbbbbbbbbbbbo..",
            "..obbbbbbbbbbbbbbo..",
            "...oobbbbbbbbbboo...",
            ".....oooooooooo.....",
            ".....ooo....ooo.....",
            "....................",
    };

    private static final String[] F_WRITE = {
            ".........aa.........",
            ".........aa.........",
            "....o...aaaa...o....",
            "...oo...aaaa...oo...",
            "...ooo........ooo...",
            ".....oooooooooo.....",
            "...oobbbbbbbbbboo...",
            "..obhhbbbbbbbbbbbo..",
            "..obhbbbbbbbbbbbbo..",
            "..obbbbbbbbbbbbbbo..",
            "..obbbwwbbbbwwbbbo..",
            "..obbbwwbbbbwwbbbo..",
            "..obbbbbbbbbbbbbbo..",
            "..obbbbbbbbbbbbbbo..",
            "...oobbbbbbbbbboo...",
            ".....oooooooooo.....",
            ".....ooo....ooo.....",
            "....................",
    };

    private static final String[] F_WRITE2 = {
            "..........aa........",
            "..........aa........",
            "....o....aaaa..o....",
            "...oo....aaaa..oo...",
            "...ooo........ooo...",
            ".....oooooooooo.....",
            "...oobbbbbbbbbboo...",
            "..obhhbbbbbbbbbbbo..",
            "..obhbbbbbbbbbbbbo..",
            "..obbbbbbbbbbbbbbo..",
            "..obbbwwbbbbwwbbbo..",
            "..obbbwwbbbbwwbbbo..",
            "..obbbbbbbbbbbbbbo..",
            "..obbbbbbbbbbbbbbo..",
            "...oobbbbbbbbbboo...",
            ".....oooooooooo.....",
            ".....ooo....ooo.....",
            "....................",
    };

    private static final String[] F_ERROR = {
            ".........aa.........",
            ".........aa.........",
            "........aaaa........",
            "...o....aaaa....o...",
            "..ooo..........ooo..",
            "..oooooooooooooooo..",
            "...oobbbbbbbbbboo...",
            "..obhhbbbbbbbbbbbo..",
            "..obhbbbbbbbbbbbbo..",
            "..obbbwwwbbwwwbbbo..",
            "..obbbbbbbbbbbbbbo..",
            "..obbbbbbbbbbbbbbo..",
            "..obbbbbbbbbbbbbbo..",
            "..obbbbbbbbbbbbbbo..",
            "...oobbbbbbbbbboo...",
            ".....oooooooooo.....",
            ".....ooo....ooo.....",
            "....................",
    };

    private static final String[] F_POKE = {
            ".........aa.........",
            "....o....aa....o....",
            "...oo...aaaa...oo...",
            "...ooo..aaaa..ooo...",
            "...ooo........ooo...",
            ".....oooooooooo.....",
            "...oobbbbbbbbbboo...",
            "..obhhbbbbbbbbbbbo..",
            "..obhwwwwbbwwwwbbo..",
            "..obbwwwwbbwwwwbbo..",
            "..obbwwwwbbwwwwbbo..",
            "..obbwwwwbbwwwwbbo..",
            "..obbbbbbobbbbbbbo..",
            "..obbbbbbbbbbbbbbo..",
            "...oobbbbbbbbbboo...",
            ".....oooooooooo.....",
            ".....ooo....ooo.....",
            "....................",
    };

    private static final String[] F_HAPPY = {
            ".........aa.........",
            "....o....aa....o....",
            "...oo...aaaa...oo...",
            "...ooo..aaaa..ooo...",
            "...ooo........ooo...",
            ".....oooooooooo.....",
            "...oobbbbbbbbbboo...",
            "..obhhbbbbbbbbbbbo..",
            "..obhbbbbbbbbbbbbo..",
            "..obbbbwbbbbwbbbbo..",
            "..obbbwbbbbbbwbbbo..",
            "..obbbbbbbbbbbbbbo..",
            "..obbbbbbbbbbbbbbo..",
            "..obbbbbbbbbbbbbbo..",
            "...oobbbbbbbbbboo...",
            ".....oooooooooo.....",
            ".....ooo....ooo.....",
            "....................",
    };

    private static final String[] F_REJECT = {
            ".........aaa........",
            ".........aaa........",
            "........aaaaa.......",
            "...o....aaaaa...o...",
            "..ooo..........ooo..",
            "..oooooooooooooooo..",
            "...oobbbbbbbbbboo...",
            "..obhhbbbbbbbbbbbo..",
            "..obhbbbbbbbbbbbbo..",
            "..obbbwwwbbwwwbbbo..",
            "..obbbbbbbbbbbbbbo..",
            "..obbbbbbbbbbbbbbo..",
            "..obbbbbbbbbbbbbbo..",
            "..obbbbbbbbbbbbbbo..",
            "...oobbbbbbbbbboo...",
            ".....oooooooooo.....",
            ".....ooo....ooo.....",
            "....................",
    };

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Paint paint = new Paint();
    private final long born = SystemClock.uptimeMillis();

    /** 一次性动作：播完就回到底色（待机/睡着/断线）。 */
    private int flashState = -1;
    private long flashUntil;

    /** 持续性动作：想、写。清掉之后回到底色。 */
    private int heldState = -1;

    private boolean online = true;
    private long lastActivity = born;
    private OnPoke onPoke;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            invalidate();
            main.postDelayed(this, 70);
        }
    };

    public PetView(Context ctx) {
        super(ctx);
        paint.setAntiAlias(false);
        paint.setStyle(Paint.Style.FILL);
        setClickable(true);
    }

    public void setOnPoke(OnPoke l) {
        onPoke = l;
    }

    /** 云端或挂坠的连线状态：断了就一直耷拉着。 */
    public void setOnline(boolean on) {
        if (online == on) {
            return;
        }
        online = on;
        lastActivity = SystemClock.uptimeMillis();
        invalidate();
    }

    /** 有一些活动（新句子、点了写、滑了一下），把「睡着」的计时清零。 */
    public void activity() {
        long now = SystemClock.uptimeMillis();
        boolean wasAsleep = online && now - lastActivity > SLEEP_AFTER_MS;
        lastActivity = now;
        if (wasAsleep) {
            flash(WAKE, 520);
        }
    }

    /** 播一次就结束的动作。 */
    public void flash(int state, long ms) {
        long now = SystemClock.uptimeMillis();
        flashState = state;
        flashUntil = now + ms;
        lastActivity = now;
        invalidate();
    }

    /** 会一直演到 clearHeld() 的动作（THINK / TYPE）。 */
    public void hold(int state) {
        heldState = state;
        lastActivity = SystemClock.uptimeMillis();
        invalidate();
    }

    public void clearHeld() {
        heldState = -1;
        invalidate();
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        if (e.getAction() == MotionEvent.ACTION_DOWN) {
            activity();
            flash(POKE, 900);
            if (onPoke != null) {
                onPoke.onPoke();
            }
            return true;
        }
        return super.onTouchEvent(e);
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        main.postDelayed(tick, 70);
    }

    @Override protected void onDetachedFromWindow() {
        main.removeCallbacks(tick);
        super.onDetachedFromWindow();
    }

    @Override protected void onMeasure(int wSpec, int hSpec) {
        setMeasuredDimension(resolve(wSpec, dp(104)), resolve(hSpec, dp(94)));
    }

    private int resolve(int spec, int want) {
        int size = MeasureSpec.getSize(spec);
        int mode = MeasureSpec.getMode(spec);
        return mode == MeasureSpec.UNSPECIFIED ? want : size;
    }

    private int currentState(long now) {
        if (flashState >= 0 && now < flashUntil) {
            return flashState;
        }
        flashState = -1;
        if (!online) {
            return OFFLINE;
        }
        if (heldState >= 0) {
            return heldState;
        }
        if (now - lastActivity > SLEEP_AFTER_MS) {
            return SLEEP;
        }
        return IDLE;
    }

    @Override protected void onDraw(Canvas c) {
        long now = SystemClock.uptimeMillis();
        int state = currentState(now);
        long t = now - flashUntil + 900;      // 一次性动作已经播了多久
        double whole = (now - born) / 1000.0;

        String[] frame;
        int lift = 0;
        int shake = 0;
        int dots = 0;      // 正在想：1..3 个点
        int zzz = 0;       // 睡着了：右上角冒的字

        switch (state) {
            case SLEEP:
                frame = F_BLINK;
                lift = (whole % 3.2 > 1.6) ? 1 : 0;
                zzz = 1 + (int) ((now / 700) % 3);
                break;

            case OFFLINE:
                frame = F_ERROR;
                lift = 1;
                break;

            case LISTEN:
                frame = F_LISTEN;
                lift = -1;
                break;

            case ACCEPT:
                frame = F_HAPPY;
                lift = t < 220 ? -1 : 0;
                break;

            case REJECT:
                frame = F_REJECT;
                // 左右各晃一格，像在摇头
                shake = (int) (Math.round(Math.sin(t / 90.0) * 1.2));
                break;

            case THINK:
                frame = F_LISTEN;
                dots = 1 + (int) ((now / 320) % 3);
                break;

            case TYPE:
                frame = ((now / 280) % 2 == 0) ? F_WRITE : F_WRITE2;
                break;

            case DONE:
                frame = F_HAPPY;
                if (t < 150) {
                    lift = -2;
                } else if (t < 300) {
                    lift = -1;
                }
                break;

            case ERROR:
                frame = F_ERROR;
                lift = 1;
                break;

            case POKE:
                frame = F_POKE;
                if (t < 170) {
                    lift = -2;
                }
                break;

            case WAKE:
                frame = F_IDLE;
                shake = (t < 260) ? (((int) (t / 90)) % 2 == 0 ? 1 : -1) : 0;
                break;

            default:
                frame = (whole % 4.0 > 3.80) ? F_BLINK : F_IDLE;
                lift = (whole % 1.6 > 0.8) ? 1 : 0;
                break;
        }

        float cell = (float) Math.floor(Math.min(getWidth() / (float) W, getHeight() / (float) H));
        if (cell < 1f) {
            cell = 1f;
        }
        float ox = (getWidth() - W * cell) / 2f + shake * cell;
        float oy = (getHeight() - H * cell) / 2f;

        // 影子：跳起来的时候淡一些
        paint.setColor(0x33000000);
        int hi = Math.max(0, -lift);
        c.drawRect(ox + (5 + hi) * cell, oy + (H - 1.4f) * cell,
                ox + (15 - hi) * cell, oy + (H - 0.6f) * cell, paint);

        for (int y = 0; y < frame.length; y++) {
            String row = frame[y];
            for (int x = 0; x < row.length(); x++) {
                int col = colorOf(row.charAt(x));
                if (col == 0) {
                    continue;
                }
                paint.setColor(col);
                float l = Math.round(ox + x * cell);
                float tp = Math.round(oy + (y + lift) * cell);
                c.drawRect(l, tp, l + Math.round(cell) + 1f, tp + Math.round(cell) + 1f, paint);
            }
        }

        // 想：右上角三个点
        if (dots > 0) {
            paint.setColor(C_CORAL);
            for (int i = 0; i < dots; i++) {
                float l = Math.round(ox + (15 + (i % 3) * 1.4f) * cell);
                float tp = Math.round(oy + (2 + (i / 3) * 1.2f) * cell);
                c.drawRect(l, tp, l + Math.round(cell * 0.8f), tp + Math.round(cell * 0.8f), paint);
            }
        }

        // 睡着：右上角冒 z
        if (zzz > 0) {
            paint.setColor(0xFF8C7F69);
            for (int i = 0; i < zzz; i++) {
                float l = Math.round(ox + (15.5f + i * 1.6f) * cell);
                float tp = Math.round(oy + (3.2f - i * 1.1f) * cell);
                c.drawRect(l, tp, l + Math.round(cell * 0.9f), tp + Math.round(cell * 0.9f), paint);
            }
        }
    }

    private int colorOf(char ch) {
        switch (ch) {
            case 'o': return C_OUT;
            case 'b': return C_BODY;
            case 'h': return C_HI;
            case 'w': return C_EYE;
            case 'a': return C_CORAL;
            default: return 0;
        }
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}

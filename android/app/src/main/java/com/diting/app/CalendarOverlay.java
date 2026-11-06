package com.diting.app;

import android.app.Activity;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

/**
 * 月份弹层：从主屏顶部那行日期点开，选一天看那天的「要你看」。
 *
 * 有记录的日子在数字下面点一个小珊瑚点；今天是珊瑚色数字；
 * 选中的那天是一个填满的圆。翻月不离开首页，选完直接换主屏。
 */
public class CalendarOverlay {

    public interface OnPick {
        void onPick(String date);
    }

    private static final String PATTERN = "yyyy-MM-dd";

    private final Activity act;
    private final String selected;
    private final List<String> marked;
    private final OnPick pick;

    private FrameLayout layer;
    private LinearLayout grid;
    private TextView title;
    private final Calendar cursor = Calendar.getInstance();
    private final SimpleDateFormat fmt = new SimpleDateFormat(PATTERN, Locale.CHINA);

    private CalendarOverlay(Activity act, String selected, List<String> marked, OnPick pick) {
        this.act = act;
        this.selected = selected;
        this.marked = marked == null ? new ArrayList<String>() : marked;
        this.pick = pick;
    }

    public static void show(Activity act, String selected, List<String> marked, OnPick pick) {
        new CalendarOverlay(act, selected, marked, pick).open();
    }

    private void open() {
        try {
            cursor.setTime(fmt.parse(selected));
        } catch (Exception ignored) {
        }

        layer = new FrameLayout(act);
        layer.setBackgroundColor(0xB8000000);
        layer.setClickable(true);
        layer.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                close();
            }
        });

        LinearLayout card = new LinearLayout(act);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(14), dp(16), dp(12));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Theme.CARD);
        bg.setCornerRadius(dp(22));
        bg.setStroke(dp(1), Theme.BORDER);
        card.setBackground(bg);
        card.setClickable(true);
        card.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                // 吃掉点击，别穿透到遮罩上
            }
        });

        card.addView(headerRow());

        LinearLayout week = new LinearLayout(act);
        week.setOrientation(LinearLayout.HORIZONTAL);
        String[] names = {"一", "二", "三", "四", "五", "六", "日"};
        for (String n : names) {
            TextView t = new TextView(act);
            t.setText(n);
            t.setTextColor(Theme.FAINT);
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
            t.setLetterSpacing(0.1f);
            t.setGravity(Gravity.CENTER);
            t.setTypeface(Typeface.create("serif", Typeface.NORMAL));
            week.addView(t, new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        }
        card.addView(week, wrap(dp(2), dp(6)));

        grid = new LinearLayout(act);
        grid.setOrientation(LinearLayout.VERTICAL);
        card.addView(grid);

        TextView back = new TextView(act);
        back.setText("回到今天");
        back.setTextColor(Theme.MUTED);
        back.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        back.setTypeface(Typeface.create("serif", Typeface.NORMAL));
        back.setGravity(Gravity.CENTER);
        back.setPadding(0, dp(12), 0, dp(2));
        back.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                pick.onPick(Store.today());
                close();
            }
        });
        if (!Store.today().equals(selected)) {
            card.addView(back);
        }

        FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        cp.setMargins(dp(22), dp(90), dp(22), dp(90));
        layer.addView(card, cp);

        act.addContentView(layer, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        buildMonth();
    }

    private View headerRow() {
        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        title = new TextView(act);
        title.setTextColor(Theme.INK);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        title.setTypeface(Typeface.create("serif", Typeface.NORMAL));
        row.addView(title, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        IconView prev = new IconView(act, Icons.CHEVRON_L).tint(Theme.MUTED);
        prev.setPadding(dp(8), dp(6), dp(8), dp(6));
        prev.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                cursor.set(Calendar.DAY_OF_MONTH, 1);
                cursor.add(Calendar.MONTH, -1);
                buildMonth();
            }
        });
        row.addView(prev);

        IconView next = new IconView(act, Icons.CHEVRON_R).tint(Theme.MUTED);
        next.setPadding(dp(8), dp(6), dp(8), dp(6));
        next.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                cursor.set(Calendar.DAY_OF_MONTH, 1);
                cursor.add(Calendar.MONTH, 1);
                buildMonth();
            }
        });
        row.addView(next);
        return row;
    }

    private void buildMonth() {
        if (grid == null) {
            return;
        }
        title.setText((cursor.get(Calendar.YEAR)) + " 年 " + (cursor.get(Calendar.MONTH) + 1) + " 月");
        grid.removeAllViews();

        Calendar first = (Calendar) cursor.clone();
        first.set(Calendar.DAY_OF_MONTH, 1);
        // 一周从周一开始
        int shift = (first.get(Calendar.DAY_OF_WEEK) + 5) % 7;
        Calendar day = (Calendar) first.clone();
        day.add(Calendar.DAY_OF_MONTH, -shift);

        String today = Store.today();
        String month = (cursor.get(Calendar.YEAR) + "-" + pad(cursor.get(Calendar.MONTH) + 1));

        for (int r = 0; r < 6; r++) {
            LinearLayout row = new LinearLayout(act);
            row.setOrientation(LinearLayout.HORIZONTAL);
            for (int i = 0; i < 7; i++) {
                row.addView(cell((Calendar) day.clone(), today, month),
                        new LinearLayout.LayoutParams(0, dp(36), 1f));
                day.add(Calendar.DAY_OF_MONTH, 1);
            }
            grid.addView(row);
        }
    }

    private View cell(final Calendar d, String today, String month) {
        String date = fmt.format(d.getTime());
        boolean inMonth = date.startsWith(month);
        boolean isToday = date.equals(today);
        boolean isSel = date.equals(selected);
        boolean hasData = marked.contains(date);

        FrameLayout box = new FrameLayout(act);
        box.setClickable(true);

        TextView t = new TextView(act);
        t.setText(String.valueOf(d.get(Calendar.DAY_OF_MONTH)));
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        t.setTypeface(Typeface.create("serif", Typeface.NORMAL));
        t.setGravity(Gravity.CENTER);
        if (isSel) {
            t.setTextColor(Theme.BG);
            GradientDrawable fill = new GradientDrawable();
            fill.setShape(GradientDrawable.OVAL);
            fill.setColor(Theme.ACCENT);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(30), dp(30));
            lp.gravity = Gravity.CENTER;
            t.setBackground(fill);
            t.setLayoutParams(lp);
        } else if (isToday) {
            t.setTextColor(Theme.ACCENT);
        } else if (inMonth) {
            t.setTextColor(Theme.INK);
        } else {
            t.setTextColor(Theme.FAINT);
        }
        box.addView(t, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        if (hasData) {
            View dot = new View(act);
            GradientDrawable dotBg = new GradientDrawable();
            dotBg.setShape(GradientDrawable.OVAL);
            dotBg.setColor(isSel ? Theme.BG : Theme.ACCENT);
            dot.setBackground(dotBg);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(4), dp(4));
            lp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
            lp.bottomMargin = dp(3);
            box.addView(dot, lp);
        }

        box.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                pick.onPick(fmt.format(d.getTime()));
                close();
            }
        });
        return box;
    }

    private void close() {
        if (layer != null && layer.getParent() instanceof android.view.ViewGroup) {
            ((android.view.ViewGroup) layer.getParent()).removeView(layer);
        }
        layer = null;
    }

    private static String pad(int v) {
        return v < 10 ? "0" + v : String.valueOf(v);
    }

    private LinearLayout.LayoutParams wrap(int top, int bottom) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        p.setMargins(0, top, 0, bottom);
        return p;
    }

    private int dp(int v) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v, act.getResources().getDisplayMetrics()));
    }
}

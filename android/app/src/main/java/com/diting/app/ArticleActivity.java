package com.diting.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * 「那一篇」：一整天的话，被谛听讲成一个志怪故事。
 *
 * 模型是流式回来的，这里按 80ms 一个字往外放——不是在等网，是在让它慢下来。
 * 点正文可以跳过；用户往上翻的时候不再自动跟到底部。
 * 写完 parse 成「书名 + 章」，存回那一天的 day JSON，跟原话一起七天后消失。
 */
public class ArticleActivity extends Activity {

    public static final String EXTRA_DATE = "date";

    private static final int PACE_MS = 80;
    private static final int PACE_CHARS = 1;

    private static final int MODE_NONE = 0;
    private static final int MODE_STREAM = 1;
    private static final int MODE_READ = 2;

    private Store store;
    private Lm lm;
    private String date;
    private int mode = MODE_NONE;

    private String fullText = "";
    private int revealed = 0;
    private boolean done = false;
    private boolean failed = false;
    private boolean skip = false;
    private boolean follow = true;
    private boolean loading = false;
    private String doneNote = "";
    private Store.Article article;

    private Handler pacer;
    private ScrollView scroller;
    private LinearLayout column;
    private TextView status;
    private TextView articleBody;
    private TextView rewriteBtn;

    private final Runnable paceTick = new Runnable() {
        @Override
        public void run() {
            if (mode != MODE_STREAM || articleBody == null) {
                return;
            }
            if (skip) {
                revealed = fullText.length();
            }
            int total = fullText.length();
            if (revealed < total) {
                revealed = Math.min(total, revealed + PACE_CHARS);
            }
            String shown = fullText.substring(0, Math.min(revealed, total));
            articleBody.setText(revealed < total ? shown + "▍" : shown);
            if (revealed < total) {
                pacer.postDelayed(this, PACE_MS);
            } else if (done) {
                finishArticle();
                return;
            } else {
                pacer.postDelayed(this, PACE_MS);
            }
            if (follow) {
                scroller.post(followBottom);
            }
        }
    };

    private final Runnable followBottom = new Runnable() {
        @Override
        public void run() {
            if (scroller == null) {
                return;
            }
            View child = scroller.getChildAt(0);
            if (child == null) {
                return;
            }
            int y = child.getBottom() - scroller.getHeight() + dp(24);
            scroller.scrollTo(0, Math.max(0, y));
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        store = new Store(this);
        lm = new Lm();
        Theme.set(store.themeMode());
        date = getIntent().getStringExtra(EXTRA_DATE);
        if (date == null || date.isEmpty()) {
            date = Store.today();
        }
        pacer = new Handler(Looper.getMainLooper());
        buildChrome();

        Store.Article a = store.loadArticleDay(date);
        if (a.hasContent()) {
            showArticle(a);
        } else {
            generate();
        }
    }

    private void buildChrome() {
        getWindow().setStatusBarColor(Theme.BG);
        View decor = getWindow().getDecorView();
        int flags = decor.getSystemUiVisibility();
        if (Theme.mode == Theme.LIGHT) {
            flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        } else {
            flags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        }
        decor.setSystemUiVisibility(flags);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(Theme.paper());

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(12), dp(12), dp(14), dp(4));

        IconView back = new IconView(this, Icons.BACK);
        back.tint(Theme.MUTED).size(18);
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        top.addView(back, new LinearLayout.LayoutParams(dp(40), dp(40)));

        TextView label = new TextView(this);
        label.setText("谛 听 写 的");
        label.setTextSize(10);
        label.setLetterSpacing(0.3f);
        label.setTextColor(Theme.FAINT);
        label.setGravity(Gravity.CENTER);
        top.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        rewriteBtn = new TextView(this);
        rewriteBtn.setText("重 写");
        rewriteBtn.setTextSize(12);
        rewriteBtn.setLetterSpacing(0.12f);
        rewriteBtn.setTextColor(Theme.ACCENT);
        rewriteBtn.setGravity(Gravity.CENTER);
        rewriteBtn.setPadding(dp(12), dp(8), dp(4), dp(8));
        rewriteBtn.setVisibility(View.GONE);
        rewriteBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                confirmRewrite();
            }
        });
        top.addView(rewriteBtn);

        root.addView(top);

        View hairline = new View(this);
        hairline.setBackgroundColor(Theme.BORDER);
        root.addView(hairline, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));

        scroller = new ScrollView(this);
        scroller.setFillViewport(true);
        scroller.setVerticalScrollBarEnabled(false);
        scroller.setOnScrollChangeListener(new View.OnScrollChangeListener() {
            @Override
            public void onScrollChange(View v, int scrollX, int scrollY, int oldScrollX, int oldScrollY) {
                follow = atBottom();
            }
        });

        column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dp(26), dp(16), dp(26), dp(48));
        scroller.addView(column, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroller, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        status = new TextView(this);
        status.setTextSize(11);
        status.setTextColor(Theme.FAINT);
        status.setLetterSpacing(0.04f);
        status.setPadding(dp(26), dp(8), dp(26), dp(20));
        status.setText("它在写……");
        root.addView(status);

        setContentView(root);
        applySerif(column);
    }

    private void generate() {
        if (loading) {
            return;
        }
        List<Store.Event> events = store.loadDay(date);
        if (events == null || events.isEmpty()) {
            mode = MODE_READ;
            column.removeAllViews();
            articleBody = null;
            TextView t = new TextView(this);
            t.setText("没有故事");
            t.setTextSize(26);
            t.setTextColor(Theme.INK);
            t.setTypeface(Typeface.create("serif", Typeface.NORMAL));
            column.addView(t);
            TextView sub = new TextView(this);
            sub.setText("那一天没有说话。石兽听了一夜的风，没有可讲的事。");
            sub.setTextSize(14);
            sub.setTextColor(Theme.MUTED);
            sub.setLineSpacing(dp(7), 1f);
            sub.setPadding(0, dp(12), 0, 0);
            column.addView(sub);
            applySerif(column);
            status.setText("");
            return;
        }

        List<String> src = new ArrayList<String>();
        for (Store.Event e : events) {
            String line = e.mine == null ? "" : e.mine;
            if (e.denied) {
                line = line + "（这句被否认过，请换一种说法）";
            }
            src.add(line);
        }

        loading = true;
        done = false;
        failed = false;
        skip = false;
        fullText = "";
        revealed = 0;
        doneNote = "";
        article = null;
        mode = MODE_STREAM;

        column.removeAllViews();
        articleBody = new TextView(this);
        articleBody.setTextSize(16);
        articleBody.setTextColor(Theme.INK);
        articleBody.setLineSpacing(dp(8), 1f);
        articleBody.setTypeface(Typeface.create("serif", Typeface.NORMAL));
        articleBody.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                skipAll();
            }
        });
        column.addView(articleBody);
        applySerif(column);
        rewriteBtn.setVisibility(View.GONE);
        status.setText("它在写……轻点正文可以跳过");
        scroller.scrollTo(0, 0);

        lm.writeArticleStream(store.endpoint(), store.apiKey(), store.model(), src,
                new Lm.ArticleStreamCallback() {
                    @Override
                    public void onDelta(String partial) {
                        fullText = partial == null ? "" : partial;
                    }

                    @Override
                    public void onDone(String full, boolean fromModel, String note) {
                        fullText = full == null ? "" : full;
                        failed = !fromModel;
                        doneNote = note == null ? "" : note;
                        done = true;
                        loading = false;
                        pacer.removeCallbacks(paceTick);
                        pacer.post(paceTick);
                    }
                });

        pacer.removeCallbacks(paceTick);
        pacer.post(paceTick);
    }

    private void skipAll() {
        if (mode != MODE_STREAM) {
            return;
        }
        skip = true;
        revealed = fullText.length();
        if (articleBody != null) {
            articleBody.setText(fullText);
        }
    }

    private void finishArticle() {
        if (mode != MODE_STREAM) {
            return;
        }
        pacer.removeCallbacks(paceTick);
        pacer.removeCallbacks(followBottom);
        Store.Article a = Store.Article.parse(fullText);
        a.fromModel = !failed;
        a.note = failed ? cutNote(doneNote) : "";
        if (a.hasContent()) {
            article = a;
            store.saveArticleDay(date, a);
            showArticle(a);
        } else {
            status.setText("这一篇没写出来。");
        }
    }

    private void showArticle(Store.Article a) {
        mode = MODE_READ;
        done = true;
        loading = false;
        article = a;
        articleBody = null;
        column.removeAllViews();

        // 一枚小印：「谛」。
        TextView seal = new TextView(this);
        seal.setText("谛");
        seal.setTextColor(Theme.ON_ACCENT);
        seal.setTextSize(10);
        seal.setGravity(Gravity.CENTER);
        GradientDrawable sb = new GradientDrawable();
        sb.setColor(Theme.ACCENT);
        sb.setCornerRadius(dp(2));
        seal.setBackground(sb);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(dp(20), dp(20));
        slp.gravity = Gravity.CENTER_HORIZONTAL;
        column.addView(seal, slp);

        TextView title = new TextView(this);
        title.setText(a.title == null || a.title.isEmpty() ? "听了一夜" : a.title);
        title.setTextSize(28);
        title.setTextColor(Theme.INK);
        title.setTypeface(Typeface.create("serif", Typeface.NORMAL));
        title.setLineSpacing(dp(6), 1f);
        title.setGravity(Gravity.CENTER);
        column.addView(title, matchWrap(dp(14), 0));

        TextView meta = new TextView(this);
        String form = a.single() ? "短篇" : (a.chapters.size() + " 章");
        meta.setText(dateLabel() + " · " + form + " · " + a.charCount() + " 字");
        meta.setTextSize(11);
        meta.setTextColor(Theme.FAINT);
        meta.setGravity(Gravity.CENTER);
        meta.setLetterSpacing(0.04f);
        meta.setPadding(0, dp(8), 0, 0);
        column.addView(meta);

        View line = new View(this);
        line.setBackgroundColor((Theme.ACCENT & 0x00FFFFFF) | 0x66000000);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(dp(28), dp(1));
        llp.gravity = Gravity.CENTER_HORIZONTAL;
        llp.topMargin = dp(20);
        llp.bottomMargin = dp(8);
        column.addView(line, llp);

        boolean single = a.single();
        int n = 0;
        for (Store.Chapter c : a.chapters) {
            n++;
            if (!single) {
                TextView label = new TextView(this);
                label.setText("第 " + cnNum(n) + " 章");
                label.setTextSize(10);
                label.setLetterSpacing(0.26f);
                label.setTextColor(Theme.ACCENT);
                label.setGravity(Gravity.CENTER);
                label.setPadding(0, dp(26), 0, 0);
                column.addView(label);

                String name = chapterName(c.title);
                if (!name.isEmpty()) {
                    TextView h = new TextView(this);
                    h.setText(name);
                    h.setTextSize(19);
                    h.setTextColor(Theme.INK);
                    h.setTypeface(Typeface.create("serif", Typeface.NORMAL));
                    h.setGravity(Gravity.CENTER);
                    h.setLineSpacing(dp(4), 1f);
                    h.setPadding(0, dp(7), 0, 0);
                    column.addView(h);
                }
            }

            TextView body = new TextView(this);
            body.setText(indent(c.body));
            body.setTextSize(16);
            body.setTextColor(Theme.INK);
            body.setLineSpacing(dp(8), 1f);
            body.setPadding(0, single ? dp(24) : dp(14), 0, 0);
            column.addView(body);
        }

        if (!a.fromModel) {
            TextView note = new TextView(this);
            String extra = a.note == null || a.note.isEmpty() ? "" : "（" + a.note + "）";
            note.setText("模型没连上，这篇是兜底写的。" + extra);
            note.setTextSize(12);
            note.setTextColor(Theme.MUTED);
            note.setLineSpacing(dp(4), 1f);
            note.setPadding(0, dp(24), 0, 0);
            column.addView(note);
        }

        rewriteBtn.setVisibility(View.VISIBLE);
        String footForm = a.single() ? "短篇" : (a.chapters.size() + " 章");
        status.setText(footForm + " · 共 " + a.charCount() + " 字");
        applySerif(column);
        scroller.post(new Runnable() {
            @Override
            public void run() {
                scroller.scrollTo(0, 0);
            }
        });
    }

    private LinearLayout.LayoutParams matchWrap(int top, int bottom) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        p.setMargins(0, top, 0, bottom);
        return p;
    }

    private static String cnNum(int n) {
        String[] cn = {"一", "二", "三", "四", "五", "六", "七", "八", "九", "十"};
        return (n >= 1 && n <= cn.length) ? cn[n - 1] : String.valueOf(n);
    }

    /** 章名去掉开头的「第N章」，只留名字。 */
    private static String chapterName(String t) {
        if (t == null) {
            return "";
        }
        return t.replaceFirst("^第[一二三四五六七八九十百零0-9]+章[\\s　:：·-]*", "").trim();
    }

    /** 小说正文：每段缩进两格。 */
    private static String indent(String body) {
        if (body == null || body.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String line : body.split("\n", -1)) {
            String t = line.trim();
            if (t.isEmpty()) {
                sb.append("\n");
                continue;
            }
            sb.append("　　").append(t).append("\n");
        }
        return sb.toString().trim();
    }

    private void confirmRewrite() {
        new AlertDialog.Builder(this)
                .setTitle("重写这一篇？")
                .setMessage("现在这篇会被换掉，旧的不留。")
                .setPositiveButton("重写", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int w) {
                        article = null;
                        mode = MODE_NONE;
                        generate();
                    }
                })
                .setNegativeButton("算了", null)
                .show();
    }

    private boolean atBottom() {
        View child = scroller.getChildAt(0);
        if (child == null) {
            return true;
        }
        return child.getBottom() - (scroller.getHeight() + scroller.getScrollY()) <= dp(32);
    }

    private String dateLabel() {
        String[] p = date == null ? new String[0] : date.split("-");
        if (p.length == 3) {
            try {
                return Integer.parseInt(p[1]) + " 月 " + Integer.parseInt(p[2]) + " 日";
            } catch (Exception ignored) {
            }
        }
        return date == null ? "" : date;
    }

    private static String cutNote(String s) {
        if (s == null) {
            return "";
        }
        String t = s.trim();
        return t.length() <= 60 ? t : t.substring(0, 60) + "…";
    }

    private void applySerif(View v) {
        if (v == null) {
            return;
        }
        if (v instanceof TextView) {
            ((TextView) v).setTypeface(Typeface.create("serif", Typeface.NORMAL));
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                applySerif(g.getChildAt(i));
            }
        }
    }

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    @Override
    protected void onDestroy() {
        if (pacer != null) {
            pacer.removeCallbacks(paceTick);
            pacer.removeCallbacks(followBottom);
        }
        super.onDestroy();
    }
}

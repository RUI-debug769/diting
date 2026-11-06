package com.diting.app;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 谛听。
 *
 * 这一屏回答一个问题：一天几百句话，凭什么要你读？
 * 答案是：不让你读。打开先看到「今天有几件事要你看」，三张卡片，
 * 原文折叠在下面——想翻再翻。
 *
 * 三栏用的是谛听自己的判断：
 *   事实  他说过什么（底稿，不用他看）
 *   待办  他答应了什么（要做的）
 *   留意  可能有问题的地方（前后不一、答应别人的事）
 *
 * 原文一个字都不改，但它不再是主角。
 */
public class MainActivity extends Activity {

    /** 两条话之间隔了这么久，就算新的一件事。 */
    private static final long EVENT_GAP_MS = 90 * 1000L;

    private Store store;
    private Lm lm;
    private NetRx rx;
    private Cloud cloud;
    private String linkedHost;
    private int linkedPort = -1;

    private final List<Store.Event> events = new ArrayList<Store.Event>();
    private Store.Summary summary = new Store.Summary();
    private long lastSpokenAt = 0;

    private LinearLayout column;
    private TextView status;
    private TextView writeBtn;
    private PetView pet;
    private int petLines;
    private ScrollView scroller;

    // 流式播放：模型给得比人读得快，所以先收下、再按固定节奏一个字一个字放。
    private final Handler pacer = new Handler(Looper.getMainLooper());
    // 播放节奏：这三个数是「舒服的度」的唯一出处。
    private static final int PACE_MS = 30;      // 每 30ms 放一次
    private static final int PACE_CHARS = 1;    // 一次放一个字
    private static final int CATCHUP_1 = 90;    // 落后 90 字以上，一次放 3 个
    private static final int CATCHUP_2 = 260;   // 落后 260 字以上，一次放 9 个

    private Store.Summary fullStream;   // 目前收到的全部内容
    private int revealed;               // 已经放出来的字数
    private boolean streamDone;         // 模型那边已经结束
    private boolean streamFailed;       // 失败（没连上/没 key）
    private String streamNote = "";
    private int frozenNeed;             // 写作期间标题上的件数先冻住，免得中途跳成 0
    private boolean frozenEmpty;
    private boolean transcriptOpen = false;
    /** 现在看的是哪一天（yyyy-MM-dd）。默认今天，从日历里可以翻回去。 */
    private String shownDate = Store.today();
    private int appliedTheme = Theme.mode;
    private boolean writing = false;

    private final Cloud.Callback cloudCallback = new Cloud.Callback() {
        @Override public void onLink(boolean connected) {
            say(connected ? "云端接上了" : "云端断开，3 秒后重连");
            if (pet != null) {
                pet.setOnline(connected);
            }
        }

        @Override public void onUtterance(String text, boolean accepted, String speaker, double score) {
            String pct = String.valueOf(Math.round(score * 100) / 100.0);
            if (accepted) {
                addSpoken(text);
                return;
            }
            // 听见了，但不是主人的声音：小兽摇头，账本不动。
            if (pet != null) {
                pet.flash(PetView.REJECT, 1300);
            }
            if ("other".equals(speaker)) {
                say("刚才那句不是你的声音（" + pct + "），没记。");
            } else {
                say("云听见了，但还没认出声纹（" + pct + "），没记。");
            }
        }

        @Override public void onBoard(boolean online) {
            say(online ? "挂坠在线，正在听" : "挂坠没连上——先看它有没有连上 WiFi");
            // 挂坠不在线时它也耷拉着：那不是装饰，是它真的没东西可听。
            if (pet != null) {
                pet.setOnline(online);
            }
        }

        @Override public void onNotice(String text) {
            if (text != null && text.length() > 0) {
                say(text);
            }
        }

        @Override public void onError(String message) {
            say("云端不通：" + message);
        }
    };

    private android.os.PowerManager.WakeLock wakelock;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        store = new Store(this);
        lm = new Lm();
        rx = new NetRx();

        // 挂坠连着的时候手机屏幕多半是黑的。不拿这个锁，系统会把网络挂起，
        // 板子那边看起来就是「连上了又被掐断」。
        android.os.PowerManager pm =
                (android.os.PowerManager) getSystemService(POWER_SERVICE);
        wakelock = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "diting:rx");
        wakelock.acquire();

        Theme.set(store.themeMode());
        appliedTheme = Theme.mode;
        shownDate = Store.today();
        events.addAll(store.loadDay(shownDate));
        summary = store.loadSummaryDay(shownDate);

        buildChrome();
        render();

        rx.start(NetRx.PORT, new NetRx.Callback() {
            @Override public void onLink(boolean connected, String peer) {
                say(connected ? "板子接上了（" + peer + "）" : "板子断开了，等它重连");
            }

            @Override public void onLevel(int rms, long total) {
                say("板子在线 · " + (total / 1024) + " KB · 音量 " + rms);
            }

            @Override public void onError(String message) {
                say("接收端口起不来：" + message);
            }
        });

        // 板子的声音走云端：那边切片、比声纹、跑识别，这里只收「这算不算我的一句话」。
        cloud = new Cloud();
        linkedHost = store.cloudHost();
        linkedPort = store.cloudPort();
        cloud.start(linkedHost, linkedPort, cloudCallback);
    }

    @Override
    protected void onResume() {
        super.onResume();
        AutoWrite.reschedule(this);
        // 设置页可能改过地址；地址变了就换一条连接，没变就别打断已经连上的。
        if (cloud != null) {
            String h = store.cloudHost();
            int p = store.cloudPort();
            if (!h.equals(linkedHost) || p != linkedPort) {
                linkedHost = h;
                linkedPort = p;
                cloud.start(h, p, cloudCallback);
            }
        }
        if (appliedTheme != store.themeMode()) {
            Theme.set(store.themeMode());
            appliedTheme = Theme.mode;
            buildChrome();
            render();
        } else {
            render();
        }
    }

    /**
     * 全屏统一用衬线：标题、正文、按钮、状态行都吃同一套字形。
     * 只换字形不换字号，层级靠大小和颜色撑，不靠字体对比。
     */
    private void applySerif(View v) {
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

    // ────────────────────────── 骨架 ──────────────────────────

    /** 固定不动的部分：滚动容器 + 底部状态行 + 「让它写」。内容每次 render() 重建。 */
    /** 状态栏也跟着换：浅色主题配深色图标，否则顶上那条黑边很扎眼。 */
    private void paintSystemBars() {
        getWindow().setStatusBarColor(Theme.BG);
        View decor = getWindow().getDecorView();
        int flags = decor.getSystemUiVisibility();
        if (Theme.mode == Theme.LIGHT) {
            flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        } else {
            flags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        }
        decor.setSystemUiVisibility(flags);
    }

    private void buildChrome() {
        paintSystemBars();
        scroller = new ScrollView(this);
        scroller.setBackgroundColor(Theme.BG);
        scroller.setVerticalScrollBarEnabled(false);

        column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dp(20), dp(10), dp(20), dp(24));
        scroller.addView(column, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Theme.BG);

        root.addView(scroller, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        status = new TextView(this);
        status.setTextColor(Theme.FAINT);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        status.setPadding(dp(20), dp(6), dp(20), 0);
        status.setText("挂坠说的话会落到这里。");
        root.addView(status);

        pet = new PetView(this);
        pet.setOnPoke(new PetView.OnPoke() {
            @Override public void onPoke() {
                say(petLine());
            }
        });
        FrameLayout petRow = new FrameLayout(this);
        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(dp(94), dp(84));
        plp.gravity = Gravity.RIGHT;
        plp.rightMargin = dp(26);
        petRow.addView(pet, plp);
        root.addView(petRow, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(84)));

        writeBtn = new TextView(this);
        writeBtn.setText("让它写");
        writeBtn.setTextColor(Theme.BG);
        writeBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        writeBtn.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        writeBtn.setGravity(Gravity.CENTER);
        writeBtn.setPadding(0, dp(13), 0, dp(13));
        GradientDrawable pill = new GradientDrawable();
        pill.setColor(Theme.ACCENT);
        pill.setCornerRadius(dp(26));
        writeBtn.setBackground(pill);
        writeBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (!Store.today().equals(shownDate)) {
                    goToday();
                    return;
                }
                android.util.Log.i("diting-ui", "tap write");
                write();
            }
        });
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        bp.setMargins(dp(20), dp(10), dp(20), dp(18));
        root.addView(writeBtn, bp);

        setContentView(root);
        applySerif(status);
        applySerif(writeBtn);
    }

    // ────────────────────────── 渲染 ──────────────────────────

    private void render() {
        if (column == null) {
            return;
        }
        column.removeAllViews();

        boolean today = Store.today().equals(shownDate);
        if (writeBtn != null && !writing) {
            writeBtn.setText(today ? "让它写" : "回到今天");
        }

        column.addView(headerRow());

        // 一边写一边改标题会让人以为「刚刚那 5 件不算了」，所以在写的时候把件数冻住。
        boolean empty = writing ? frozenEmpty : (summary == null || summary.empty());
        int need = writing ? frozenNeed : (summary == null ? 0 : summary.needCount());

        TextView headline = new TextView(this);
        headline.setTextColor(Theme.INK);
        headline.setTextSize(TypedValue.COMPLEX_UNIT_SP, 27);
        headline.setTypeface(Typeface.create("serif", Typeface.NORMAL));
        headline.setLineSpacing(dp(4), 1f);
        headline.setLetterSpacing(-0.02f);
        if (events.isEmpty() && empty) {
            headline.setText(today ? "还没听见你说话" : "那天没有记录");
        } else if (need > 0) {
            headline.setText((today ? "今天有 " : "那天有 ") + need + " 件事要你看");
        } else if (!empty) {
            headline.setText(today ? "今天没什么要你看的" : "那天没什么要你看的");
        } else {
            headline.setText(today ? "还没整理今天的话" : "那天还没整理");
        }
        column.addView(headline, matchWrap(dp(10), 0));

        TextView sub = new TextView(this);
        sub.setTextColor(Theme.MUTED);
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        sub.setLineSpacing(dp(3), 1f);
        if (writing) {
            sub.setText("正在把这一天压成三行。");
        } else if (need > 0) {
            sub.setText("其余的都收在原文里，不用现在翻。");
        } else if (!empty) {
            sub.setText("事实留在下面，想起来再看。");
        } else if (events.isEmpty()) {
            sub.setText(today ? "把它挂在身上，说一句试试。" : "那天它没听见什么。");
        } else {
            sub.setText(today ? "点下面的「让它写」，我把一天压成三行。"
                    : "这一天的原话还在，只是不再重写了。");
        }
        column.addView(sub, matchWrap(0, dp(18)));

        List<String> todos = withCursor(summary == null ? null : summary.todos, 1);

        // 「需你留意」里如果只是把某条待办换个说法再说一遍，就不算新的一件。
        List<String> watch = new ArrayList<String>();
        for (String w : withCursor(summary == null ? null : summary.watch, 2)) {
            boolean dup = false;
            for (String t : todos) {
                if (sameThing(w, t)) {
                    dup = true;
                    break;
                }
            }
            if (!dup) {
                watch.add(w);
            }
        }

        if (!watch.isEmpty()) {
            column.addView(card("需 你 留 意", watch, true), matchWrap(0, dp(12)));
        }
        if (!todos.isEmpty()) {
            column.addView(card("待 办", todos, false), matchWrap(0, dp(12)));
        }
        // 模型偶尔会把「要去做的事」也写成事实。同一句话在待办里出现过了，
        // 就别再在事实里说一遍——三栏的价值是分工，不是复读。
        List<String> facts = new ArrayList<String>();
        if (summary != null) {
            for (String f : withCursor(summary.facts, 0)) {
                if (!dupOf(f, todos) && !dupOf(f, watch)) {
                    facts.add(f);
                }
            }
        }
        if (!facts.isEmpty()) {
            column.addView(card("事 实", facts, false), matchWrap(0, dp(12)));
        }

        column.addView(transcriptHeader(), matchWrap(dp(22), dp(6)));

        if (transcriptOpen) {
            if (events.isEmpty()) {
                TextView none = new TextView(this);
                none.setText("还没有记录。");
                none.setTextColor(Theme.FAINT);
                none.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
                column.addView(none, matchWrap(dp(8), 0));
            }
            for (Store.Event e : events) {
                column.addView(line(e.mine), matchWrap(dp(10), 0));
            }
        }

        TextView foot = new TextView(this);
        foot.setText(Store.today().equals(shownDate)
                ? "原文只留最近 7 天，之后自动清掉。"
                : "这是往回翻的记录。原文只留最近 7 天。");
        foot.setTextColor(Theme.FAINT);
        foot.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        column.addView(foot, matchWrap(dp(26), 0));

        applySerif(column);

        scroller.post(new Runnable() {
            @Override public void run() {
                if (scroller != null) {
                    scroller.scrollTo(0, 0);
                }
            }
        });
    }

    private View headerRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(12), 0, dp(10));

        java.util.Date d;
        try {
            d = new SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).parse(shownDate);
        } catch (Exception e) {
            d = new Date();
        }
        boolean isToday = Store.today().equals(shownDate);

        LinearLayout dateBox = new LinearLayout(this);
        dateBox.setOrientation(LinearLayout.HORIZONTAL);
        dateBox.setGravity(Gravity.CENTER_VERTICAL);
        dateBox.setPadding(0, dp(4), 0, dp(4));
        dateBox.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                openCalendar();
            }
        });

        IconView cal = new IconView(this, Icons.CALENDAR).tint(Theme.MUTED).size(15);
        dateBox.addView(cal);

        TextView date = new TextView(this);
        date.setText(new SimpleDateFormat("M 月 d 日", Locale.CHINA).format(d));
        date.setTextColor(isToday ? Theme.MUTED : Theme.ACCENT);
        date.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        date.setLetterSpacing(0.08f);
        date.setPadding(dp(7), 0, 0, 0);
        dateBox.addView(date);
        row.addView(dateBox, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        IconView themeToggle = new IconView(this, Icons.CONTRAST)
                .tint(Theme.MUTED).size(18);
        themeToggle.setPadding(dp(10), dp(4), dp(8), dp(4));
        themeToggle.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                int next = Theme.toggled();
                Theme.set(next);
                store.setThemeMode(next);
                appliedTheme = next;
                buildChrome();
                render();
            }
        });
        row.addView(themeToggle);

        IconView settings = new IconView(this, Icons.GEAR).tint(Theme.MUTED).size(18);
        settings.setPadding(dp(12), dp(4), dp(2), dp(4));
        settings.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, SettingsActivity.class));
            }
        });
        row.addView(settings);
        return row;
    }

    private View transcriptHeader() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(6), 0, dp(6));

        int n = 0;
        for (Store.Event e : events) {
            n += countSentences(e.mine);
        }

        TextView label = new TextView(this);
        boolean today = Store.today().equals(shownDate);
        String head = today ? "今日原话" : "那天的原话";
        label.setText(events.isEmpty() ? head : head + " · " + n + " 句");
        label.setTextColor(Theme.MUTED);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        label.setLetterSpacing(0.16f);
        row.addView(label, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView toggle = new TextView(this);
        toggle.setText(transcriptOpen ? "收起" : "展开");
        toggle.setTextColor(Theme.FAINT);
        toggle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        toggle.setPadding(dp(12), dp(2), 0, dp(2));
        toggle.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                android.util.Log.i("diting-ui", "tap toggle");
                transcriptOpen = !transcriptOpen;
                render();
            }
        });
        row.addView(toggle);
        return row;
    }

    /** 一张卡片：小标签 + 若干条短句。accent=true 时标签和圆点用珊瑚色。 */
    private View card(String label, List<String> items, boolean accent) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(14), dp(16), dp(16));

        GradientDrawable bgd = new GradientDrawable();
        bgd.setColor(Theme.CARD);
        bgd.setCornerRadius(dp(18));
        bgd.setStroke(dp(1), Theme.BORDER);
        box.setBackground(bgd);

        TextView title = new TextView(this);
        title.setText(label);
        title.setTextColor(accent ? Theme.ACCENT : Theme.FAINT);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        title.setLetterSpacing(0.18f);
        box.addView(title, matchWrap(0, dp(8)));

        for (String s : items) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);

            TextView dot = new TextView(this);
            dot.setText("·");
            dot.setTextColor(accent ? Theme.ACCENT : Theme.FAINT);
            dot.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            dot.setPadding(0, 0, dp(8), 0);
            row.addView(dot);

            TextView t = new TextView(this);
            t.setText(s);
            t.setTextColor(Theme.INK);
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            t.setLineSpacing(dp(5), 1f);
            row.addView(t, new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            box.addView(row, matchWrap(dp(3), 0));
        }
        return box;
    }

    /** 原文一行。它不解释、不改写，只是原样放在那里。 */
    private View line(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(Theme.INK);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        t.setLineSpacing(dp(5), 1f);
        t.setAlpha(0.92f);
        t.setPadding(0, dp(6), 0, dp(6));
        return t;
    }

    // ────────────────────────── 动作 ──────────────────────────

    /**
     * 流式写的时候，正在写的那一条后面挂一个光标；别的条目原样。
     * partialSummary() 已经把「没收尾的那一条」放进列表末尾了，这里只负责加光标。
     */
    private static boolean sameThing(String a, String b) {
        return Store.Summary.sameThing(a, b);
    }

    private static boolean dupOf(String s, List<String> list) {
        for (String t : list) {
            if (sameThing(s, t)) {
                return true;
            }
        }
        return false;
    }

    private List<String> withCursor(List<String> src, int section) {
        List<String> out = new ArrayList<String>();
        if (src == null) {
            return out;
        }
        out.addAll(src);
        if (summary != null && summary.openSection == section && !out.isEmpty()) {
            int last = out.size() - 1;
            out.set(last, out.get(last) + "▍");
        }
        return out;
    }

    /** 打开月历弹层；有记录的日子会在数字下面点一个小点。 */
    private void openCalendar() {
        CalendarOverlay.show(this, shownDate, store.recentDays(), new CalendarOverlay.OnPick() {
            @Override public void onPick(String date) {
                showDate(date);
            }
        });
    }

    private void showDate(String date) {
        boolean same = date.equals(shownDate);
        shownDate = date;
        events.clear();
        events.addAll(store.loadDay(shownDate));
        summary = store.loadSummaryDay(shownDate);
        transcriptOpen = false;
        if (!same) {
            say(Store.today().equals(date) ? "回到今天了。" : "翻到 " + date + "。");
        }
        render();
    }

    private void goToday() {
        showDate(Store.today());
    }

    private String petLine() {
        String[] lines = {
                "嗯，我在。",
                "我听着呢。",
                "你说，我记着。",
                "这句我记下了。",
        };
        String s = lines[petLines % lines.length];
        petLines++;
        return s;
    }

    private void addSpoken(String text) {
        String t = text == null ? "" : text.trim();
        if (t.isEmpty()) {
            return;
        }
        // 正在往回翻的时候来了新句子：先把画面拉回今天，新的话才算数。
        if (!Store.today().equals(shownDate)) {
            shownDate = Store.today();
            events.clear();
            events.addAll(store.loadDay(shownDate));
            summary = store.loadSummaryDay(shownDate);
        }
        long now = System.currentTimeMillis();
        if (!events.isEmpty() && now - lastSpokenAt < EVENT_GAP_MS && lastSpokenAt > 0) {
            Store.Event last = events.get(events.size() - 1);
            last.mine = last.mine.isEmpty() ? t : last.mine + " " + t;
        } else {
            Store.Event e = new Store.Event(t);
            e.its = null;
            events.add(e);
        }
        lastSpokenAt = now;
        store.save(events);
        say("记下了。");
        render();
        if (pet != null) {
            pet.flash(PetView.ACCEPT, 1200);
        }
    }

    private void write() {
        if (events.isEmpty()) {
            say("还没有话可以写。");
            return;
        }
        if (writing) {
            return;
        }

        List<String> src = new ArrayList<String>();
        for (Store.Event e : events) {
            String line = e.mine;
            if (e.denied) {
                line = line + "（这句被否认过，请换一种说法）";
            }
            src.add(line);
        }

        writing = true;
        writeBtn.setText("它在整理…");
        writeBtn.setAlpha(0.75f);
        say("它在整理…");
        if (pet != null) {
            pet.hold(PetView.THINK);
        }

        // 每次新写都从零开始放。
        revealed = 0;
        fullStream = null;
        streamDone = false;
        streamFailed = false;
        streamNote = "";
        frozenNeed = summary == null ? 0 : summary.needCount();
        frozenEmpty = summary == null || summary.empty();

        lm.summarizeStream(store.endpoint(), store.apiKey(), store.model(), src,
                new Lm.StreamCallback() {
                    @Override public void onDelta(Store.Summary partial) {
                        if (partial == null) {
                            return;
                        }
                        if (pet != null && !writing) {
                            pet.hold(PetView.TYPE);
                        }
                        if (pet != null) {
                            pet.hold(PetView.TYPE);
                        }
                        // 只收下，不直接上屏——播放交给 paceTick。
                        fullStream = partial;
                        if (!streamDone) {
                            pacer.removeCallbacks(paceTick);
                            pacer.post(paceTick);
                        }
                    }

                    @Override public void onDone(Store.Summary s, boolean fromModel, String note) {
                        streamFailed = !fromModel || s == null;
                        streamNote = note == null ? "" : note;
                        if (s != null) {
                            fullStream = s;
                        }
                        streamDone = true;
                        pacer.removeCallbacks(paceTick);
                        pacer.post(paceTick);
                    }
                });
    }

    /** 每次放一小段：字数少就一个字一个字，追得上就加快，永远比模型慢半拍。 */
    private final Runnable paceTick = new Runnable() {
        @Override public void run() {
            Store.Summary full = fullStream;
            if (full == null) {
                if (streamDone) {
                    finishStream();
                }
                return;
            }
            int total = visibleChars(full);
            if (revealed >= total) {
                if (streamDone) {
                    finishStream();
                    return;
                }
                pacer.postDelayed(this, 40);   // 等模型下一段
                return;
            }
            int left = total - revealed;
            int step = left > CATCHUP_2 ? 9 : (left > CATCHUP_1 ? 3 : PACE_CHARS);
            revealed += step;
            summary = clipTo(full, revealed);
            render();
            pacer.postDelayed(this, PACE_MS);
        }
    };

    private static int visibleChars(Store.Summary s) {
        int n = 0;
        for (String x : s.facts) n += x.length();
        for (String x : s.todos) n += x.length();
        for (String x : s.watch) n += x.length();
        return n;
    }

    /** 按「一共显示多少字」裁出一份快照，正在写的那条挂上光标。 */
    private static Store.Summary clipTo(Store.Summary full, int budget) {
        Store.Summary out = new Store.Summary();
        out.openSection = -1;
        for (int sec = 0; sec < 3; sec++) {
            List<String> src = sec == 0 ? full.facts : (sec == 1 ? full.todos : full.watch);
            List<String> dst = sec == 0 ? out.facts : (sec == 1 ? out.todos : out.watch);
            for (String item : src) {
                if (budget <= 0) {
                    return out;
                }
                if (item.length() <= budget) {
                    dst.add(item);
                    budget -= item.length();
                } else {
                    dst.add(item.substring(0, budget));
                    out.openSection = sec;
                    out.openItem = item;
                    return out;
                }
            }
        }
        return out;
    }

    private void finishStream() {
        pacer.removeCallbacks(paceTick);
        boolean ok = !streamFailed;
        writing = false;
        writeBtn.setText("让它写");
        writeBtn.setAlpha(1f);
        if (fullStream != null) {
            summary = fullStream;
            if (ok) {
                store.saveSummary(fullStream);
            }
        }
        render();
        say(ok ? "整理好了。" : "模型没连上，" + streamNote);
        if (pet != null) {
            pet.clearHeld();
            pet.flash(ok ? PetView.DONE : PetView.ERROR, 1600);
        }
        fullStream = null;
        revealed = 0;
        streamDone = false;
    }

    private void say(String s) {
        if (status != null) {
            status.setText(s);
        }
    }

    // ────────────────────────── 小工具 ──────────────────────────

    private LinearLayout.LayoutParams matchWrap(int top, int bottom) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        p.setMargins(0, top, 0, bottom);
        return p;
    }

    /**
     * 数一段原话里有几「句」：句末标点切段，不带标点的一整段也算一句。
     * 英文句点故意不算句末，免得把 3.14、a.m. 切碎。
     */
    private static int countSentences(String text) {
        if (text == null || text.trim().isEmpty()) {
            return 0;
        }
        int n = 0;
        for (String part : text.split("[。？！?!…]+")) {
            if (!part.trim().isEmpty()) {
                n++;
            }
        }
        return n;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (cloud != null) {
            cloud.stop();
        }
        if (rx != null) {
            rx.stop();
        }
        if (wakelock != null && wakelock.isHeld()) {
            wakelock.release();
        }
    }

    private int dp(int v) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }
}

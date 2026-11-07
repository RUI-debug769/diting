package com.diting.app;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
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
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

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

    /** 顶部两个页签：「记」只管原话，「写」管三栏和文章。 */
    private static final int TAB_REC = 0;
    private static final int TAB_WRITE = 1;
    private int tab = TAB_REC;

    /** 「写」页的七日文章列表：进页面时读一次，回前台再刷新。 */
    private final List<String> articleDates = new ArrayList<String>();
    private final List<Store.Article> articleCache = new ArrayList<Store.Article>();
    private final List<Boolean> articleHasEvents = new ArrayList<Boolean>();
    private boolean articlesDirty = true;
    private Voice voice;
    /** 人在不在这一屏前面。退到后台就安静，不往口袋里扔声音。 */
    private boolean resumed;
    /** 谛听在的时候由它出声，手机不跟着响——一份声音只从一个身体出来。 */
    private boolean boardOnline;
    private int petLines;
    private ScrollView scroller;

    // 流式播放：模型给得比人读得快，所以先收下、再按固定节奏一个字一个字放。
    private final Handler pacer = new Handler(Looper.getMainLooper());
    // 播放节奏：这三个数是「舒服的度」的唯一出处。
    // 2026-11 调慢：一天的话值得慢慢听，80ms 一个字，追帧的阈值也放宽，
    // 正常长度（几百字）的三栏不会再触发加速。
    private static final int PACE_MS = 80;      // 每 80ms 放一次
    private static final int PACE_CHARS = 1;    // 一次放一个字
    private static final int CATCHUP_1 = 600;   // 落后 600 字以上，一次放 3 个
    private static final int CATCHUP_2 = 1500;  // 落后 1500 字以上，一次放 9 个

    private Store.Summary fullStream;   // 目前收到的全部内容
    private int revealed;               // 已经放出来的字数
    private boolean streamDone;         // 模型那边已经结束
    private boolean streamFailed;       // 失败（没连上/没 key）
    private String streamNote = "";
    private int frozenNeed;             // 写作期间标题上的件数先冻住，免得中途跳成 0
    private boolean frozenEmpty;
    /** 现在看的是哪一天（yyyy-MM-dd）。默认今天，从日历里可以翻回去。 */
    private String shownDate = Store.today();
    private int appliedTheme = Theme.mode;
    private boolean writing = false;
    private TextView bubble;            // 写完那句安慰话，从宠物旁边冒出来

    /** 写完时说的话：都是短的、不催人的。挑一句随机。 */
    private static final String[] COMFORT = {
            "辛苦了。",
            "记好了，歇会儿。",
            "今天也写完了。",
            "不急着看，先喝口水。",
            "都在这儿了，慢慢翻。",
            "写完这一版，就放下吧。",
            "这些事，明天再说也不迟。",
            "你歇着，我守着。",
            "累了就少记两句。",
            "有我在，不用都记住。",
    };

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
            speak(Voice.HUSH);
            if ("other".equals(speaker)) {
                say("刚才那句不是你的声音（" + pct + "），没记。");
            } else {
                say("云听见了，但还没认出声纹（" + pct + "），没记。");
            }
        }

        @Override public void onBoard(boolean online) {
            boardOnline = online;
            say(online ? "谛听在线，正在听" : "谛听没连上——先看它有没有连上 WiFi");
            // 谛听不在线时它也耷拉着：那不是装饰，是它真的没东西可听。
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
        voice = Voice.get(this);
        voice.setEnabled(store.sound());

        // 谛听连着的时候手机屏幕多半是黑的。不拿这个锁，系统会把网络挂起，
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
        resumed = true;
        // 从阅读页回来时文章可能刚写好，列表缓存要重读。
        articlesDirty = true;
        if (voice != null) {
            voice.setEnabled(store.sound());
        }
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
        scroller.setBackgroundColor(0x00000000);
        scroller.setVerticalScrollBarEnabled(false);

        column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dp(24), dp(4), dp(24), dp(28));
        scroller.addView(column, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(Theme.paper());

        // 顶部页签：两个字居中，像书页上的两个签。选中只压一道朱砂短线。
        LinearLayout tabBar = new LinearLayout(this);
        tabBar.setOrientation(LinearLayout.HORIZONTAL);
        tabBar.setGravity(Gravity.CENTER);
        tabBar.setPadding(dp(24), dp(10), dp(24), 0);
        tabBar.addView(tabItem("记", TAB_REC),
                new LinearLayout.LayoutParams(dp(72), dp(44)));
        View tabDivider = new View(this);
        tabDivider.setBackgroundColor(Theme.BORDER);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(dp(1), dp(12));
        dlp.leftMargin = dp(14);
        dlp.rightMargin = dp(14);
        tabBar.addView(tabDivider, dlp);
        tabBar.addView(tabItem("写", TAB_WRITE),
                new LinearLayout.LayoutParams(dp(72), dp(44)));
        root.addView(tabBar);

        View hairline = new View(this);
        hairline.setBackgroundColor(Theme.BORDER);
        root.addView(hairline, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1)));

        root.addView(scroller, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        status = new TextView(this);
        status.setTextColor(Theme.FAINT);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        status.setLetterSpacing(0.02f);
        status.setPadding(dp(24), dp(8), dp(24), 0);
        status.setText("谛听听见的话会落到这里。");
        root.addView(status);

        pet = new PetView(this);
        pet.setOnPoke(new PetView.OnPoke() {
            @Override public void onPoke() {
                speak(Voice.POKE);
                say(petLine());
            }
        });
        FrameLayout petRow = new FrameLayout(this);
        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(dp(78), dp(70));
        plp.gravity = Gravity.RIGHT;
        plp.rightMargin = dp(24);
        petRow.addView(pet, plp);

        bubble = new TextView(this);
        bubble.setTextColor(Theme.INK);
        bubble.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        bubble.setLineSpacing(dp(2), 1f);
        bubble.setPadding(dp(12), dp(8), dp(12), dp(8));
        bubble.setMaxWidth(dp(210));
        GradientDrawable bubbleBg = new GradientDrawable();
        bubbleBg.setCornerRadius(dp(3));
        bubbleBg.setColor(Theme.CARD);
        bubbleBg.setStroke(dp(1), Theme.BORDER);
        bubble.setBackground(bubbleBg);
        bubble.setVisibility(View.GONE);
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        blp.gravity = Gravity.START | Gravity.CENTER_VERTICAL;
        blp.leftMargin = dp(24);
        blp.rightMargin = dp(122);
        petRow.addView(bubble, blp);
        root.addView(petRow, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(70)));

        writeBtn = new TextView(this);
        writeBtn.setText("让它写");
        writeBtn.setTextColor(Theme.ON_ACCENT);
        writeBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        writeBtn.setLetterSpacing(0.18f);
        writeBtn.setGravity(Gravity.CENTER);
        GradientDrawable bar = new GradientDrawable();
        bar.setColor(Theme.ACCENT);
        bar.setCornerRadius(dp(3));
        writeBtn.setBackground(bar);
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
                LinearLayout.LayoutParams.MATCH_PARENT, dp(52));
        bp.setMargins(dp(24), dp(12), dp(24), dp(10));
        root.addView(writeBtn, bp);
        // 切页签会把按钮重建一遍；正在写的时候别让它看起来又能点。
        if (writing) {
            writeBtn.setText("它在整理…");
            writeBtn.setAlpha(0.75f);
        }
        // 「让它写」只属于「写」页；在「记」页不出现。
        writeBtn.setVisibility(tab == TAB_WRITE ? View.VISIBLE : View.GONE);

        setContentView(root);
        applySerif(status);
        applySerif(writeBtn);
    }

    /** 顶部页签：两个字居中，选中时底下压一道朱砂。 */
    private View tabItem(String label, final int which) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        t.setLetterSpacing(0.34f);
        t.setTypeface(Typeface.create("serif", Typeface.NORMAL));
        t.setTextColor(tab == which ? Theme.INK : Theme.MUTED);
        t.setGravity(Gravity.CENTER);
        if (tab == which) {
            t.setBackground(tabUnderline());
        }
        t.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (tab == which) {
                    return;
                }
                tab = which;
                buildChrome();
                render();
            }
        });
        return t;
    }

    /** 朱砂短线下沉在页签字下面。 */
    private Drawable tabUnderline() {
        GradientDrawable line = new GradientDrawable();
        line.setColor(Theme.ACCENT);
        line.setCornerRadius(dp(1));
        LayerDrawable layers = new LayerDrawable(new Drawable[]{line});
        layers.setLayerGravity(0, Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM);
        layers.setLayerInsetBottom(0, dp(8));
        layers.setLayerWidth(0, dp(20));
        layers.setLayerHeight(0, dp(2));
        return layers;
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

        if (tab == TAB_REC) {
            renderRec(today);
        } else {
            renderWrite(today);
        }

        applySerif(column);
        resetScroll();
    }

    private void resetScroll() {
        scroller.post(new Runnable() {
            @Override public void run() {
                if (scroller != null) {
                    scroller.scrollTo(0, 0);
                }
            }
        });
    }

    // ── 「记」：原话，一个字不改 ──

    private void renderRec(boolean today) {
        int sentences = 0;
        for (Store.Event e : events) {
            sentences += countSentences(e.mine);
        }

        TextView headline = headline();
        if (events.isEmpty()) {
            headline.setText(today ? "还没听见你说话" : "那天没有记录");
        } else {
            headline.setText((today ? "今天听见 " : "那天听见 ") + sentences + " 句");
        }
        column.addView(headline, matchWrap(dp(14), 0));

        TextView sub = new TextView(this);
        sub.setTextColor(Theme.MUTED);
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        sub.setLineSpacing(dp(4), 1f);
        sub.setText(events.isEmpty()
                ? (today ? "把它挂在身上，说一句试试。" : "那天它没听见什么。")
                : "原话一个字没改，都收在下面。");
        column.addView(sub, matchWrap(0, dp(20)));

        if (events.isEmpty()) {
            TextView none = new TextView(this);
            none.setText(today ? "说一句，它就在这儿记下。" : "那天没有留下原话。");
            none.setTextColor(Theme.FAINT);
            none.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            none.setLineSpacing(dp(4), 1f);
            column.addView(none, matchWrap(dp(6), 0));
        } else {
            // 连着说的同一句，只占一行，右边标 ×N——像账本上的「同上」。
            int index = 1;
            for (int i = 0; i < events.size(); ) {
                String text = events.get(i).mine;
                int count = 1;
                while (i + count < events.size()
                        && text != null && text.equals(events.get(i + count).mine)) {
                    count++;
                }
                column.addView(entry(index, text, count));
                if (i + count < events.size()) {
                    column.addView(rule(dp(28), dp(14), dp(14)));
                }
                i += count;
                index++;
            }
        }

        TextView foot = new TextView(this);
        foot.setText("原文只留最近 7 天，之后自动清掉。");
        foot.setTextColor(Theme.FAINT);
        foot.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        foot.setLetterSpacing(0.04f);
        column.addView(foot, matchWrap(dp(30), 0));
    }

    // ── 「写」：三栏 + 最近七天的文章 ──

    private void renderWrite(boolean today) {
        // 一边写一边改标题会让人以为「刚刚那 5 件不算了」，所以在写的时候把件数冻住。
        boolean empty = writing ? frozenEmpty : (summary == null || summary.empty());
        int need = writing ? frozenNeed : (summary == null ? 0 : summary.needCount());

        TextView headline = headline();
        if (events.isEmpty() && empty) {
            headline.setText(today ? "还没听见你说话" : "那天没有记录");
        } else if (need > 0) {
            headline.setText((today ? "今天有 " : "那天有 ") + need + " 件事要你看");
        } else if (!empty) {
            headline.setText(today ? "今天没什么要你看的" : "那天没什么要你看的");
        } else {
            headline.setText(today ? "还没整理今天的话" : "那天还没整理");
        }
        column.addView(headline, matchWrap(dp(14), 0));

        TextView sub = new TextView(this);
        sub.setTextColor(Theme.MUTED);
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        sub.setLineSpacing(dp(4), 1f);
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
        column.addView(sub, matchWrap(0, dp(22)));

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
            column.addView(section("需 你 留 意", watch, true));
        }
        if (!todos.isEmpty()) {
            column.addView(section("待 办", todos, false));
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
            column.addView(section("事 实", facts, false));
        }

        column.addView(sectionHeader("文 章 · 最 近 七 天", -1, Theme.MUTED),
                matchWrap(dp(32), dp(4)));

        String auto = store.autoAt();
        TextView artHint = new TextView(this);
        artHint.setTextColor(Theme.FAINT);
        artHint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        artHint.setLineSpacing(dp(4), 1f);
        artHint.setText(auto != null && auto.matches("\\d{1,2}:\\d{2}")
                ? "每天 " + auto + " 会自己写一篇；点一天，就写那一天。"
                : "点一天，就写那一天。");
        column.addView(artHint, matchWrap(0, dp(12)));

        refreshArticles();
        column.addView(rule(0, 0, 0));
        for (int i = 0; i < articleDates.size(); i++) {
            column.addView(articleRow(articleDates.get(i), articleCache.get(i),
                    articleHasEvents.get(i).booleanValue()));
        }

        TextView foot = new TextView(this);
        foot.setText("原话收在「记」里；这里只放它写下的东西。");
        foot.setTextColor(Theme.FAINT);
        foot.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        column.addView(foot, matchWrap(dp(28), 0));
    }

    /** 页标题：字号、衬线、行距统一。 */
    private TextView headline() {
        TextView t = new TextView(this);
        t.setTextColor(Theme.INK);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 28);
        t.setTypeface(Typeface.create("serif", Typeface.NORMAL));
        t.setLineSpacing(dp(6), 1f);
        t.setLetterSpacing(-0.01f);
        return t;
    }

    /** 「写」页的七日文章缓存：只在首次进入和回前台时读一次存档。 */
    private void refreshArticles() {
        if (!articlesDirty) {
            return;
        }
        articlesDirty = false;
        articleDates.clear();
        articleCache.clear();
        articleHasEvents.clear();
        articleDates.addAll(lastSevenDays());
        List<Store.Article> arts = store.loadArticles(articleDates);
        Set<String> have = new HashSet<String>(store.recentDays());
        for (int i = 0; i < articleDates.size(); i++) {
            articleCache.add(arts.get(i));
            articleHasEvents.add(Boolean.valueOf(have.contains(articleDates.get(i))));
        }
    }

    /** 一天一行：左边日期，右边书名；有文章就看，有原话没文章就点开写。 */
    private View articleRow(final String date, Store.Article a, boolean hasEvents) {
        boolean has = a != null && a.hasContent();

        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(14), 0, dp(14));

        TextView day = new TextView(this);
        day.setText(dayShort(date));
        day.setTextColor(has ? Theme.MUTED : Theme.FAINT);
        day.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        day.setLetterSpacing(0.04f);
        day.setWidth(dp(52));
        day.setPadding(0, dp(2), dp(8), 0);
        row.addView(day);

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);

        TextView title = new TextView(this);
        title.setText(has ? a.title : (hasEvents ? "还没写" : "那天没有记录"));
        title.setTextColor(has ? Theme.INK : (hasEvents ? Theme.MUTED : Theme.FAINT));
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        title.setLineSpacing(dp(3), 1f);
        texts.addView(title);

        TextView meta = new TextView(this);
        meta.setTextColor(Theme.FAINT);
        meta.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        meta.setPadding(0, dp(3), 0, 0);
        if (has) {
            String form = a.single() ? "短篇" : (a.chapters.size() + " 章");
            meta.setText(form + " · " + a.charCount() + " 字"
                    + (a.fromModel ? "" : " · 兜底"));
        } else if (hasEvents) {
            meta.setText("点一下写这一天");
        } else {
            meta.setText("没有原话");
        }
        texts.addView(meta);

        row.addView(texts, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        if (has || hasEvents) {
            IconView chev = new IconView(this, Icons.CHEVRON_R).tint(Theme.FAINT).size(12);
            row.addView(chev, new LinearLayout.LayoutParams(dp(20), dp(20)));
            row.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    openArticle(date);
                }
            });
        } else {
            row.setAlpha(0.55f);
        }

        wrap.addView(row);
        wrap.addView(rule(0, 0, 0));
        return wrap;
    }

    /** 今天、昨天，或者「11 月 5 日」。 */
    private String dayLabel(String date) {
        if (date.equals(Store.today())) {
            return "今天";
        }
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.add(java.util.Calendar.DAY_OF_YEAR, -1);
        if (date.equals(new SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(c.getTime()))) {
            return "昨天";
        }
        try {
            Date d = new SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).parse(date);
            return new SimpleDateFormat("M 月 d 日", Locale.CHINA).format(d);
        } catch (Exception e) {
            return date;
        }
    }

    private List<String> lastSevenDays() {
        List<String> out = new ArrayList<String>();
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.CHINA);
        java.util.Calendar c = java.util.Calendar.getInstance();
        for (int i = 0; i < 7; i++) {
            out.add(f.format(c.getTime()));
            c.add(java.util.Calendar.DAY_OF_YEAR, -1);
        }
        return out;
    }

    private View headerRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(18), 0, dp(8));

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
        date.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        date.setLetterSpacing(0.06f);
        date.setPadding(dp(8), 0, 0, 0);
        dateBox.addView(date);
        row.addView(dateBox, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        IconView themeToggle = new IconView(this, Icons.CONTRAST)
                .tint(Theme.MUTED).size(17);
        themeToggle.setPadding(dp(12), dp(6), dp(10), dp(6));
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

        IconView settings = new IconView(this, Icons.GEAR).tint(Theme.MUTED).size(17);
        settings.setPadding(dp(12), dp(6), dp(2), dp(6));
        settings.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, SettingsActivity.class));
            }
        });
        row.addView(settings);
        return row;
    }

    /** 一栏：一道短朱砂、一个疏排小字，下面是条目。不用卡片，靠留白分隔。 */
    private View section(String label, List<String> items, boolean accent) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, dp(28), 0, 0);

        box.addView(sectionHeader(label, items.size(),
                accent ? Theme.ACCENT : Theme.MUTED));

        for (String s : items) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(0, dp(7), 0, dp(7));

            TextView dot = new TextView(this);
            dot.setText("·");
            dot.setTextColor(accent ? Theme.ACCENT : Theme.FAINT);
            dot.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            dot.setPadding(0, 0, dp(10), 0);
            row.addView(dot);

            TextView t = new TextView(this);
            t.setText(s);
            t.setTextColor(Theme.INK);
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            t.setLineSpacing(dp(6), 1f);
            row.addView(t, new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            box.addView(row);
        }
        return box;
    }

    /** 一栏的标题：短朱砂 + 疏排小字 + 件数。 */
    private View sectionHeader(String label, int count, int color) {
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);

        View mark = new View(this);
        GradientDrawable mg = new GradientDrawable();
        mg.setColor(Theme.ACCENT);
        mg.setCornerRadius(dp(1));
        mark.setBackground(mg);
        head.addView(mark, new LinearLayout.LayoutParams(dp(14), dp(2)));

        TextView t = new TextView(this);
        t.setText("  " + label);
        t.setTextColor(color);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        t.setLetterSpacing(0.2f);
        head.addView(t, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        if (count >= 0) {
            TextView c = new TextView(this);
            c.setText(String.valueOf(count));
            c.setTextColor(Theme.FAINT);
            c.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
            head.addView(c);
        }
        return head;
    }

    /** 原话一条：左边编号，右边原字；连着说 N 遍的，只占一行。 */
    private View entry(int index, String text, int count) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(12), 0, dp(12));

        TextView num = new TextView(this);
        num.setText(String.format(Locale.CHINA, "%02d", index));
        num.setTextColor(Theme.FAINT);
        num.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        num.setLetterSpacing(0.06f);
        num.setPadding(0, dp(3), dp(14), 0);
        row.addView(num);

        TextView t = new TextView(this);
        t.setText(text == null ? "" : text);
        t.setTextColor(Theme.INK);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        t.setLineSpacing(dp(6), 1f);
        t.setAlpha(0.95f);
        row.addView(t, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        if (count > 1) {
            TextView badge = new TextView(this);
            badge.setText("×" + count);
            badge.setTextColor(Theme.FAINT);
            badge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
            badge.setPadding(dp(8), dp(3), 0, 0);
            row.addView(badge);
        }
        return row;
    }

    /** 一根淡淡的线。left=左边缩进，top/bottom=上下留白。 */
    private View rule(int left, int top, int bottom) {
        View v = new View(this);
        v.setBackgroundColor(Theme.BORDER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1));
        lp.leftMargin = left;
        lp.topMargin = top;
        lp.bottomMargin = bottom;
        v.setLayoutParams(lp);
        return v;
    }

    /** 文章列表左边的「今天 / 昨天 / 11.5」。 */
    private String dayShort(String date) {
        if (date.equals(Store.today())) {
            return "今天";
        }
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.add(java.util.Calendar.DAY_OF_YEAR, -1);
        if (date.equals(new SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(c.getTime()))) {
            return "昨天";
        }
        String[] p = date.split("-");
        if (p.length == 3) {
            try {
                return Integer.parseInt(p[1]) + "." + Integer.parseInt(p[2]);
            } catch (Exception ignored) {
            }
        }
        return date;
    }

    // ────────────────────────── 动作 ──────────────────────────

    /**
     * 流式写的时候，正在写的那一条后面挂一个光标；别的条目原样。
     * partialSummary() 已经把「没收尾的那一条」放进列表末尾了，这里只负责加光标。
     */
    private static boolean sameThing(String a, String b) {
        return Store.Summary.strictSame(a, b);
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
        if (!same) {
            say(Store.today().equals(date) ? "回到今天了。" : "翻到 " + date + "。");
        }
        render();
    }

    private void goToday() {
        showDate(Store.today());
    }

    /** 打开某一天的「那一篇」：有就看，没有就当场写。 */
    private void openArticle(String date) {
        Intent it = new Intent(this, ArticleActivity.class);
        it.putExtra(ArticleActivity.EXTRA_DATE, date);
        startActivity(it);
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
        speak(Voice.TICK);
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

    /** 写完一句话：淡进来，停三秒，再淡出去。 */
    private void sayBubble(String text) {
        if (bubble == null) {
            return;
        }
        bubble.setText(text);
        bubble.setVisibility(View.VISIBLE);
        bubble.setAlpha(0f);
        bubble.animate().alpha(1f).setDuration(180).start();
        bubble.removeCallbacks(hideBubble);
        bubble.postDelayed(hideBubble, 3000);
    }

    private final Runnable hideBubble = new Runnable() {
        @Override public void run() {
            if (bubble == null) {
                return;
            }
            bubble.animate().alpha(0f).setDuration(420).withEndAction(new Runnable() {
                @Override public void run() {
                    if (bubble != null) {
                        bubble.setVisibility(View.GONE);
                    }
                }
            }).start();
        }
    };

    private String comfortLine() {
        int i = (int) (Math.random() * COMFORT.length);
        return COMFORT[Math.min(COMFORT.length - 1, Math.max(0, i))];
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
                // 新一版不等于全部：旧版里这次没提到的条目继续留着，只在真的被
                // 新说法替代时才消失。
                Store.Summary merged = Store.Summary.merge(
                        store.loadSummaryDay(Store.today()), fullStream);
                summary = merged;
                store.saveSummary(merged);
            }
        }
        render();
        say(ok ? "整理好了。" : "模型没连上，" + streamNote);
        sayBubble(ok ? comfortLine() : "这次没写成，等下再试。");
        if (pet != null) {
            pet.clearHeld();
            pet.flash(ok ? PetView.DONE : PetView.ERROR, 1600);
        }
        // 写成了才落地一声；没写成，沉默比提示音诚实。
        if (ok) {
            speak(Voice.SETTLE);
        }
        fullStream = null;
        revealed = 0;
        streamDone = false;
    }

    /** 只在人在场时出声。设置里关掉声音，这里也不碰 AudioTrack。
     *  「记下」和「不是你」是谛听的判断，它在线就该它来说；本地只兜底。 */
    private void speak(int sound) {
        if (!resumed || voice == null) {
            return;
        }
        if (boardOnline && (sound == Voice.TICK || sound == Voice.HUSH)) {
            return;
        }
        voice.play(sound);
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
    protected void onPause() {
        super.onPause();
        resumed = false;
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

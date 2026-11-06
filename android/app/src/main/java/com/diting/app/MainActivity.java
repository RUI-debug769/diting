package com.diting.app;

import android.Manifest;
import android.app.Activity;
import android.content.DialogInterface;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 谛听。
 *
 * 这一版把整条链路接起来了：说话 → 左栏出现原话 → 它写 → 右栏出现它的话
 * → 你可以说「这不是我说的」。
 *
 * 三个决定都在这一版里生效：
 *   1. 左栏原话零改写，一个字都不动。
 *   2. 右栏是谛听自己的话，第一人称，不写「他今天……」。
 *   3. 否认是真的：被否认的那条会带「换一种说法」的记号重新问一次模型。
 */
public class MainActivity extends Activity {

    private static final int BG     = 0xFF12100E;
    private static final int INK    = 0xFFE8E2D8;
    private static final int FAINT  = 0xFF6B635A;
    private static final int DIVIDE = 0xFF2A2622;

    /** 两条话之间隔了这么久，就算新的一件事。 */
    private static final long EVENT_GAP_MS = 90 * 1000L;

    private Store store;
    private Lm lm;
    private NetRx rx;
    private Cloud cloud;
    private String linkedHost;
    private int linkedPort = -1;

    /** 云端回来的每一句都从主线程进这里，和手打「说一句」走同一条路。 */
    private final Cloud.Callback cloudCallback = new Cloud.Callback() {
        @Override public void onLink(boolean connected) {
            say(connected ? "云端接上了" : "云端断开，3 秒后重连");
        }

        @Override public void onUtterance(String text, boolean accepted, String speaker, double score) {
            String pct = String.valueOf(Math.round(score * 100) / 100.0);
            if (accepted) {
                addSpoken(text);
            } else if ("other".equals(speaker)) {
                status.setText("刚才那句不是你的声音（" + pct + "），没记。");
            } else {
                status.setText("云听见了，但还没认出声纹（" + pct + "），没记。");
            }
        }

        @Override public void onBoard(boolean online) {
            say(online ? "板子在线，正在推声音" : "板子没连上云端——先看它有没有连上 WiFi");
        }

        @Override public void onNotice(String text) {
            if (text != null && text.length() > 0) {
                status.setText(text);
            }
        }

        @Override public void onError(String message) {
            say("云端不通：" + message);
        }
    };
    private android.os.PowerManager.WakeLock wakelock;

    private final List<Store.Event> events = new ArrayList<Store.Event>();
    private long lastSpokenAt = 0;

    private DayView day;
    private android.widget.ScrollView scroller;
    private TextView counted;
    private TextView status;

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

        events.addAll(store.load());

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);

        root.addView(head(), new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        root.addView(nameRow(), new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(22)));

        day = new DayView(this);
        // 时间线放进 ScrollView：行数一多，固定高度的画布会把新行裁在屏幕外，
        // 用户看到的就是「说了话但 App 没记录」。
        scroller = new android.widget.ScrollView(this);
        scroller.setVerticalScrollBarEnabled(false);
        scroller.setFillViewport(true);
        scroller.addView(day, new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT));
        root.addView(scroller, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        status = new TextView(this);
        status.setTextColor(FAINT);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        status.setPadding(dp(16), dp(6), dp(16), dp(2));
        status.setText("板子说的话会落到这里。");
        root.addView(status, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        root.addView(buttonRow(), new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        setContentView(root);
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
    }

    // ────────────────────────── 视图 ──────────────────────────

    private View head() {
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setPadding(dp(16), dp(20), dp(16), dp(4));

        TextView date = new TextView(this);
        date.setText(new SimpleDateFormat("M 月 d 日", Locale.CHINA).format(new Date()));
        date.setTextColor(INK);
        date.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        head.addView(date, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        counted = new TextView(this);
        counted.setTextColor(FAINT);
        counted.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        counted.setGravity(Gravity.END);
        head.addView(counted, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView settings = new TextView(this);
        settings.setText("设置");
        settings.setTextColor(FAINT);
        settings.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        settings.setPadding(dp(14), dp(2), 0, dp(2));
        settings.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(new android.content.Intent(MainActivity.this, SettingsActivity.class));
            }
        });
        head.addView(settings, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        return head;
    }

    private View nameRow() {
        int pad = dp(16), gap = dp(14);
        int colW = (getResources().getDisplayMetrics().widthPixels - pad * 2 - gap * 2 - dp(1)) / 2;

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(pad, dp(2), pad, dp(2));

        TextView me = new TextView(this);
        me.setText("我");
        me.setTextColor(FAINT);
        me.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        me.setLetterSpacing(0.14f);
        row.addView(me, new LinearLayout.LayoutParams(colW,
                LinearLayout.LayoutParams.MATCH_PARENT));

        row.addView(new View(this), new LinearLayout.LayoutParams(gap * 2,
                LinearLayout.LayoutParams.MATCH_PARENT));

        TextView it = new TextView(this);
        it.setText("谛听");
        it.setTextColor(FAINT);
        it.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        it.setLetterSpacing(0.14f);
        it.setGravity(Gravity.END);
        row.addView(it, new LinearLayout.LayoutParams(colW,
                LinearLayout.LayoutParams.MATCH_PARENT));
        return row;
    }

    private View buttonRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(12), dp(4), dp(12), dp(16));
        row.setLayoutParams(lp);

        row.addView(makeButton("让它写", 1f, new View.OnClickListener() {
            @Override public void onClick(View v) { write(); }
        }));
        return row;
    }

    private Button makeButton(String text, float weight, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        b.setTextColor(INK);
        b.setBackgroundColor(DIVIDE);
        b.setOnClickListener(l);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, weight);
        p.setMargins(dp(4), 0, dp(4), 0);
        b.setLayoutParams(p);
        return b;
    }

    // ────────────────────────── 动作 ──────────────────────────

    private void addSpoken(String text) {
        String t = text == null ? "" : text.trim();
        if (t.isEmpty()) {
            return;
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
    }

    private void write() {
        if (events.isEmpty()) {
            say("还没有话可以写。");
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

        say("它在写…");
        lm.rewrite(store.endpoint(), store.apiKey(), store.model(), src, new Lm.Callback() {
            @Override public void onDone(String[] lines, boolean fromModel, String note) {
                for (int i = 0; i < events.size() && i < lines.length; i++) {
                    events.get(i).its = lines[i];
                }
                store.save(events);
                render();
                say(fromModel ? "写好了。" : "模型没连上，" + note);
            }
        });
    }

    // ────────────────────────── 渲染 ──────────────────────────

    private void render() {
        String[][] rows = new String[events.size()][2];
        int denied = -1;
        int sentences = 0;

        for (int i = 0; i < events.size(); i++) {
            Store.Event e = events.get(i);
            rows[i][0] = e.mine == null ? "" : e.mine;
            rows[i][1] = e.its == null ? "" : e.its;
            if (e.denied) {
                denied = i;
            }
            sentences += countSentences(e.mine);
        }

        day.setEvents(rows, denied);
        counted.setText(events.isEmpty() ? "还没有记录" : "记下 " + sentences + " 句");

        if (scroller != null) {
            scroller.post(new Runnable() {
                @Override
                public void run() {
                    scroller.fullScroll(android.view.View.FOCUS_DOWN);
                }
            });
        }
    }

    private void say(String s) {
        status.setText(s);
    }

    /**
     * 数一段原话里有几「句」：句末标点切段，不带标点的一整段也算一句。
     *
     * 系统语音识别返回的中文基本不带句末标点，所以老口径（只数 。？！）恒为 0，
     * 顶栏会一直停在「记下 0 句」。按「切段后非空」数，一段没标点的话算 1 句。
     * 英文句点 . 故意不算句末，免得把 3.14、a.m. 切碎。
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

package com.diting.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.pm.PackageManager;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;

import java.util.Locale;

/**
 * 设置。从主界面右上角进来，是独立一页，不和日期条抢位置。
 *
 * 这里只管三件事：
 *   1. 板子几点到几点持续记录（云端按这个时段收，时段外直接丢）
 *   2. 我的声音：录一次、删掉已录的
 *   3. 云端和模型的地址（现场要能改）
 */
public class SettingsActivity extends Activity {


    /** 一块浅浅的纸：不再画边框，靠底色和留白分开。 */
    private android.graphics.drawable.GradientDrawable round(int fill, int radiusDp) {
        android.graphics.drawable.GradientDrawable g =
                new android.graphics.drawable.GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    private static final int REQ_AUDIO = 2001;

    private Store store;
    private final Control control = new Control();
    private final Enroll enroll = new Enroll();

    private TextView startView;
    private TextView endView;
    private TextView windowNow;
    private TextView voiceState;
    private TextView autoView;
    private String autoAt;
    private TextView autoHint;
    private TextView reply;

    private EditText key;
    private EditText model;
    private EditText endpoint;
    private EditText wifiSsid;
    private EditText wifiPswd;
    private EditText host;
    private EditText port;

    private String start;
    private String end;
    private boolean afterPermissionEnroll;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        store = new Store(this);
        Theme.set(store.themeMode());
        start = store.windowStart();
        end = store.windowEnd();
        autoAt = store.autoAt();

        ScrollView scroll = new ScrollView(this);
        scroll.setBackground(Theme.paper());

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(24), dp(20), dp(24), dp(32));

        getWindow().setStatusBarColor(Theme.BG);
        View decor0 = getWindow().getDecorView();
        int sf = decor0.getSystemUiVisibility();
        if (Theme.mode == Theme.LIGHT) {
            sf |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        } else {
            sf &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        }
        decor0.setSystemUiVisibility(sf);

        root.addView(titleRow());
        root.addView(group("板子什么时候记"));
        root.addView(windowRow());
        windowNow = small("时段外，板子还在推，云端直接丢掉，不存也不认。");
        root.addView(windowNow);

        root.addView(group("自动写"));
        root.addView(autoRow());
        autoHint = label("");
        root.addView(autoHint);

        root.addView(group("外观"));
        root.addView(themeRow());
        root.addView(soundRow());
        root.addView(small("石头和木头的声音。关掉就只剩灯。"));

        root.addView(group("我的声音"));
        voiceState = small("正在问云端…");
        root.addView(voiceState);
        root.addView(iconButton(Icons.MIC, "录一次（说 8 秒）", new View.OnClickListener() {
            @Override public void onClick(View v) {
                enrollVoice();
            }
        }));
        root.addView(iconButton(Icons.MIC, "用板子的麦克风", new View.OnClickListener() {
            @Override public void onClick(View v) {
                enrollFromBoard();
            }
        }));
        root.addView(iconButton(Icons.TRASH, "删掉已录的声音", new View.OnClickListener() {
            @Override public void onClick(View v) {
                confirmWipe();
            }
        }));

        root.addView(group("记录"));
        root.addView(small("删掉之后找不回来。演示前清场用。"));
        root.addView(iconButton(Icons.ERASE, "清空今天的记录", new View.OnClickListener() {
            @Override public void onClick(View v) {
                confirmClear();
            }
        }));

        root.addView(group("板子的 WiFi"));
        root.addView(label("名字"));
        wifiSsid = field(store.wifiSsid(), "比如 Murdo");
        root.addView(wifiSsid);
        root.addView(label("密码"));
        wifiPswd = field(store.wifiPswd(), "8 位以上");
        root.addView(wifiPswd);
        root.addView(iconButton(Icons.WIFI, "发给板子", new View.OnClickListener() {
            @Override public void onClick(View v) {
                sendWifi();
            }
        }));
        root.addView(iconButton(Icons.CLOUD, "让板子重新配网", new View.OnClickListener() {
            @Override public void onClick(View v) {
                reprovision();
            }
        }));
        root.addView(label("板子换了地方连不上时：点「重新配网」，等它开一个 Diting-Setup 热点（密码 diting1234），"
                + "手机连上这个热点，再回来填好名字密码点「发给板子」。"));

        root.addView(group("云端"));
        host = field(store.cloudHost(), "<你的服务器>");
        port = field(String.valueOf(store.cloudPort()), "19002");
        root.addView(label("地址"));
        root.addView(host);
        root.addView(label("端口"));
        root.addView(port);

        root.addView(group("模型"));
        key = field(store.apiKey(), "sk-...");
        model = field(store.model(), "deepseek-chat");
        endpoint = field(store.endpoint(), "https://api.deepseek.com/chat/completions");
        root.addView(label("Key"));
        root.addView(key);
        root.addView(label("模型"));
        root.addView(model);
        root.addView(label("接口（留空用 DeepSeek）"));
        root.addView(endpoint);

        reply = small(" ");
        root.addView(reply);

        root.addView(bigButton("保 存 并 返 回", new View.OnClickListener() {
            @Override public void onClick(View v) {
                save();
            }
        }));

        // 装到手机上的是不是这一版，看这一行最直接。
        TextView version = new TextView(this);
        version.setText("谛听 0.4 · 2026-11-07");
        version.setTextColor(Theme.FAINT);
        version.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        version.setGravity(Gravity.CENTER);
        version.setPadding(0, dp(20), 0, 0);
        root.addView(version);

        scroll.addView(root);
        setContentView(scroll);
        applySerif(scroll);
        refreshStatus();
    }

    // ────────────────────────── 视图 ──────────────────────────

    /** 和主屏一样：整页统一衬线，层级靠大小与颜色。 */
    private void applySerif(View v) {
        if (v instanceof TextView) {
            ((TextView) v).setTypeface(android.graphics.Typeface.create(
                    "serif", android.graphics.Typeface.NORMAL));
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                applySerif(g.getChildAt(i));
            }
        }
    }

    private View titleRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, 0, 0, dp(6));

        IconView back = new IconView(this, Icons.BACK).tint(Theme.FAINT).size(20);
        back.setPadding(dp(2), dp(6), dp(14), dp(6));
        back.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                save();
            }
        });
        row.addView(back);

        TextView title = new TextView(this);
        title.setText("设置");
        title.setTextColor(Theme.INK);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
        title.setTypeface(android.graphics.Typeface.create("serif",
                android.graphics.Typeface.NORMAL));
        row.addView(title, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        return row;
    }

    private View group(String text) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(30), 0, dp(10));

        View mark = new View(this);
        mark.setBackgroundColor(Theme.ACCENT);
        row.addView(mark, new LinearLayout.LayoutParams(dp(14), dp(1)));

        TextView v = new TextView(this);
        v.setText("  " + text);
        v.setTextColor(Theme.MUTED);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        v.setLetterSpacing(0.2f);
        row.addView(v);
        return row;
    }

    private TextView label(String text) {
        TextView v = new TextView(this);
        v.setText(text);
        v.setTextColor(Theme.MUTED);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        v.setPadding(0, dp(10), 0, dp(6));
        return v;
    }

    private TextView small(String text) {
        TextView v = new TextView(this);
        v.setText(text);
        v.setTextColor(Theme.FAINT);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        v.setLineSpacing(dp(3), 1f);
        v.setPadding(0, dp(4), 0, dp(8));
        return v;
    }

    /** 每天几点自动写；点一下改时间，想关就按对话框里的「关掉」。 */
    private View autoRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        TextView who = new TextView(this);
        who.setText("每天");
        who.setTextColor(Theme.FAINT);
        who.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        row.addView(who);

        autoView = new TextView(this);
        autoView.setText(isTime(autoAt) ? autoAt : "关");
        autoView.setTextColor(Theme.INK);
        autoView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 28);
        autoView.setGravity(Gravity.CENTER);
        autoView.setPadding(dp(12), dp(16), dp(12), dp(16));
        autoView.setBackground(round(Theme.WASH, 3));
        autoView.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                pickAuto();
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        lp.leftMargin = dp(10);
        lp.rightMargin = dp(10);
        row.addView(autoView, lp);

        TextView tail = new TextView(this);
        tail.setText("自动写");
        tail.setTextColor(Theme.FAINT);
        tail.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        row.addView(tail);
        return row;
    }

    private void confirmClear() {
        new AlertDialog.Builder(this)
                .setTitle("清空今天？")
                .setMessage("今天的原话、三栏判断和那一篇文章都会删掉，删了找不回来。")
                .setNegativeButton("算了", null)
                .setPositiveButton("清空", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        store.clearDay(Store.today());
                        say("今天的记录清空了。");
                    }
                })
                .show();
    }

    private static boolean isTime(String s) {
        return s != null && s.matches("\\d{1,2}:\\d{2}");
    }

    private void pickAuto() {
        final EditText input = new EditText(this);
        input.setText(isTime(autoAt) ? autoAt : "23:00");
        input.setSingleLine(true);
        input.setTextColor(Theme.INK);
        input.setHintTextColor(Theme.FAINT);
        input.setBackgroundColor(Theme.WASH);
        input.setPadding(dp(12), dp(12), dp(12), dp(12));

        new AlertDialog.Builder(this)
                .setTitle("几点自动写")
                .setView(input)
                .setPositiveButton("好", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        String v = normalise(input.getText().toString());
                        if (v == null) {
                            say("要写成 23:00 这样。");
                            return;
                        }
                        autoAt = v;
                        store.setAutoAt(v);
                        AutoWrite.reschedule(SettingsActivity.this);
                        refreshAuto();
                        say("每天 " + v + " 自动写三栏和那一篇小说。");
                    }
                })
                .setNeutralButton("关掉", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        autoAt = "";
                        store.setAutoAt("");
                        AutoWrite.reschedule(SettingsActivity.this);
                        refreshAuto();
                        say("自动写关掉了。");
                    }
                })
                .setNegativeButton("算了", null)
                .show();
    }

    private void refreshAuto() {
        if (autoView == null) {
            return;
        }
        boolean on = isTime(autoAt);
        autoView.setText(on ? autoAt : "关");
        if (autoHint != null) {
            autoHint.setText(on
                    ? "到点会自己写好三栏和那一篇，写完发条通知；手机锁着也会写。"
                    : "点上面那个「关」，定一个时间就会每天自动写三栏和那一篇。");
        }
    }

    private View windowRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        startView = timeCell(start);
        endView = timeCell(end);

        row.addView(startView, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView dash = new TextView(this);
        dash.setText("到");
        dash.setTextColor(Theme.FAINT);
        dash.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        dash.setPadding(dp(10), 0, dp(10), 0);
        row.addView(dash);

        row.addView(endView, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        return row;
    }

    private TextView timeCell(String value) {
        TextView v = new TextView(this);
        v.setText(value);
        v.setTextColor(Theme.INK);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        v.setTypeface(android.graphics.Typeface.create("serif",
                android.graphics.Typeface.NORMAL));
        v.setGravity(Gravity.CENTER);
        v.setBackground(round(Theme.WASH, 3));
        v.setPadding(0, dp(18), 0, dp(18));
        v.setTag(value);
        v.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                pickTime((TextView) view);
            }
        });
        return v;
    }

    private EditText field(String value, String hint) {
        EditText e = new EditText(this);
        e.setText(value);
        e.setHint(hint);
        e.setTextColor(Theme.INK);
        e.setHintTextColor(Theme.FAINT);
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        e.setSingleLine(true);
        e.setBackground(round(Theme.WASH, 3));
        e.setPadding(dp(13), dp(12), dp(13), dp(12));
        return e;
    }

    /** 一行：左边图标，右边短标签，末尾一道细线。设置页用行代替按钮。 */
    private View iconButton(int icon, String text, final View.OnClickListener l) {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(14), 0, dp(14));
        row.addView(new IconView(this, icon).tint(Theme.ACCENT).size(17));

        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(Theme.INK);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        t.setPadding(dp(12), 0, dp(8), 0);
        row.addView(t, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        IconView chev = new IconView(this, Icons.CHEVRON_R).tint(Theme.FAINT).size(12);
        row.addView(chev, new LinearLayout.LayoutParams(dp(20), dp(20)));

        row.setOnClickListener(l);
        wrap.addView(row);

        View line = new View(this);
        line.setBackgroundColor(Theme.BORDER);
        wrap.addView(line, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1)));
        wrap.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        return wrap;
    }

    /** 一个开关：夜纸 ↔ 日纸。点一下就整页换。 */
    private View themeRow() {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(14), 0, dp(14));

        boolean dark = Theme.mode == Theme.DARK;
        row.addView(new IconView(this, dark ? Icons.MOON : Icons.SUN)
                .tint(Theme.ACCENT).size(17));

        TextView t = new TextView(this);
        t.setText(dark ? "深色 · 夜纸" : "浅色 · 日纸");
        t.setTextColor(Theme.INK);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        t.setPadding(dp(12), 0, 0, 0);
        row.addView(t, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        // 小方块开关：左日右夜，朱砂块表示现在在哪边。
        row.addView(capsuleSwitch(dark), new LinearLayout.LayoutParams(dp(40), dp(22)));

        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                int next = Theme.toggled();
                Theme.set(next);
                store.setThemeMode(next);
                recreate();
            }
        });

        wrap.addView(row);
        View line = new View(this);
        line.setBackgroundColor(Theme.BORDER);
        wrap.addView(line, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1)));
        return wrap;
    }

    /** 40×22 的小方块开关；knob 用 tag 找回，切换时只挪滑块。 */
    private FrameLayout capsuleSwitch(boolean on) {
        FrameLayout capsule = new FrameLayout(this);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(3));
        bg.setColor(Theme.WASH);
        capsule.setBackground(bg);
        View knob = new View(this);
        GradientDrawable kg = new GradientDrawable();
        kg.setCornerRadius(dp(2));
        kg.setColor(Theme.ACCENT);
        knob.setBackground(kg);
        knob.setTag("knob");
        FrameLayout.LayoutParams kp = new FrameLayout.LayoutParams(dp(16), dp(16));
        kp.gravity = Gravity.CENTER_VERTICAL | (on ? Gravity.RIGHT : Gravity.LEFT);
        kp.leftMargin = dp(3);
        kp.rightMargin = dp(3);
        capsule.addView(knob, kp);
        return capsule;
    }

    private void setCapsule(FrameLayout capsule, boolean on) {
        if (capsule == null) {
            return;
        }
        View knob = capsule.findViewWithTag("knob");
        if (knob == null) {
            return;
        }
        FrameLayout.LayoutParams p = (FrameLayout.LayoutParams) knob.getLayoutParams();
        p.gravity = Gravity.CENTER_VERTICAL | (on ? Gravity.RIGHT : Gravity.LEFT);
        knob.setLayoutParams(p);
    }

    /** 声语开关：四声器物音。关掉时连 AudioTrack 都不建。 */
    private View soundRow() {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(14), 0, dp(14));

        row.addView(new IconView(this, Icons.SOUND).tint(Theme.ACCENT).size(17));

        TextView t = new TextView(this);
        t.setText("声音");
        t.setTextColor(Theme.INK);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        t.setPadding(dp(12), 0, 0, 0);
        row.addView(t, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        final FrameLayout capsule = capsuleSwitch(store.sound());
        row.addView(capsule, new LinearLayout.LayoutParams(dp(40), dp(22)));

        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                boolean next = !store.sound();
                store.setSound(next);
                Voice voice = Voice.get(SettingsActivity.this);
                voice.setEnabled(next);
                setCapsule(capsule, next);
                if (next) {
                    voice.play(Voice.TICK);
                    say("声音开了。");
                } else {
                    say("声音关了，只剩灯。");
                }
            }
        });

        wrap.addView(row);
        View line = new View(this);
        line.setBackgroundColor(Theme.BORDER);
        wrap.addView(line, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1)));
        return wrap;
    }

    /** 一块朱砂色的主按钮：保存这类动作只留这一种。 */
    private View bigButton(String text, View.OnClickListener l) {
        TextView b = new TextView(this);
        b.setText(text);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        b.setLetterSpacing(0.2f);
        b.setTextColor(Theme.ON_ACCENT);
        b.setGravity(Gravity.CENTER);
        GradientDrawable g = new GradientDrawable();
        g.setColor(Theme.ACCENT);
        g.setCornerRadius(dp(3));
        b.setBackground(g);
        b.setOnClickListener(l);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(52));
        p.setMargins(0, dp(18), 0, dp(8));
        b.setLayoutParams(p);
        return b;
    }

    // ────────────────────────── 时段 ──────────────────────────

    /** 直接打 4 个数字比转表盘快，跨零点的 22:00 → 07:00 也是这么打出来的。 */
    private void pickTime(final TextView cell) {
        final EditText input = new EditText(this);
        input.setText(String.valueOf(cell.getText()));
        input.setHint("比如 22:30");
        input.setSingleLine(true);
        input.setTextColor(Theme.INK);
        input.setHintTextColor(Theme.FAINT);
        input.setBackgroundColor(Theme.WASH);
        input.setPadding(dp(12), dp(12), dp(12), dp(12));

        new AlertDialog.Builder(this)
                .setTitle("几点")
                .setView(input)
                .setPositiveButton("好", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        String v = normalise(input.getText().toString());
                        if (v == null) {
                            say("要写成 08:30 这样。");
                            return;
                        }
                        cell.setText(v);
                        if (cell == startView) {
                            start = v;
                        } else {
                            end = v;
                        }
                        pushWindow();
                    }
                })
                .setNegativeButton("算了", null)
                .show();
    }

    /** "9:5" / "0905" / "9：05" 都认，收成 HH:MM；认不出返回 null。 */
    private static String normalise(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.trim().replace("：", ":").replace(" ", "");
        if (s.isEmpty()) {
            return null;
        }
        try {
            int h, m;
            if (s.contains(":")) {
                String[] p = s.split(":");
                if (p.length != 2) {
                    return null;
                }
                h = Integer.parseInt(p[0]);
                m = Integer.parseInt(p[1]);
            } else if (s.length() <= 2) {
                h = Integer.parseInt(s);
                m = 0;
            } else if (s.length() <= 4) {
                h = Integer.parseInt(s.substring(0, s.length() - 2));
                m = Integer.parseInt(s.substring(s.length() - 2));
            } else {
                return null;
            }
            if (h < 0 || h > 23 || m < 0 || m > 59) {
                return null;
            }
            return String.format(Locale.CHINA, "%02d:%02d", h, m);
        } catch (Exception e) {
            return null;
        }
    }

    private void pushWindow() {
        String json = "{\"cmd\":\"window\",\"start\":\"" + start + "\",\"end\":\"" + end + "\"}";
        control.send(currentHost(), currentPort(), json, new Control.Callback() {
            @Override public void onReply(final boolean ok, final JSONObject o, final String error) {
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (!ok) {
                            say("云端没收到：" + error);
                            return;
                        }
                        store.setWindow(start, end);
                        applyStatus(o);
                        say("记下了：每天 " + start + " → " + end + "。");
                    }
                });
            }
        });
    }

    // ────────────────────────── 声纹 ──────────────────────────

    private void enrollVoice() {
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            afterPermissionEnroll = true;
            requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO}, REQ_AUDIO);
            return;
        }
        say("对着手机说 8 秒，随便说什么都行。");
        voiceState.setText("正在录…");
        enroll.start(currentHost(), store.enrollPort(), 8, new Enroll.Callback() {
            @Override public void onDone(final boolean ok, final String message) {
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        voiceState.setText(ok ? "已经录好了，之后只记你的声音。"
                                : "没录成：" + message);
                        refreshStatus();
                    }
                });
            }
        });
    }

    private void confirmWipe() {
        new AlertDialog.Builder(this)
                .setMessage("删掉之后，谁说话都不会被记，直到你再录一次。")
                .setPositiveButton("删掉", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        wipe();
                    }
                })
                .setNegativeButton("算了", null)
                .show();
    }

    /** 手机录的那版和板子麦克风差着一条链路，分数常年卡在 0.6 附近；
     *  让板子自己录，同人分数能到 0.9 上下。云端 30 秒内等一句话。 */
    private void enrollFromBoard() {
        voiceState.setText("好，现在对着板子说 8 秒，说完等一下。");
        control.send(currentHost(), currentPort(), "{\"cmd\":\"enroll_board\"}",
                new Control.Callback() {
            @Override public void onReply(final boolean ok, final JSONObject o, final String error) {
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (!ok) {
                            voiceState.setText("没连上云端：" + error);
                            return;
                        }
                        voiceState.setText("在等板子那边的话…");
                    }
                });
            }
        });
    }

    private void wipe() {
        control.send(currentHost(), currentPort(), "{\"cmd\":\"wipe\"}", new Control.Callback() {
            @Override public void onReply(final boolean ok, final JSONObject o, final String error) {
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (!ok) {
                            say("没删成：" + error);
                            return;
                        }
                        applyStatus(o);
                        say("删掉了。现在谁说话都不会被记。");
                    }
                });
            }
        });
    }

    /** 先问一句：填错了板子会掉线，得重新烧录才能救回来。 */
    private void sendWifi() {
        final String ssid = wifiSsid.getText().toString().trim();
        final String pswd = wifiPswd.getText().toString();
        if (ssid.isEmpty()) {
            say("WiFi 名字空着。");
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("改板子的 WiFi")
                .setMessage("板子会重启去连「" + ssid + "」。\n\n名字或密码填错，板子会掉线——"
                        + "得用 USB 重新烧一次固件才能恢复。继续吗？")
                .setPositiveButton("继续", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        reallySendWifi(ssid, pswd);
                    }
                })
                .setNegativeButton("算了", null)
                .show();
    }

    /** 在线就经云端转给板子；云端说板子没连，就直接连它开的配网热点。 */
    private void reallySendWifi(final String ssid, final String pswd) {
        final String json = "{\"cmd\":\"wifi\",\"ssid\":\"" + ssid.replace("\\", "")
                + "\",\"pswd\":\"" + pswd.replace("\\", "") + "\"}";

        say("发给板子…");
        control.send(store.cloudHost(), store.cloudPort(), json, new Control.Callback() {
            @Override public void onReply(boolean ok, org.json.JSONObject reply, String error) {
                if (ok) {
                    say(reply.optString("message", "板子收到了，会重连「" + ssid + "」"));
                    return;
                }
                say("云端这边不行（" + (reply != null ? reply.optString("message") : error)
                        + "），试试直连配网热点…");
                control.send("192.168.4.1", 19004, json, new Control.Callback() {
                    @Override public void onReply(boolean ok2, org.json.JSONObject r2, String e2) {
                        say(ok2 ? "板子记下了，它会重启去连「" + ssid + "」。"
                                : "连不上板子的配网热点：先连 Diting-Setup 那个 WiFi。");
                    }
                });
            }
        });
    }

    private void reprovision() {
        say("让板子重新配网…");
        control.send(store.cloudHost(), store.cloudPort(), "{\"cmd\":\"reprovision\"}",
                new Control.Callback() {
                    @Override public void onReply(boolean ok, org.json.JSONObject reply, String error) {
                        say(ok ? "板子会重启，开一个 Diting-Setup 热点；手机连上去再点「发给板子」。"
                                : "没成功：" + (reply != null ? reply.optString("message") : error));
                    }
                });
    }

    private void refreshStatus() {
        control.send(currentHost(), currentPort(), "{\"cmd\":\"status\"}", new Control.Callback() {
            @Override public void onReply(final boolean ok, final JSONObject o, final String error) {
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (!ok) {
                            voiceState.setText("云端连不上，先看地址对不对。");
                            return;
                        }
                        applyStatus(o);
                    }
                });
            }
        });
    }

    private void applyStatus(JSONObject o) {
        if (o == null) {
            return;
        }
        boolean enrolled = o.optBoolean("enrolled", false);
        boolean inWindow = o.optBoolean("in_window", true);
        JSONObject w = o.optJSONObject("window");
        if (w != null) {
            String s = w.optString("start", start);
            String e = w.optString("end", end);
            if (!s.isEmpty() && !e.isEmpty()) {
                start = s;
                end = e;
                startView.setText(s);
                endView.setText(e);
            }
        }
        voiceState.setText(enrolled ? "已录好：只有你的声音会被记。"
                : "还没录：现在谁说话都不会被记。");
        windowNow.setText(inWindow ? "现在在时段里，板子说的话会被记下。"
                : "现在在时段外，板子还在推，但云端直接丢掉。");
    }

    // ────────────────────────── 存取 ──────────────────────────

    private String currentHost() {
        String h = host.getText().toString().trim();
        return h.isEmpty() ? store.cloudHost() : h;
    }

    private int currentPort() {
        return parsePort(port.getText().toString(), Cloud.PORT);
    }

    private void save() {
        if (wifiSsid != null) {
            store.setWifiSsid(wifiSsid.getText().toString().trim());
            store.setWifiPswd(wifiPswd.getText().toString());
        }
        store.setApiKey(key.getText().toString());
        store.setModel(model.getText().toString());
        store.setEndpoint(endpoint.getText().toString());
        store.setCloudHost(host.getText().toString());
        store.setCloudPort(currentPort());
        store.setEnrollPort(currentPort() + 1);
        store.setWindow(start, end);
        finish();
    }

    private static int parsePort(String text, int fallback) {
        try {
            int p = Integer.parseInt(text.trim());
            return p > 0 && p < 65536 ? p : fallback;
        } catch (Exception e) {
            return fallback;
        }
    }

    private void say(String s) {
        reply.setText(s);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] result) {
        super.onRequestPermissionsResult(code, perms, result);
        if (code == REQ_AUDIO && result.length > 0
                && result[0] == PackageManager.PERMISSION_GRANTED) {
            if (afterPermissionEnroll) {
                afterPermissionEnroll = false;
                enrollVoice();
            }
        } else if (code == REQ_AUDIO) {
            say("没有麦克风权限，录不了。");
        }
    }

    private int dp(int v) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }
}

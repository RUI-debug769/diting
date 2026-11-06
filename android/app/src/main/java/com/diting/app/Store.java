package com.diting.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Collections;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.Locale;

/**
 * 记录。用 SharedPreferences 存 JSON，够用。
 *
 * 存的东西刻意很少：原话、谛听给出的三栏判断（事实/待办/需留意）、以及「被否认过」这个标记。
 * 不存音频。这条原则从第一版就没变：它保存的不是你的声音，是你对自己的说法。
 *
 * 保留策略：按天存，**只留最近 7 天**。第 8 天开始，最早的原文自动消失。
 * 这是产品承诺里「忘记是功能」的实现，不是省空间。
 */
public class Store {

    /** 最近 7 天。改这个数字之前先想清楚对用户意味着什么。 */
    public static final int KEEP_DAYS = 7;

    public static class Event {
        public String mine = "";
        public String its;
        public boolean denied = false;

        public Event(String mine) {
            this.mine = mine;
        }
    }

    /** 一天的判断结果。三栏，都是短句。 */
    public static class Summary {
        public final List<String> facts = new ArrayList<String>();
        public final List<String> todos = new ArrayList<String>();
        public final List<String> watch = new ArrayList<String>();

        /** 流式写的时候，哪一栏、哪一条还没收尾（不落盘，只影响显示）。 */
        public transient int openSection = -1;
        public transient String openItem = null;

        public boolean empty() {
            return facts.isEmpty() && todos.isEmpty() && watch.isEmpty();
        }

        /**
         * 「今天有几件事要你看」= 待办 + 需留意，去重之后才算数。
         *
         * 直接数条数不准：模型会把同一件事换个说法写两遍，也会把「去图书馆还书」
         * 同时写进待办和留意。这里先把每条归一（去掉标点空格），再按
         * 「相同 / 一方包含另一方 / 两字组重合度过高」三种情况合并。
         */
        public int needCount() {
            return needItems().size();
        }

        public List<String> needItems() {
            List<String> out = new ArrayList<String>();
            for (String t : todos) {
                addIfNew(out, t);
            }
            for (String w : watch) {
                addIfNew(out, w);
            }
            return out;
        }

        /** 归一：只留字母数字，中文按字留（不转拼音、不切词，够用且不会误伤）。 */
        public static String norm(String s) {
            if (s == null) {
                return "";
            }
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (Character.isLetterOrDigit(c)) {
                    b.append(Character.toLowerCase(c));
                }
            }
            return b.toString();
        }

        /** 两句话是不是同一件事：归一后相同、长句互相包含、或两字组重合过半。 */
        public static boolean sameThing(String a, String b) {
            String x = norm(a);
            String y = norm(b);
            if (x.isEmpty() || y.isEmpty()) {
                return false;
            }
            if (x.equals(y)) {
                return true;
            }
            if (x.length() >= 4 && y.length() >= 4 && (x.contains(y) || y.contains(x))) {
                return true;
            }
            return bigramOverlap(x, y) >= 0.6;
        }

        private static void addIfNew(List<String> out, String cand) {
            if (norm(cand).length() < 3) {
                return;
            }
            for (String kept : out) {
                if (sameThing(cand, kept)) {
                    return;
                }
            }
            out.add(cand);
        }

        private static double bigramOverlap(String a, String b) {
            Set<String> x = bigrams(a);
            Set<String> y = bigrams(b);
            if (x.isEmpty() || y.isEmpty()) {
                return 0;
            }
            int hit = 0;
            for (String s : x) {
                if (y.contains(s)) {
                    hit++;
                }
            }
            return hit / (double) Math.min(x.size(), y.size());
        }

        private static Set<String> bigrams(String s) {
            Set<String> out = new HashSet<String>();
            for (int i = 0; i + 1 < s.length(); i++) {
                out.add(s.substring(i, i + 2));
            }
            return out;
        }

        public String toJson() {
            try {
                JSONObject o = new JSONObject();
                o.put("facts", new JSONArray(facts));
                o.put("todos", new JSONArray(todos));
                o.put("watch", new JSONArray(watch));
                return o.toString();
            } catch (Exception e) {
                return "";
            }
        }

        public static Summary fromJson(String raw) {
            Summary s = new Summary();
            if (raw == null || raw.isEmpty()) {
                return s;
            }
            try {
                JSONObject o = new JSONObject(raw);
                fill(o.optJSONArray("facts"), s.facts);
                fill(o.optJSONArray("todos"), s.todos);
                fill(o.optJSONArray("watch"), s.watch);
            } catch (Exception ignored) {
            }
            return s;
        }

        private static void fill(JSONArray arr, List<String> out) {
            if (arr == null) {
                return;
            }
            for (int i = 0; i < arr.length(); i++) {
                String v = arr.optString(i, "").trim();
                if (!v.isEmpty()) {
                    out.add(v);
                }
            }
        }
    }

    private static final String PREFS = "diting";
    private static final String KEY_DAYS = "days_v2";      // {"2026-11-06": {"events":[...],"summary":"..."}}
    private static final String KEY_EVENTS = "events";     // 旧版单日存档，只用于迁移
    private static final String KEY_APIKEY = "apikey";
    private static final String KEY_ENDPOINT = "endpoint";
    private static final String KEY_MODEL = "model";
    private static final String KEY_CLOUD_HOST = "cloud_host";
    private static final String KEY_CLOUD_PORT = "cloud_port";
    private static final String KEY_ENROLL_PORT = "enroll_port";
    private static final String KEY_WIN_START = "win_start";
    private static final String KEY_WIN_END = "win_end";
    private static final String KEY_AUTO_AT = "auto_at";
    private static final String KEY_WIFI_SSID = "wifi_ssid";
    private static final String KEY_WIFI_PSWD = "wifi_pswd";
    private static final String KEY_THEME = "theme_mode";

    private final SharedPreferences sp;

    public Store(Context ctx) {
        sp = ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static String today() {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(new Date());
    }

    // ────────────────────────── 按天的存档 ──────────────────────────

    private JSONObject days() {
        try {
            String raw = sp.getString(KEY_DAYS, "");
            if (raw == null || raw.isEmpty()) {
                return new JSONObject();
            }
            return new JSONObject(raw);
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private void putDays(JSONObject days) {
        // 只留最近 KEEP_DAYS 天：key 是 yyyy-MM-dd，字符串序就是时间序
        List<String> keys = new ArrayList<String>();
        Iterator<String> it = days.keys();
        while (it.hasNext()) {
            keys.add(it.next());
        }
        Collections.sort(keys);
        while (keys.size() > KEEP_DAYS) {
            days.remove(keys.remove(0));
        }
        sp.edit().putString(KEY_DAYS, days.toString()).apply();
    }

    private static JSONObject dayOf(JSONObject days, String date) {
        JSONObject d = days.optJSONObject(date);
        return d == null ? new JSONObject() : d;
    }

    /**
     * 清掉某一天：原文和三栏一起删，不留副本。
     * 演示前清场用它——昨天联调留下的杂音不该出现在评委眼前。
     */
    public void clearDay(String date) {
        JSONObject days = days();
        if (days.has(date)) {
            days.remove(date);
            sp.edit().putString(KEY_DAYS, days.toString()).apply();
        }
        sp.edit().remove(KEY_EVENTS).apply();
    }

    public List<Event> load() {
        return loadDay(today());
    }

    public List<Event> loadDay(String date) {
        List<Event> out = new ArrayList<Event>();
        JSONObject days = days();

        // 迁移：老版本只有一个 events 字段，第一次打开时并到「今天」
        if (days.length() == 0) {
            String legacy = sp.getString(KEY_EVENTS, "");
            if (legacy != null && !legacy.isEmpty()) {
                JSONObject d = new JSONObject();
                try {
                    d.put("events", new JSONArray(legacy));
                    days.put(today(), d);
                    putDays(days);
                } catch (Exception ignored) {
                }
            }
        }

        JSONArray arr = dayOf(days, date).optJSONArray("events");
        if (arr == null) {
            return out;
        }
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) {
                continue;
            }
            Event e = new Event(o.optString("mine", ""));
            String its = o.optString("its", "");
            e.its = its.isEmpty() ? null : its;
            e.denied = o.optBoolean("denied", false);
            out.add(e);
        }
        return out;
    }

    public void save(List<Event> events) {
        saveDay(today(), events);
    }

    public void saveDay(String date, List<Event> events) {
        JSONArray arr = new JSONArray();
        for (Event e : events) {
            try {
                JSONObject o = new JSONObject();
                o.put("mine", e.mine == null ? "" : e.mine);
                o.put("its", e.its == null ? "" : e.its);
                o.put("denied", e.denied);
                arr.put(o);
            } catch (Exception ignored) {
            }
        }
        JSONObject days = days();
        try {
            JSONObject d = dayOf(days, date);
            d.put("events", arr);
            days.put(date, d);
            putDays(days);
        } catch (Exception ignored) {
        }
    }

    public Summary loadSummary() {
        return loadSummaryDay(today());
    }

    public Summary loadSummaryDay(String date) {
        return Summary.fromJson(dayOf(days(), date).optString("summary", ""));
    }

    public void saveSummary(Summary s) {
        JSONObject days = days();
        try {
            JSONObject d = dayOf(days, today());
            d.put("summary", s == null ? "" : s.toJson());
            days.put(today(), d);
            putDays(days);
        } catch (Exception ignored) {
        }
    }

    /** 有记录的日子，从新到旧。留着给「往回翻」用。 */
    public List<String> recentDays() {
        JSONObject days = days();
        List<String> keys = new ArrayList<String>();
        Iterator<String> it = days.keys();
        while (it.hasNext()) {
            keys.add(it.next());
        }
        Collections.sort(keys, Collections.reverseOrder());
        return keys;
    }

    // ────────────────────────── 设置 ──────────────────────────

    public String apiKey() {
        return sp.getString(KEY_APIKEY, "");
    }

    public void setApiKey(String v) {
        sp.edit().putString(KEY_APIKEY, v == null ? "" : v.trim()).apply();
    }

    public String endpoint() {
        return sp.getString(KEY_ENDPOINT, "");
    }

    public void setEndpoint(String v) {
        sp.edit().putString(KEY_ENDPOINT, v == null ? "" : v.trim()).apply();
    }

    public String model() {
        return sp.getString(KEY_MODEL, "deepseek-chat");
    }

    public void setModel(String v) {
        sp.edit().putString(KEY_MODEL, v == null || v.trim().isEmpty() ? "deepseek-chat" : v.trim()).apply();
    }

    /** 云端地址。板子直连的那台机器，App 只从它那里收已经判过声纹的结果。 */
    public String cloudHost() {
        return sp.getString(KEY_CLOUD_HOST, "");
    }

    public void setCloudHost(String v) {
        sp.edit().putString(KEY_CLOUD_HOST, v == null ? "" : v.trim()).apply();
    }

    public int cloudPort() {
        int d = Integer.parseInt(sp.getString(KEY_CLOUD_PORT, "19002"));
        return d <= 0 || d > 65535 ? 19002 : d;
    }

    public void setCloudPort(int v) {
        sp.edit().putString(KEY_CLOUD_PORT, String.valueOf(v)).apply();
    }

    /** 注册声纹用的端口，和听板子、听 App 的是三条不同的口。 */
    public int enrollPort() {
        int d = Integer.parseInt(sp.getString(KEY_ENROLL_PORT, "19003"));
        return d <= 0 || d > 65535 ? 19003 : d;
    }

    public void setEnrollPort(int v) {
        sp.edit().putString(KEY_ENROLL_PORT, String.valueOf(v)).apply();
    }

    /** 板子从几点记到几点。start == end 表示全天；start > end 表示跨零点。 */
    public String windowStart() {
        return sp.getString(KEY_WIN_START, "00:00");
    }

    public String windowEnd() {
        return sp.getString(KEY_WIN_END, "00:00");
    }

    public void setWindow(String start, String end) {
        sp.edit().putString(KEY_WIN_START, start).putString(KEY_WIN_END, end).apply();
    }

    /** 上次填过的 WiFi，只是省得每次重打；真正的凭据在板子 KV 里。 */
    public String wifiSsid() {
        return sp.getString(KEY_WIFI_SSID, "");
    }

    public void setWifiSsid(String v) {
        sp.edit().putString(KEY_WIFI_SSID, v == null ? "" : v.trim()).apply();
    }

    public String wifiPswd() {
        return sp.getString(KEY_WIFI_PSWD, "");
    }

    public void setWifiPswd(String v) {
        sp.edit().putString(KEY_WIFI_PSWD, v == null ? "" : v).apply();
    }

    /** 每天自动写的时间 "HH:MM"；空串表示关掉。 */
    /** 界面明暗档：0 最暗，4 最浅，默认 1（就是现在这套暖黑）。 */
    /** 主题：0 深色、1 浅色。 */
    public int themeMode() {
        return sp.getInt(KEY_THEME, 0) == 1 ? 1 : 0;
    }

    public void setThemeMode(int v) {
        sp.edit().putInt(KEY_THEME, v == 1 ? 1 : 0).apply();
    }

    public String autoAt() {
        return sp.getString(KEY_AUTO_AT, "");
    }

    public void setAutoAt(String v) {
        sp.edit().putString(KEY_AUTO_AT, v == null ? "" : v.trim()).apply();
    }
}

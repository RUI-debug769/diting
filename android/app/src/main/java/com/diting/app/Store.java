package com.diting.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 一天的记录。现在用 SharedPreferences 存一段 JSON，够用。
 *
 * 存的东西刻意很少：只有原话、谛听写的那句、以及「被否认过」这个标记。
 * 不存音频、不存时间戳以外的元数据——这条产品原则从第一版就没变过：
 * 它保存的不是你的声音，是你对自己的说法。
 */
public class Store {

    public static class Event {
        public String mine = "";
        public String its;
        public boolean denied = false;

        public Event(String mine) {
            this.mine = mine;
        }
    }

    private static final String PREFS = "diting";
    private static final String KEY_EVENTS = "events";
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

    private final SharedPreferences sp;

    public Store(Context ctx) {
        sp = ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public List<Event> load() {
        List<Event> out = new ArrayList<>();
        String raw = sp.getString(KEY_EVENTS, "");
        if (raw == null || raw.isEmpty()) {
            return out;
        }
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Event e = new Event(o.optString("mine", ""));
                String its = o.optString("its", "");
                e.its = its.isEmpty() ? null : its;
                e.denied = o.optBoolean("denied", false);
                out.add(e);
            }
        } catch (Exception ignored) {
            // 存档坏了就当今天还没开始
        }
        return out;
    }

    public void save(List<Event> events) {
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
        sp.edit().putString(KEY_EVENTS, arr.toString()).apply();
    }

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
    public String autoAt() {
        return sp.getString(KEY_AUTO_AT, "");
    }

    public void setAutoAt(String v) {
        sp.edit().putString(KEY_AUTO_AT, v == null ? "" : v.trim()).apply();
    }

    public void setModel(String v) {
        sp.edit().putString(KEY_MODEL, v == null || v.trim().isEmpty() ? "deepseek-chat" : v.trim()).apply();
    }
}

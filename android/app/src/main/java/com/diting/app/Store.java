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

        /**
         * 把新一版并进旧一版：同一条（sameThing）用新说法替换，旧版里这次没提到的继续留着。
         *
         * 为什么不是整个覆盖：重写时模型每次挑的句子不完全一样，上一版里那句「他自称薛瑞」
         * 这次没被选中，并不等于它不是事实。合并之后，只有它真的不成立了（被新说法替掉），
         * 才会从屏幕上消失。
         */
        public static Summary merge(Summary oldOne, Summary fresh) {
            Summary out = new Summary();
            if (oldOne != null) {
                out.facts.addAll(oldOne.facts);
                out.todos.addAll(oldOne.todos);
                out.watch.addAll(oldOne.watch);
            }
            if (fresh != null) {
                mergeSection(out.facts, fresh.facts);
                mergeSection(out.todos, fresh.todos);
                mergeSection(out.watch, fresh.watch);
            }
            out.compact();
            return out;
        }

        private static final int MAX_PER_SECTION = 8;

        private static void mergeSection(List<String> into, List<String> fresh) {
            // 旧版自己也可能已经攒了两条同义的话，先自愈一遍，免得越并越多。
            List<String> compact = new ArrayList<String>();
            for (String s : into) {
                addIfNew(compact, s);
            }
            into.clear();
            into.addAll(compact);

            for (String item : fresh) {
                if (norm(item).length() < 3) {
                    continue;
                }
                int hit = -1;
                for (int i = 0; i < into.size(); i++) {
                    if (sameThing(item, into.get(i))) {
                        hit = i;
                        break;
                    }
                }
                if (hit >= 0) {
                    // 同一件事：留信息更全的那条（长的那条通常带着时间/对象/条件）。
                    if (item.length() > into.get(hit).length()) {
                        into.set(hit, item);
                    }
                } else {
                    into.add(item);        // 这次新说到的，接在后面
                }
            }
            // 并入之后，被替换掉的那条可能和新说法其实是同一件事（比如新版把两件事
            // 合成了一句），再压一遍，只留先说到的那个。
            List<String> again = new ArrayList<String>();
            for (String s : into) {
                addIfNew(again, s);
            }
            into.clear();
            into.addAll(again);

            while (into.size() > MAX_PER_SECTION) {
                into.remove(0);            // 留太多反而没人看，只保留最近八条
            }
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

        /** 去掉「他说/你要/直接/到时」这类口头壳，只留事情本身。 */
        private static String strip(String s) {
            String x = norm(s);
            String[] junk = {"他说", "她说", "你要", "到时候", "到时", "直接", "一下",
                    "然后", "其实", "顺便", "还有", "这个", "那个"};
            for (String j : junk) {
                x = x.replace(j, "");
            }
            return x;
        }

        /** 两句话是不是同一件事：归一后相同、长句互相包含、开头六个字一样、或两字组重合接近一半。 */
        public static boolean sameThing(String a, String b) {
            String x = strip(a);
            String y = strip(b);
            if (x.isEmpty() || y.isEmpty()) {
                return false;
            }
            if (x.equals(y)) {
                return true;
            }
            if (x.length() >= 4 && y.length() >= 4 && (x.contains(y) || y.contains(x))) {
                return true;
            }
            double over = bigramOverlap(x, y);
            if (prefixLen(x, y) >= 6 && over >= 0.25) {
                return true;
            }
            // 共享一个实词（比如都在说「音频」）就算同一件事：这正是模型最爱
            // 换着说法写五六遍的地方。
            if (sharesTopic(x, y)) {
                return true;
            }
            return over >= 0.42;
        }

        /** 严格版：只认几乎同一句（归一相同 / 包含 / 两字组重合过半）。 */
        public static boolean strictSame(String a, String b) {
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
            return bigramOverlap(x, y) >= 0.5;
        }

        private static void addIfNew(List<String> out, String cand) {
            if (norm(cand).length() < 3) {
                return;
            }
            for (String kept : out) {
                if (strictSame(cand, kept)) {
                    return;
                }
            }
            out.add(cand);
        }

        /** 常见虚词两字组：不算「同一件事」的证据，否则「明天…」会全被并成一条。 */
        private static final Set<String> STOP = new HashSet<String>(java.util.Arrays.asList(
                "今天", "明天", "后天", "昨天", "上午", "下午", "中午", "晚上", "时候", "现在",
                "可以", "一下", "一个", "我们", "他们", "她们", "这个", "那个", "什么", "怎么",
                "就是", "还是", "因为", "所以", "然后", "但是", "而且", "如果", "已经", "可能",
                "觉得", "知道", "应该", "需要", "事情", "东西", "地方", "问题", "以后", "之前",
                "之后", "这样", "那样", "怎样", "多少", "几个", "有些", "真的", "比较", "非常",
                "特别", "继续", "开始", "结束", "完成", "准备", "打算", "计划", "决定", "认为",
                "自己", "大家", "一些", "有点", "好像", "估计", "应该", "直接", "到时"));

        /** 两句里有没有同一个「实词」两字组（音频、图书馆、张老师这种）。 */
        private static boolean sharesTopic(String x, String y) {
            if (x.length() < 6 || y.length() < 6) {
                return false;
            }
            Set<String> b = bigrams(y);
            for (String g : bigrams(x)) {
                if (!STOP.contains(g) && b.contains(g)) {
                    return true;
                }
            }
            return false;
        }

        private static int prefixLen(String a, String b) {
            int n = Math.min(a.length(), b.length());
            int i = 0;
            while (i < n && a.charAt(i) == b.charAt(i)) {
                i++;
            }
            return i;
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
            s.compact();
            return s;
        }

        /** 三栏各自压一遍：同义只留信息最全的一条。模型很爱把一句话换两种说法写两遍。 */
        public void compact() {
            collapse(facts);
            collapse(todos);
            collapse(watch);
        }

        /** 把一栏里同义的条目收成一条；保留更长的那个说法。 */
        private static void collapse(List<String> list) {
            List<String> out = new ArrayList<String>();
            for (String s : list) {
                if (norm(s).length() < 3) {
                    continue;
                }
                int hit = -1;
                for (int i = 0; i < out.size(); i++) {
                    if (sameThing(s, out.get(i))) {
                        hit = i;
                        break;
                    }
                }
                if (hit < 0) {
                    out.add(s);
                } else if (s.length() > out.get(hit).length()) {
                    out.set(hit, s);
                }
            }
            // 换了更长的那条之后，可能又和别的条目撞上，再扫一遍直到稳定。
            for (int pass = 0; pass < 3; pass++) {
                List<String> again = new ArrayList<String>();
                boolean changed = false;
                for (String s : out) {
                    int hit = -1;
                    for (int i = 0; i < again.size(); i++) {
                        if (sameThing(s, again.get(i))) {
                            hit = i;
                            break;
                        }
                    }
                    if (hit < 0) {
                        again.add(s);
                    } else {
                        changed = true;
                        if (s.length() > again.get(hit).length()) {
                            again.set(hit, s);
                        }
                    }
                }
                out = again;
                if (!changed) {
                    break;
                }
            }
            list.clear();
            list.addAll(out);
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

    /** 一章：章名 + 正文。 */
    public static class Chapter {
        public String title = "";
        public String body = "";

        public Chapter() {
        }

        public Chapter(String title, String body) {
            this.title = title == null ? "" : title;
            this.body = body == null ? "" : body;
        }
    }

    /**
     * 一天的一篇小说：谛听把这一天的原话讲成志怪故事。
     *
     * 每章约两千字，章数看当天素材，最多四章。它和三栏存在同一条 day JSON 里，
     * 七天之后跟原话一起消失——故事不是档案，忘了也不可惜。
     */
    public static class Article {
        public String title = "";
        public final List<Chapter> chapters = new ArrayList<Chapter>();
        public long updatedAt = 0L;
        /** 空串表示模型写的；非空是兜底原因，界面要如实说。 */
        public String note = "";
        public boolean fromModel = true;

        public boolean hasContent() {
            if (chapters.isEmpty()) {
                return false;
            }
            for (Chapter c : chapters) {
                if (c.body != null && !c.body.trim().isEmpty()) {
                    return true;
                }
            }
            return false;
        }

        public int charCount() {
            int n = 0;
            for (Chapter c : chapters) {
                n += c.body == null ? 0 : c.body.length();
            }
            return n;
        }

        /** 单篇短故事：没有分章标题。 */
        public boolean single() {
            return chapters.size() == 1
                    && (chapters.get(0).title == null || chapters.get(0).title.trim().isEmpty());
        }

        public String toJson() {
            try {
                JSONObject o = new JSONObject();
                o.put("title", title == null ? "" : title);
                o.put("at", updatedAt);
                o.put("note", note == null ? "" : note);
                o.put("fromModel", fromModel);
                JSONArray arr = new JSONArray();
                for (Chapter c : chapters) {
                    JSONObject co = new JSONObject();
                    co.put("t", c.title == null ? "" : c.title);
                    co.put("b", c.body == null ? "" : c.body);
                    arr.put(co);
                }
                o.put("chapters", arr);
                return o.toString();
            } catch (Exception e) {
                return "";
            }
        }

        public static Article fromJson(String raw) {
            Article a = new Article();
            if (raw == null || raw.isEmpty()) {
                return a;
            }
            try {
                JSONObject o = new JSONObject(raw);
                a.title = o.optString("title", "");
                a.updatedAt = o.optLong("at", 0L);
                a.note = o.optString("note", "");
                a.fromModel = o.optBoolean("fromModel", true);
                JSONArray arr = o.optJSONArray("chapters");
                if (arr != null) {
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject co = arr.optJSONObject(i);
                        if (co == null) {
                            continue;
                        }
                        a.chapters.add(new Chapter(co.optString("t", ""), co.optString("b", "")));
                    }
                }
            } catch (Exception ignored) {
            }
            return a;
        }

        /**
         * 把模型吐出的纯文本切成「书名 + 章」。
         * 约定：第一行「书名：…」，之后每章一行「第N章 章名」，其余是正文。
         * 模型偶尔不守格式，这里尽最大努力，不抛异常。
         */
        public static Article parse(String raw) {
            Article a = new Article();
            if (raw == null) {
                return a;
            }
            a.title = "听了一夜";
            Chapter cur = null;
            StringBuilder body = new StringBuilder();
            String[] lines = raw.split("\n", -1);
            for (String line : lines) {
                String t = line == null ? "" : line.trim();
                if (t.isEmpty()) {
                    if (cur != null && body.length() > 0) {
                        body.append("\n");
                    }
                    continue;
                }
                String name = titleOf(t);
                if (name != null) {
                    if (!name.isEmpty()) {
                        a.title = name;
                    }
                    continue;
                }
                if (isChapterLine(t)) {
                    if (cur != null) {
                        cur.body = body.toString().trim();
                        a.chapters.add(cur);
                    }
                    cur = new Chapter(t, "");
                    body.setLength(0);
                    continue;
                }
                if (cur == null) {
                    // 短篇没有章名：整篇正文收进一个无名章，别丢。
                    cur = new Chapter("", "");
                }
                body.append(t).append("\n");
            }
            if (cur != null) {
                cur.body = body.toString().trim();
                a.chapters.add(cur);
            }
            // 一章都没识别出来（模型完全没守格式）：整篇当一章。
            if (a.chapters.isEmpty()) {
                String whole = raw.trim();
                String name = titleOf(whole);
                if (name != null) {
                    whole = name;
                }
                if (!whole.isEmpty()) {
                    a.chapters.add(new Chapter("", whole));
                }
            }
            a.updatedAt = System.currentTimeMillis();
            return a;
        }

        /** 认「书名：」「篇名：」「标题：」三种抬头；不是抬头返回 null。 */
        private static String titleOf(String t) {
            String[] heads = {"书名：", "书名:", "篇名：", "篇名:", "标题：", "标题:"};
            for (String h : heads) {
                if (t.startsWith(h)) {
                    return t.substring(h.length()).trim();
                }
            }
            return null;
        }

        private static boolean isChapterLine(String t) {
            if (!t.startsWith("第")) {
                return false;
            }
            int i = 1;
            while (i < t.length()) {
                char c = t.charAt(i);
                if ((c >= '0' && c <= '9')
                        || "一二三四五六七八九十百零〇两".indexOf(c) >= 0) {
                    i++;
                } else {
                    break;
                }
            }
            return i > 1 && i < t.length() && t.charAt(i) == '章';
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
    private static final String KEY_SOUND = "sound_on";

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

    /** 那一天的「写」；没有就是空的 Article，不返回 null。 */
    public Article loadArticleDay(String date) {
        return Article.fromJson(dayOf(days(), date).optString("article", ""));
    }

    /** 一次读出多天的「写」，只解析一次存档；「写」页的列表用它，免得逐行重复解析。 */
    public List<Article> loadArticles(List<String> dates) {
        JSONObject days = days();
        List<Article> out = new ArrayList<Article>();
        for (String date : dates) {
            out.add(Article.fromJson(dayOf(days, date).optString("article", "")));
        }
        return out;
    }

    /**
     * 把一篇文章存回那一天。文章和原话、三栏同住一条 day JSON，
     * clearDay / putDays 会一起带走，不用另写清理逻辑。
     */
    public void saveArticleDay(String date, Article a) {
        JSONObject days = days();
        try {
            JSONObject d = dayOf(days, date);
            d.put("article", a == null ? "" : a.toJson());
            days.put(date, d);
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

    /** 声语：记下、不是你、写完、被戳的那四声。默认开。 */
    public boolean sound() {
        return sp.getBoolean(KEY_SOUND, true);
    }

    public void setSound(boolean on) {
        sp.edit().putBoolean(KEY_SOUND, on).apply();
    }

    public String autoAt() {
        return sp.getString(KEY_AUTO_AT, "");
    }

    public void setAutoAt(String v) {
        sp.edit().putString(KEY_AUTO_AT, v == null ? "" : v.trim()).apply();
    }
}

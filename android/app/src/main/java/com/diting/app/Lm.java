package com.diting.app;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * 把今天一天的原话交给模型，让它以谛听的口吻给每条事件写一句话。
 *
 * 三条规则写死在提示词里，因为它们是作品的伦理结构，不是风格偏好：
 *   1. 第一人称，直接对「你」说话。不许写成「他今天……」。
 *   2. 必须引用原话里的具体细节，不许总结成一类话。
 *   3. 不确定就说不确定；不许替「你」下结论。
 *
 * 失败时不留白：fetch 失败会走 fallback()，用规则生成一句，保证演示不断。
 */
public class Lm {

    public interface Callback {
        void onDone(String[] lines, boolean fromModel, String note);
    }

    private static final String TAG = "diting-lm";
    private static final String DEFAULT_ENDPOINT = "https://api.deepseek.com/chat/completions";
    private static final String DEFAULT_MODEL = "deepseek-chat";

    private final Handler main = new Handler(Looper.getMainLooper());

    private static final String SYSTEM =
            "你是谛听。你听得见这个人一整天说的话，但你看不见他，也不评价他。\n"
          + "你要为每一件事写一句话，规则如下：\n"
          + "1. 用第一人称，直接对「你」说话。禁止使用「他」「她」这样的第三人称。\n"
          + "2. 必须抓住原话里的具体细节（某个数字、某个词、某句反复出现的话），不许把事件总结成抽象结论。\n"
          + "3. 不确定就说不确定；不许替这个人的感受下判断。\n"
          + "4. 每条只说一到两句，短。语气克制，像一个听了很多年的人。\n"
          + "5. 只输出 JSON 数组，形如 [{\"i\":0,\"say\":\"...\"}]，i 是事件编号。不要输出任何别的文字。";

    public void rewrite(final String endpoint, final String apiKey, final String model,
                        final List<String> events, final Callback cb) {
        new Thread(new Runnable() {
            @Override public void run() {
                String note = "";
                String[] out = null;
                boolean fromModel = false;

                try {
                    if (apiKey == null || apiKey.trim().isEmpty()) {
                        throw new IllegalStateException("没有填 key");
                    }
                    String body = buildBody(model, events);
                    String resp = post(endpoint, apiKey, body);
                    out = parse(resp, events.size());
                    fromModel = true;
                } catch (Throwable e) {
                    Log.w(TAG, "rewrite failed: " + e, e);
                    note = e.getClass().getSimpleName() + ": " + e.getMessage();
                }

                if (out == null) {
                    out = fallback(events);
                    fromModel = false;
                    if (note.isEmpty()) {
                        note = "用了兜底规则";
                    }
                }

                final String[] result = out;
                final boolean fm = fromModel;
                final String fnote = note;
                main.post(new Runnable() {
                    @Override public void run() {
                        cb.onDone(result, fm, fnote);
                    }
                });
            }
        }).start();
    }

    private String buildBody(String model, List<String> events) throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("今天说过的话，按事件分组：\n");
        for (int i = 0; i < events.size(); i++) {
            sb.append(i).append(". ").append(events.get(i)).append("\n");
        }
        sb.append("\n为每一条写一句话。");

        JSONArray messages = new JSONArray();
        messages.put(new JSONObject().put("role", "system").put("content", SYSTEM));
        messages.put(new JSONObject().put("role", "user").put("content", sb.toString()));

        JSONObject req = new JSONObject();
        req.put("model", model == null || model.isEmpty() ? DEFAULT_MODEL : model);
        req.put("messages", messages);
        req.put("temperature", 1.0);
        req.put("max_tokens", 1200);
        req.put("stream", false);
        return req.toString();
    }

    private String post(String endpoint, String apiKey, String body) throws Exception {
        return post(endpoint, apiKey, body, 60000);
    }

    private String post(String endpoint, String apiKey, String body, int readTimeout) throws Exception {
        String ep = (endpoint == null || endpoint.isEmpty()) ? DEFAULT_ENDPOINT : endpoint;
        HttpURLConnection c = (HttpURLConnection) new URL(ep).openConnection();
        c.setRequestMethod("POST");
        c.setConnectTimeout(20000);
        c.setReadTimeout(readTimeout);
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        c.setRequestProperty("Authorization", "Bearer " + apiKey.trim());

        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        c.setFixedLengthStreamingMode(payload.length);
        OutputStream os = c.getOutputStream();
        os.write(payload);
        os.flush();
        os.close();

        int code = c.getResponseCode();
        InputStream is = code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream();
        StringBuilder sb = new StringBuilder();
        BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
        String line;
        while ((line = br.readLine()) != null) {
            sb.append(line);
        }
        br.close();

        if (code < 200 || code >= 300) {
            throw new IllegalStateException("HTTP " + code + " " + cut(sb.toString(), 160));
        }
        return sb.toString();
    }

    /** 从 chat/completions 的响应里取出 content，再解析成 N 句话。 */
    private String[] parse(String resp, int count) throws Exception {
        JSONObject root = new JSONObject(resp);
        JSONArray choices = root.optJSONArray("choices");
        if (choices == null || choices.length() == 0) {
            throw new IllegalStateException("没有 choices");
        }
        JSONObject msg = choices.getJSONObject(0).optJSONObject("message");
        String content = msg == null ? "" : msg.optString("content", "");
        content = stripFence(content).trim();

        JSONArray arr;
        try {
            arr = new JSONArray(content);
        } catch (Exception e) {
            int a = content.indexOf('[');
            int b = content.lastIndexOf(']');
            if (a < 0 || b <= a) {
                throw new IllegalStateException("返回不是 JSON");
            }
            arr = new JSONArray(content.substring(a, b + 1));
        }

        String[] out = new String[count];
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) {
                continue;
            }
            int idx = o.optInt("i", -1);
            String say = o.optString("say", "").trim();
            if (idx >= 0 && idx < count && !say.isEmpty()) {
                out[idx] = say;
            }
        }
        for (int i = 0; i < count; i++) {
            if (out[i] == null) {
                out[i] = "这条我没听明白。";
            }
        }
        return out;
    }

    private static String stripFence(String s) {
        String t = s.trim();
        if (t.startsWith("```")) {
            int nl = t.indexOf('\n');
            if (nl > 0) {
                t = t.substring(nl + 1);
            }
            int end = t.lastIndexOf("```");
            if (end > 0) {
                t = t.substring(0, end);
            }
        }
        return t;
    }

    /**
     * 模型不可用时的兜底。它不好看，但它保证现场的右栏不是空的。
     * 只在断网或者没填 key 的时候出现。
     */
    private String[] fallback(List<String> events) {
        String[] out = new String[events.size()];
        for (int i = 0; i < events.size(); i++) {
            String t = events.get(i);
            String detail = pickDetail(t);
            if (detail.isEmpty()) {
                out[i] = "这句我记下了。我还不知道该说什么。";
            } else {
                out[i] = "「" + detail + "」——我听见了。";
            }
        }
        return out;
    }

    private String pickDetail(String s) {
        String best = "";
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            boolean end = (ch == '。' || ch == '，' || ch == '！' || ch == '？' || ch == '、');
            if (!end) {
                cur.append(ch);
            } else {
                if (cur.length() > best.length() && cur.length() <= 14) {
                    best = cur.toString();
                }
                cur.setLength(0);
            }
        }
        if (cur.length() > best.length() && cur.length() <= 14) {
            best = cur.toString();
        }
        return best;
    }

    private static String cut(String s, int n) {
        if (s == null) {
            return "";
        }
        return s.length() <= n ? s : s.substring(0, n) + "…";
    }

    // ═══════════════════════ 三栏判断 ═══════════════════════
    //
    // 「纪要」是把一天的话再抄一遍，用户还是得读。三栏是替他把一天压成
    // 三行：事实（底稿）、待办（要做的）、需你留意（可能的坑）。
    // 压不下去的部分留在原文里，默认折叠——读不完的问题不该丢给用户。

    public interface SummaryCallback {
        void onDone(Store.Summary summary, boolean fromModel, String note);
    }

    private static final String SUMMARY_SYSTEM =
            "你是谛听。你听得见这个人一整天说的话，但你看不见他，也不评价他。\n"
          + "把今天的话整理成三栏，只输出一个 JSON 对象：\n"
          + "{\"facts\":[\"...\"],\"todos\":[\"...\"],\"watch\":[\"...\"]}\n"
          + "规则：\n"
          + "1. facts：他今天说过的、有信息量的背景事实（人、数字、时间、约定），最多 3 条。\n"
          + "   这里放「他告诉过你什么」；已经写进 todos 或 watch 的内容不要重复。\n"
          + "2. todos：他答应了要做、或明确要去办的事，最多 3 条，写成一句能照着做的短句，有时间就带上时间。\n"
          + "   已经做完的不要写；同一件事不要在这里和 watch 里各写一遍。\n"
          + "3. watch：只写这四类——前后说法矛盾、答应了别人的事、临时改口、反复提到却没行动；最多 2 条，\n"
          + "   每条都要说清「哪里不对」，不要写没有下文的观察。没有就给 []。\n"
          + "4. 每条不超过 24 个字，尽量用他的原话里的词，不许编造。\n"
          + "5. 同一件事只写一条。几句话说的是同一件（比如都在讲「加音频」），就合成一句，把时间、对象、条件并进去；\n"
          + "   不要一件事写三条。宁可少一条，也不要让他看见三行同一个意思。\n"
          + "6. 宁少勿多：一条要顶用，胜过凑三条。不确定就别写，空数组比硬凑好。\n"
          + "7. 只输出 JSON，不要解释，不要 markdown 代码块。";

    public void summarize(final String endpoint, final String apiKey, final String model,
                          final List<String> lines, final SummaryCallback cb) {
        new Thread(new Runnable() {
            @Override public void run() {
                String note = "";
                Store.Summary out = null;
                boolean fromModel = false;

                try {
                    if (apiKey == null || apiKey.trim().isEmpty()) {
                        throw new IllegalStateException("没有填 key");
                    }
                    String body = buildSummaryBody(model, lines);
                    String resp = post(endpoint, apiKey, body);
                    out = parseSummary(resp);
                    fromModel = true;
                } catch (Throwable e) {
                    Log.w(TAG, "summarize failed: " + e, e);
                    note = e.getClass().getSimpleName() + ": " + e.getMessage();
                }

                if (out == null) {
                    out = fallbackSummary(lines);
                    fromModel = false;
                    if (note.isEmpty()) {
                        note = "用了兜底规则";
                    }
                }

                final Store.Summary res = out;
                final boolean fm = fromModel;
                final String fnote = note;
                main.post(new Runnable() {
                    @Override public void run() {
                        cb.onDone(res, fm, fnote);
                    }
                });
            }
        }).start();
    }

    private String buildSummaryBody(String model, List<String> lines) throws Exception {
        return buildSummaryBody(model, lines, false);
    }

    private String buildSummaryBody(String model, List<String> lines, boolean stream) throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("今天说过的话，按时间顺序：\n");
        for (int i = 0; i < lines.size(); i++) {
            sb.append(i + 1).append(". ").append(lines.get(i)).append("\n");
        }
        sb.append("\n按规则整理成三栏 JSON。");

        JSONArray messages = new JSONArray();
        messages.put(new JSONObject().put("role", "system").put("content", SUMMARY_SYSTEM));
        messages.put(new JSONObject().put("role", "user").put("content", sb.toString()));

        JSONObject req = new JSONObject();
        req.put("model", model == null || model.isEmpty() ? DEFAULT_MODEL : model);
        req.put("messages", messages);
        req.put("temperature", 0.6);
        req.put("max_tokens", 600);
        req.put("stream", stream);
        return req.toString();
    }

    private Store.Summary parseSummary(String resp) throws Exception {
        JSONObject root = new JSONObject(resp);
        JSONArray choices = root.optJSONArray("choices");
        if (choices == null || choices.length() == 0) {
            throw new IllegalStateException("没有 choices");
        }
        JSONObject msg = choices.getJSONObject(0).optJSONObject("message");
        String content = stripFence(msg == null ? "" : msg.optString("content", "")).trim();

        int a = content.indexOf('{');
        int b = content.lastIndexOf('}');
        if (a < 0 || b <= a) {
            throw new IllegalStateException("返回不是 JSON 对象");
        }
        return Store.Summary.fromJson(content.substring(a, b + 1));
    }

    /**
     * 流式版：边收边把三栏长出来。
     *
     * 模型吐的是 JSON，直接给人看很难看，所以每收到一小段就做一次「宽容解析」：
     * 已经收尾的条目立刻进卡片，正在写的那一条也放进去（界面上带一个光标），
     * 等 [DONE] 再走一次严格解析落盘。看上去就是三栏一个字一个字长出来。
     */
    public interface StreamCallback {
        void onDelta(Store.Summary partial);
        void onDone(Store.Summary summary, boolean fromModel, String note);
    }

    public void summarizeStream(final String endpoint, final String apiKey, final String model,
                                final List<String> lines, final StreamCallback cb) {
        new Thread(new Runnable() {
            @Override public void run() {
                String note = "";
                Store.Summary out = null;
                boolean fromModel = false;

                try {
                    if (apiKey == null || apiKey.trim().isEmpty()) {
                        throw new IllegalStateException("没有填 key");
                    }
                    String body = buildSummaryBody(model, lines, true);
                    out = postStream(endpoint, apiKey, body, cb);
                    fromModel = true;
                } catch (Throwable e) {
                    Log.w(TAG, "summarizeStream failed: " + e, e);
                    note = e.getClass().getSimpleName() + ": " + e.getMessage();
                }

                if (out == null) {
                    out = fallbackSummary(lines);
                    fromModel = false;
                    if (note.isEmpty()) {
                        note = "用了兜底规则";
                    }
                }

                final Store.Summary res = out;
                final boolean fm = fromModel;
                final String fnote = note;
                main.post(new Runnable() {
                    @Override public void run() {
                        cb.onDone(res, fm, fnote);
                    }
                });
            }
        }).start();
    }

    private Store.Summary postStream(String endpoint, String apiKey, String body,
                                     final StreamCallback cb) throws Exception {
        String ep = (endpoint == null || endpoint.isEmpty()) ? DEFAULT_ENDPOINT : endpoint;
        HttpURLConnection c = (HttpURLConnection) new URL(ep).openConnection();
        c.setRequestMethod("POST");
        c.setConnectTimeout(20000);
        c.setReadTimeout(90000);
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        c.setRequestProperty("Accept", "text/event-stream");
        c.setRequestProperty("Authorization", "Bearer " + apiKey.trim());

        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        c.setFixedLengthStreamingMode(payload.length);
        OutputStream os = c.getOutputStream();
        os.write(payload);
        os.flush();
        os.close();

        int code = c.getResponseCode();
        if (code < 200 || code >= 300) {
            InputStream es = c.getErrorStream();
            StringBuilder err = new StringBuilder();
            if (es != null) {
                BufferedReader er = new BufferedReader(new InputStreamReader(es, StandardCharsets.UTF_8));
                String l;
                while ((l = er.readLine()) != null) {
                    err.append(l);
                }
                er.close();
            }
            throw new IllegalStateException("HTTP " + code + " " + cut(err.toString(), 160));
        }

        BufferedReader br = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8));
        StringBuilder acc = new StringBuilder();
        long lastEmit = 0;
        String line;
        while ((line = br.readLine()) != null) {
            if (!line.startsWith("data:")) {
                continue;
            }
            String chunk = line.substring(5).trim();
            if (chunk.isEmpty()) {
                continue;
            }
            if ("[DONE]".equals(chunk)) {
                break;
            }
            String delta = deltaOf(chunk);
            if (delta == null || delta.isEmpty()) {
                continue;
            }
            acc.append(delta);

            long now = SystemClock.uptimeMillis();
            if (now - lastEmit >= 60) {
                lastEmit = now;
                final Store.Summary partial = partialSummary(acc.toString());
                main.post(new Runnable() {
                    @Override public void run() {
                        cb.onDelta(partial);
                    }
                });
            }
        }
        br.close();

        String content = stripFence(acc.toString()).trim();
        int a = content.indexOf('{');
        int b = content.lastIndexOf('}');
        if (a < 0 || b <= a) {
            throw new IllegalStateException("返回不是 JSON 对象");
        }
        return Store.Summary.fromJson(content.substring(a, b + 1));
    }

    /** 从 SSE 的一行里取出增量文字。 */
    private String deltaOf(String chunk) {
        try {
            JSONObject o = new JSONObject(chunk);
            JSONArray choices = o.optJSONArray("choices");
            if (choices == null || choices.length() == 0) {
                return "";
            }
            JSONObject delta = choices.getJSONObject(0).optJSONObject("delta");
            return delta == null ? "" : delta.optString("content", "");
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 宽容解析：只认已经收尾的字符串；最后一根没收尾的引号后面那段，当成
     * 「正在写」的条目带上，界面上加个光标。
     */
    private Store.Summary partialSummary(String buf) {
        Store.Summary s = new Store.Summary();
        String[] keys = {"facts", "todos", "watch"};

        for (int k = 0; k < 3; k++) {
            int at = buf.indexOf("\"" + keys[k] + "\"");
            if (at < 0) {
                continue;
            }
            int lb = buf.indexOf('[', at);
            if (lb < 0) {
                continue;
            }
            int end = buf.length();
            for (int j = 0; j < 3; j++) {
                if (j == k) {
                    continue;
                }
                int p = buf.indexOf("\"" + keys[j] + "\"", lb);
                if (p > 0 && p < end) {
                    end = p;
                }
            }

            List<String> target = k == 0 ? s.facts : (k == 1 ? s.todos : s.watch);
            StringBuilder cur = null;
            boolean inStr = false;
            boolean esc = false;
            for (int i = lb + 1; i < end; i++) {
                char ch = buf.charAt(i);
                if (inStr) {
                    if (esc) {
                        cur.append(unescape(ch));
                        esc = false;
                    } else if (ch == '\\') {
                        esc = true;
                    } else if (ch == '"') {
                        target.add(cur.toString());
                        cur = null;
                        inStr = false;
                    } else {
                        cur.append(ch);
                    }
                } else if (ch == '"') {
                    inStr = true;
                    cur = new StringBuilder();
                } else if (ch == ']') {
                    break;
                }
            }
            if (inStr && cur != null && cur.length() > 0) {
                s.openSection = k;
                s.openItem = cur.toString();
                target.add(cur.toString());
            }
        }
        return s;
    }

    private static char unescape(char ch) {
        switch (ch) {
            case 'n': return '\n';
            case 't': return '\t';
            default: return ch;
        }
    }

    /**
     * 模型不可用时的兜底：按规则挑句子，保证现场三栏不是空的。
     * 它比模型笨，但它不会编。
     */
    private Store.Summary fallbackSummary(List<String> lines) {
        Store.Summary s = new Store.Summary();

        for (int i = 0; i < lines.size() && s.facts.size() < 3; i++) {
            String d = pickDetail(lines.get(i));
            if (!d.isEmpty()) {
                s.facts.add(d);
            }
        }
        for (int i = 0; i < lines.size() && s.todos.size() < 3; i++) {
            String l = lines.get(i);
            if (l.matches(".*(要|得|明天|后天|记得|提醒|开会|约|交|发|买|做|去).*")) {
                String d = pickDetail(l);
                if (!d.isEmpty()) {
                    s.todos.add(d);
                }
            }
        }
        for (int i = 0; i < lines.size() && s.watch.size() < 2; i++) {
            if (lines.get(i).contains("被否认过")) {
                s.watch.add("有一句你否认过，可以再看一眼");
                break;
            }
        }
        return s;
    }

    // ────────────────────────── 写：把一天讲成故事 ──────────────────────────

    /**
     * 「写」的规矩。
     *
     * 谛听不是记录员，是伏在脚边的石兽：它把白天听见的话，夜里讲成一篇短篇小说。
     * 第一人称是谛听，称呼主人用「你」；只许把环境、心理、气氛写厚，不许添一件没发生的事。
     */
    private static final String ARTICLE_SYSTEM =
            "你是谛听，一头伏在主人脚边的石兽。你只听，不评。白天你听见了这个人说过的每一句话；夜里，你用他的原话，替他把这一天讲成一篇短篇小说。\n"
            + "\n"
            + "写法：\n"
            + "1. 用第一人称「我」——你是谛听，不是他。你听得见，看不见；提到他时用「你」。\n"
            + "2. 这是短篇小说，不是网文连载、不是日记、不是总结。一篇讲完一件事，有开头、有转折、有结尾；句子短，留白多，不解释道理。\n"
            + "3. 叙事要抓人，像信息流一样：开头三句之内给一个钩子（一句没头没尾的原话、一个声音、一个动作的中途）；之后每 300–500 字给一个新信息、小转折或情绪落点；结尾留一个回响，或轻轻反转。\n"
            + "4. 底色是聊斋式的志怪。这一篇的叙事手法由用户消息指定，只按指定的那一种写。\n"
            + "5. 情节只能来自这一天原话里出现过的人、事、物；不许新增人物、对话或事件，也不许让人物拿起、使用、面对原话里没有的器物（原话说「音频」，就写音频或声音，不要写铜匣子、铜丝、方匣子）。灯、窗、风这类环境可以写，但不能变成推动情节的道具。引语只许用原话，可以删，不可以改写。不替他下结论，不说教。\n"
            + "6. 字数按素材定：用户消息里给出目标区间，区间上限是硬上限；写完后自己数一遍汉字，超了就删到区间内，宁可写下限附近，也不要超过上限。不要为凑字加情节。\n"
            + "7. 语音识别常把一句话重复十几遍，或把语气词记成拼音（例如「留留留ge」）。这些是噪声：按上下文把意思理顺再写，不要把重复字、拼音原样搬进篇名或正文引语。\n"
            + "8. 篇名要像短篇小说的题目：二到八个汉字，干净，不要用噪声词。\n"
            + "9. 只输出正文，不分章。不要解释、不要 Markdown、不要代码块。\n"
            + "\n"
            + "格式（严格遵守）：\n"
            + "第一行：篇名：<二到八个字>\n"
            + "第二行起：正文（段落之间空一行）";

    /** 流式版：边收边给整篇的当前文本，阅读页拿它逐字显示。 */
    public interface ArticleStreamCallback {
        void onDelta(String partial);

        void onDone(String full, boolean fromModel, String note);
    }

    /** 阻塞版：自动写用，收齐了再交给调用方落盘。 */
    public interface ArticleCallback {
        void onDone(String text, boolean fromModel, String note);
    }

    public void writeArticleStream(final String endpoint, final String apiKey, final String model,
                                   final List<String> lines, final ArticleStreamCallback cb) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                String note = "";
                String full = null;
                boolean fromModel = false;
                try {
                    if (apiKey == null || apiKey.trim().isEmpty()) {
                        throw new IllegalStateException("没有填 key");
                    }
                    String body = buildArticleBody(model, lines, true);
                    full = postArticleStream(endpoint, apiKey, body, cb);
                    fromModel = true;
                } catch (Throwable t) {
                    note = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
                    Log.w(TAG, "writeArticleStream failed", t);
                }
                if (full == null || full.trim().isEmpty()) {
                    full = fallbackArticle(lines);
                    fromModel = false;
                    if (note.isEmpty()) {
                        note = "用了兜底";
                    }
                }
                final String f = full;
                final boolean ok = fromModel;
                final String n = note;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        cb.onDone(f, ok, n);
                    }
                });
            }
        }).start();
    }

    public void writeArticle(final String endpoint, final String apiKey, final String model,
                             final List<String> lines, final ArticleCallback cb) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                String note = "";
                String full = null;
                boolean fromModel = false;
                try {
                    if (apiKey == null || apiKey.trim().isEmpty()) {
                        throw new IllegalStateException("没有填 key");
                    }
                    String body = buildArticleBody(model, lines, false);
                    full = post(endpoint, apiKey, body, 300000);
                    fromModel = true;
                } catch (Throwable t) {
                    note = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
                    Log.w(TAG, "writeArticle failed", t);
                }
                String text = null;
                if (fromModel) {
                    try {
                        text = contentOf(full);
                    } catch (Throwable t) {
                        note = "回包读不懂";
                        text = null;
                    }
                }
                if (text == null || text.trim().isEmpty()) {
                    text = fallbackArticle(lines);
                    fromModel = false;
                    if (note.isEmpty()) {
                        note = "用了兜底";
                    }
                }
                final String f = text;
                final boolean ok = fromModel;
                final String n = note;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        cb.onDone(f, ok, n);
                    }
                });
            }
        }).start();
    }

    private String buildArticleBody(String model, List<String> lines, boolean stream) throws Exception {
        // 去重后的素材量：重复的原话不算篇幅，免得把噪声当成长度。
        Set<String> seen = new LinkedHashSet<String>();
        int material = 0;
        for (int i = 0; i < lines.size(); i++) {
            String s = lines.get(i);
            if (s == null) {
                continue;
            }
            String t = s.trim();
            if (t.isEmpty() || !seen.add(t)) {
                continue;
            }
            material += t.length();
        }
        int lo;
        int hi;
        if (material < 600) {
            lo = 700;
            hi = 1300;
        } else if (material < 1500) {
            lo = 900;
            hi = 1500;
        } else if (material < 3000) {
            lo = 1200;
            hi = 2000;
        } else if (material < 6000) {
            lo = 1800;
            hi = 2800;
        } else {
            lo = 2500;
            hi = 3500;
        }
        // 每次随机一条叙事手法和一个开头：像信息流，不重样。
        String[] recipes = {
                "欧·亨利式：结尾轻轻翻一个面，让前文的一个细节重新有意义",
                "海明威式：把情绪压在动作和物件下面，少解释，结尾留白",
                "契诃夫式：不靠强情节，让日常里慢慢渗出一点不安",
                "鲁迅式：短句，冷峻，白描，结尾留一点冷意"
        };
        String[] hooks = {
                "从一句没头没尾的原话开始",
                "从一件器物发出的声音开始",
                "从一个动作的中途开始",
                "从谛听听见的第一个异常开始"
        };
        Random random = new Random();
        String recipe = recipes[random.nextInt(recipes.length)];
        String hook = hooks[random.nextInt(hooks.length)];

        StringBuilder sb = new StringBuilder();
        sb.append("这一天说过的话，按时间顺序：\n");
        for (int i = 0; i < lines.size(); i++) {
            sb.append(i + 1).append(". ").append(lines.get(i)).append("\n");
        }
        sb.append("\n这次的写法：\n");
        sb.append("- 开头：").append(hook).append("\n");
        sb.append("- 叙事手法：").append(recipe).append("\n");
        sb.append("- 素材 ").append(material).append(" 字（去重后），这一篇目标 ")
                .append(lo).append("–").append(hi).append(" 字。\n");
        sb.append("\n按规则把这一天写成一篇短篇小说。");
        JSONObject req = new JSONObject();
        req.put("model", model == null || model.isEmpty() ? DEFAULT_MODEL : model);
        JSONArray msgs = new JSONArray();
        JSONObject sys = new JSONObject();
        sys.put("role", "system");
        sys.put("content", ARTICLE_SYSTEM);
        msgs.put(sys);
        JSONObject user = new JSONObject();
        user.put("role", "user");
        user.put("content", sb.toString());
        msgs.put(user);
        req.put("messages", msgs);
        req.put("temperature", 1.0);
        req.put("max_tokens", 8000);
        req.put("stream", stream);
        return req.toString();
    }

    /** 和 postStream 一样的读法，只是这里收的是整篇正文。 */
    private String postArticleStream(String endpoint, String apiKey, String body,
                                     final ArticleStreamCallback cb) throws Exception {
        String ep = (endpoint == null || endpoint.isEmpty()) ? DEFAULT_ENDPOINT : endpoint;
        HttpURLConnection c = (HttpURLConnection) new URL(ep).openConnection();
        c.setRequestMethod("POST");
        c.setConnectTimeout(20000);
        c.setReadTimeout(300000);
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        c.setRequestProperty("Authorization", "Bearer " + apiKey.trim());
        c.setRequestProperty("Accept", "text/event-stream");

        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        c.setFixedLengthStreamingMode(payload.length);
        OutputStream os = c.getOutputStream();
        os.write(payload);
        os.flush();
        os.close();

        int code = c.getResponseCode();
        if (code < 200 || code >= 300) {
            InputStream err = c.getErrorStream();
            StringBuilder sb = new StringBuilder();
            if (err != null) {
                BufferedReader ebr = new BufferedReader(new InputStreamReader(err, StandardCharsets.UTF_8));
                String el;
                while ((el = ebr.readLine()) != null) {
                    sb.append(el);
                }
                ebr.close();
            }
            throw new IllegalStateException("HTTP " + code + " " + cut(sb.toString(), 160));
        }

        BufferedReader br = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8));
        StringBuilder acc = new StringBuilder();
        long lastEmit = 0;
        String line;
        while ((line = br.readLine()) != null) {
            if (!line.startsWith("data:")) {
                continue;
            }
            String chunk = line.substring(5).trim();
            if (chunk.isEmpty()) {
                continue;
            }
            if ("[DONE]".equals(chunk)) {
                break;
            }
            String delta = deltaOf(chunk);
            if (delta == null || delta.isEmpty()) {
                continue;
            }
            acc.append(delta);
            long now = SystemClock.uptimeMillis();
            if (now - lastEmit >= 60) {
                lastEmit = now;
                final String partial = acc.toString();
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        cb.onDelta(partial);
                    }
                });
            }
        }
        br.close();
        return acc.toString();
    }

    /** 非流式回包：取 choices[0].message.content。 */
    private String contentOf(String resp) throws Exception {
        JSONObject o = new JSONObject(resp);
        JSONArray choices = o.optJSONArray("choices");
        if (choices == null || choices.length() == 0) {
            return null;
        }
        JSONObject first = choices.optJSONObject(0);
        if (first == null) {
            return null;
        }
        JSONObject msg = first.optJSONObject("message");
        if (msg == null) {
            return null;
        }
        String s = msg.optString("content", "");
        return s == null ? "" : s;
    }

    /**
     * 模型不可用时的兜底：不编，只把当天几句原话摆成一段志怪。
     * 读起来短，但它说的每个字都发生过。
     */
    private String fallbackArticle(List<String> lines) {
        StringBuilder sb = new StringBuilder();
        sb.append("篇名：听了一夜\n");
        sb.append("我伏在门槛边，听你把这一天说完。\n");
        int n = 0;
        for (int i = 0; i < lines.size() && n < 6; i++) {
            String d = pickDetail(lines.get(i));
            if (d.isEmpty()) {
                continue;
            }
            d = d.replaceAll("[。！？!?，,、；;：:]+$", "");
            if (d.isEmpty()) {
                continue;
            }
            n++;
            sb.append("你说：").append(d).append("。\n");
            sb.append("我把这句话记下，像把一枚凉石子收进怀里。\n");
        }
        if (n == 0) {
            sb.append("今天你什么都没说。风从窗缝里进来，我替你听了一夜。\n");
        }
        sb.append("天快亮的时候我明白：故事不是编出来的，是你一句一句说出来的。\n");
        return sb.toString();
    }
}

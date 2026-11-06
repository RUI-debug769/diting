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
import java.util.List;

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
        String ep = (endpoint == null || endpoint.isEmpty()) ? DEFAULT_ENDPOINT : endpoint;
        HttpURLConnection c = (HttpURLConnection) new URL(ep).openConnection();
        c.setRequestMethod("POST");
        c.setConnectTimeout(20000);
        c.setReadTimeout(60000);
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
          + "5. 宁少勿多：一条要顶用，胜过凑三条。不确定就别写，空数组比硬凑好。\n"
          + "6. 只输出 JSON，不要解释，不要 markdown 代码块。";

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
}

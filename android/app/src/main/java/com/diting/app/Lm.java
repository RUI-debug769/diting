package com.diting.app;

import android.os.Handler;
import android.os.Looper;
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
}

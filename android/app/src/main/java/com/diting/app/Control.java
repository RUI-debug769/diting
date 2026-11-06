package com.diting.app;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * 设置页和云端说一句话。
 *
 * 复用 App 本来就连着的那条 19002：连上去写一行 JSON，读一行 JSON 回来就断开。
 * 这条口同时在广播 utterance，所以读的时候要把广播行跳过去，不能拿第一行就当答复。
 */
public class Control {

    public interface Callback {
        void onReply(boolean ok, JSONObject reply, String error);
    }

    public void send(final String host, final int port, final String json, final Callback cb) {
        new Thread(new Runnable() {
            @Override public void run() {
                Socket socket = null;
                try {
                    socket = new Socket();
                    socket.connect(new InetSocketAddress(host.trim(), port), 4000);
                    socket.setSoTimeout(6000);

                    OutputStream out = socket.getOutputStream();
                    out.write((json + "\n").getBytes(StandardCharsets.UTF_8));
                    out.flush();

                    BufferedReader in = new BufferedReader(
                            new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));

                    String line;
                    while ((line = in.readLine()) != null) {
                        JSONObject o;
                        try {
                            o = new JSONObject(line);
                        } catch (Exception bad) {
                            continue;
                        }
                        String type = o.optString("type", "");
                        if ("utterance".equals(type) || "board".equals(type)) {
                            continue;              // 广播行，不是给我的答复
                        }
                        cb.onReply(o.optBoolean("ok", true), o, null);
                        return;
                    }
                    cb.onReply(false, null, "云端没有回话");
                } catch (Exception e) {
                    cb.onReply(false, null, e.getClass().getSimpleName() + " " + e.getMessage());
                } finally {
                    if (socket != null) {
                        try {
                            socket.close();
                        } catch (Exception ignored) {
                        }
                    }
                }
            }
        }, "diting-control").start();
    }
}

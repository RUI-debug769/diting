package com.diting.app;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * 云端事件的长连接。
 *
 * 板子把 PCM 推给云，云在那边切片、比声纹、跑识别，然后把每一句的结果按行推过来：
 * {"type":"utterance","speaker":"me","score":0.86,"text":"…","accepted":true}
 *
 * 这里只管收行、解析、回到主线程。断线就每 3 秒重连一次——现场 WiFi 抖一下不该让
 * 演示停在那里。
 */
public class Cloud {

    public static final int PORT = 19002;

    public interface Callback {
        void onLink(boolean connected);

        void onUtterance(String text, boolean accepted, String speaker, double score);

        /** 板子有没有连上云端。App 自己连上不等于板子在推。 */
        void onBoard(boolean online);

        /** 云端完成的动作（比如用板子录完声纹），直接当一句话给用户看。 */
        void onNotice(String text);

        void onError(String message);
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean running;
    private Socket socket;
    private Thread thread;

    public void start(final String host, final int port, final Callback cb) {
        stop();
        if (host == null || host.trim().isEmpty()) {
            cb.onError("没设云端地址");
            return;
        }
        running = true;
        thread = new Thread(new Runnable() {
            @Override public void run() {
                boolean wasLinked = false;
                while (running) {
                    try {
                        Socket s = new Socket();
                        s.connect(new InetSocketAddress(host.trim(), port), 4000);
                        s.setTcpNoDelay(true);
                        socket = s;
                        wasLinked = true;
                        postLink(cb, true);

                        BufferedReader in = new BufferedReader(
                                new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
                        String line;
                        while (running && (line = in.readLine()) != null) {
                            handle(line, cb);
                        }
                    } catch (Exception e) {
                        if (running && wasLinked) {
                            postError(cb, e.getClass().getSimpleName() + " " + e.getMessage());
                        }
                    } finally {
                        closeQuietly();
                        socket = null;
                    }
                    if (!running) {
                        break;
                    }
                    if (wasLinked) {
                        wasLinked = false;
                        postLink(cb, false);
                    }
                    sleep(3000);
                }
            }
        }, "diting-cloud");
        thread.setDaemon(true);
        thread.start();
    }

    public void stop() {
        running = false;
        closeQuietly();
        Thread t = thread;
        thread = null;
        if (t != null) {
            t.interrupt();
        }
    }

    private void handle(String line, final Callback cb) {
        String text;
        String speaker;
        double score;
        boolean accepted;
        try {
            JSONObject o = new JSONObject(line);
            String type = o.optString("type");
            if ("board".equals(type)) {
                final boolean online = o.optBoolean("online", false);
                main.post(new Runnable() {
                    @Override public void run() {
                        cb.onBoard(online);
                    }
                });
                return;
            }
            if ("notice".equals(type)) {
                final String notice = o.optString("text", "");
                main.post(new Runnable() {
                    @Override public void run() {
                        cb.onNotice(notice);
                    }
                });
                return;
            }
            if (!"utterance".equals(type)) {
                return;
            }
            text = o.optString("text", "");
            speaker = o.optString("speaker", "");
            score = o.optDouble("score", 0);
            accepted = o.optBoolean("accepted", false);
        } catch (Exception e) {
            return;
        }
        final String fText = text;
        final String fSpeaker = speaker;
        final double fScore = score;
        final boolean fAccepted = accepted;
        main.post(new Runnable() {
            @Override public void run() {
                cb.onUtterance(fText, fAccepted, fSpeaker, fScore);
            }
        });
    }

    private void postLink(final Callback cb, final boolean connected) {
        main.post(new Runnable() {
            @Override public void run() {
                cb.onLink(connected);
            }
        });
    }

    private void postError(final Callback cb, final String message) {
        main.post(new Runnable() {
            @Override public void run() {
                cb.onError(message);
            }
        });
    }

    private void closeQuietly() {
        Socket s = socket;
        if (s != null) {
            try {
                s.close();
            } catch (Exception ignored) {
            }
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }
}

package com.diting.app;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.DataInputStream;
import java.net.ServerSocket;
import java.net.Socket;

/**
 * 收板子推过来的音频。
 *
 * 帧格式和板子那边对齐：4 字节小端长度 + PCM（16k/16bit/单声道）。
 * 这一版只做「收下来、算 RMS、报数」——先把链路证明通，再接识别。
 * 真正的识别要从这里取 PCM 喂给 sherpa-onnx，而不是让系统识别器开麦克风，
 * 因为那时麦克风在挂坠上，不在手机上。
 */
public class NetRx {

    public interface Callback {
        void onLink(boolean connected, String peer);
        void onLevel(int rms, long totalBytes);
        void onError(String message);
    }

    private static final String TAG = "diting-net";
    public static final int PORT = 8765;

    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean running = false;
    private ServerSocket server;
    private Callback cb;

    public void start(int port, Callback callback) {
        this.cb = callback;
        if (running) {
            return;
        }
        running = true;

        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    server = new ServerSocket(port);
                    Log.i(TAG, "listening on " + port);
                    while (running) {
                        Socket s = server.accept();
                        handle(s);
                    }
                } catch (Throwable e) {
                    if (running) {
                        Log.w(TAG, "server stop", e);
                        final String msg = e.getClass().getSimpleName() + ": " + e.getMessage();
                        main.post(new Runnable() {
                            @Override public void run() {
                                if (cb != null) cb.onError(msg);
                            }
                        });
                    }
                }
            }
        }, "diting-rx");
        t.start();
    }

    private void handle(final Socket s) {
        final String peer = s.getInetAddress() == null ? "?" : s.getInetAddress().getHostAddress();
        main.post(new Runnable() {
            @Override public void run() {
                if (cb != null) cb.onLink(true, peer);
            }
        });

        long total = 0;
        int lastReport = 0;

        try {
            DataInputStream in = new DataInputStream(s.getInputStream());
            while (running) {
                // 板子发的是小端 4 字节长度。DataInputStream.readInt() 是大端，
                // 直接用它会把 640 读成 0x80020000，于是每帧都被当成超长丢掉。
                int b0 = in.read();
                int b1 = in.read();
                int b2 = in.read();
                int b3 = in.read();
                if ((b0 | b1 | b2 | b3) < 0) {
                    break;
                }
                int len = b0 | (b1 << 8) | (b2 << 16) | (b3 << 24);
                if (len <= 0 || len > 8192) {
                    Log.w(TAG, "bad frame len=" + len);
                    break;
                }
                byte[] buf = new byte[len];
                in.readFully(buf);

                total += len;

                // 16bit 小端：按 short 算 RMS
                long acc = 0;
                int n = len / 2;
                for (int i = 0; i + 1 < len; i += 2) {
                    int lo = buf[i] & 0xFF;
                    int hi = buf[i + 1];
                    short v = (short) ((hi << 8) | lo);
                    acc += (long) v * v;
                }
                final int rms = n > 0 ? (int) Math.sqrt((double) acc / n) : 0;
                final long ft = total;

                if ((int) total - lastReport >= 16000) {
                    lastReport = (int) total;
                    main.post(new Runnable() {
                        @Override public void run() {
                            if (cb != null) cb.onLevel(rms, ft);
                        }
                    });
                }
            }
        } catch (Throwable e) {
            Log.w(TAG, "link closed: " + e);
        } finally {
            try { s.close(); } catch (Throwable ignored) { }
            main.post(new Runnable() {
                @Override public void run() {
                    if (cb != null) cb.onLink(false, peer);
                }
            });
        }
    }

    public void stop() {
        running = false;
        try { if (server != null) server.close(); } catch (Throwable ignored) { }
    }
}

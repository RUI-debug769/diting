package com.diting.app;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * 注册「我的声音」。
 *
 * 对着手机说满 N 秒，这段 PCM 直接推给云端的注册口；云端切掉静音、算一条声纹向量存下来。
 * 手机和挂坠是两只麦克风，但声纹认的是人不是设备——注册完再用挂坠说话，同一个人才过。
 *
 * 云端只在第一次真正「注册」；之后再录，它会先跟你已有的向量比一比，不像就不覆盖，
 * 免得路过的人随便说一句就把你的声纹换掉。
 */
public class Enroll {

    public static final int SAMPLE_RATE = 16000;

    public interface Callback {
        void onDone(boolean ok, String message);
    }

    private final Handler main = new Handler(Looper.getMainLooper());

    public void start(final String host, final int port, final int seconds, final Callback cb) {
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                boolean ok = false;
                String message;
                AudioRecord rec = null;
                Socket socket = null;
                try {
                    socket = new Socket();
                    socket.connect(new InetSocketAddress(host, port), 4000);
                    socket.setTcpNoDelay(true);

                    int min = AudioRecord.getMinBufferSize(
                            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
                    rec = new AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
                            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                            Math.max(min, 16384));
                    if (rec.getState() != AudioRecord.STATE_INITIALIZED) {
                        message = "麦克风打不开";
                    } else {
                        DataOutputStream out = new DataOutputStream(socket.getOutputStream());
                        byte[] buf = new byte[2048];  // 1024 个 16bit 样本
                        rec.startRecording();
                        long until = System.currentTimeMillis() + seconds * 1000L;
                        while (System.currentTimeMillis() < until) {
                            int n = rec.read(buf, 0, buf.length);
                            if (n > 0) {
                                // 云端要的是 4 字节小端长度 + PCM
                                out.writeInt(Integer.reverseBytes(n));
                                out.write(buf, 0, n);
                            }
                        }
                        rec.stop();
                        out.flush();
                        socket.shutdownOutput();

                        BufferedReader in = new BufferedReader(
                                new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                        String line = in.readLine();
                        message = "云端没回话";
                        if (line != null) {
                            JSONObject o = new JSONObject(line);
                            ok = o.optBoolean("ok", false);
                            message = o.optString("message", ok ? "记住了" : "没记住");
                        }
                    }
                } catch (Exception e) {
                    message = e.getClass().getSimpleName() + " " + e.getMessage();
                } finally {
                    if (rec != null) {
                        try {
                            if (rec.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                                rec.stop();
                            }
                        } catch (Exception ignored) {
                        }
                        rec.release();
                    }
                    if (socket != null) {
                        try {
                            socket.close();
                        } catch (Exception ignored) {
                        }
                    }
                }
                final boolean fOk = ok;
                final String fMsg = message;
                main.post(new Runnable() {
                    @Override public void run() {
                        cb.onDone(fOk, fMsg);
                    }
                });
            }
        }, "diting-enroll");
        t.setDaemon(true);
        t.start();
    }
}

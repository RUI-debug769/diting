package com.diting.app;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.util.Log;

import java.util.Random;

/**
 * 声语：石头与木头。
 *
 * 谛听只有一颗灯和一只石兽，不能再长出一张嘴。所以它的声音不是提示音，
 * 是一套器物音：小石子落在木桌上、木头被布包住、木块放稳、石子轻碰。
 *
 * 四条规矩，写在代码里免得以后被改坏：
 *   1. 不出人声、不出词句；
 *   2. 不出音高、不成旋律——每一声都是瞬态，衰减在 300ms 内结束；
 *   3. 不用合成器音色：全部是噪声激励 + 几个非谐和的衰减模态，
 *      也就是一块木头/石头被敲到时真实发生的事；
 *   4. 只在这几个时刻响：记下一句、听见不是你的、三栏写完、被戳。
 *      写的时候、想的时候、断开的时候，都不响。
 *
 * 声音在运行时算出来，不打包 wav：这个工程不编译 res/、不生成 R，
 * 而且一段 0.1 秒的 PCM 只有几 KB，比任何资源管线都轻。
 */
public final class Voice {

    /** 记下了：小石子落在木桌上，干、短、没有尾音。 */
    public static final int TICK = 0;
    /** 不是你：布把木头包住的一声闷响，更低、更暗。 */
    public static final int HUSH = 1;
    /** 写完了：木块放稳，轻轻磕一下，留一点尾。 */
    public static final int SETTLE = 2;
    /** 被戳：极轻的一颗石子。 */
    public static final int POKE = 3;

    private static final String TAG = "DitingVoice";
    private static final int RATE = 44100;
    private static final int COUNT = 4;

    private static Voice instance;

    private final AudioTrack[] tracks = new AudioTrack[COUNT];
    private final boolean[] dead = new boolean[COUNT];
    private boolean enabled = true;

    private Voice() {
    }

    public static synchronized Voice get(Context c) {
        if (instance == null) {
            instance = new Voice();
        }
        return instance;
    }

    public void setEnabled(boolean on) {
        enabled = on;
        if (!on) {
            stopAll();
        }
    }

    public boolean enabled() {
        return enabled;
    }

    /** 播一声。同一时刻只保留这一声，不会叠成一片。 */
    public void play(int id) {
        if (!enabled || id < 0 || id >= COUNT) {
            return;
        }
        AudioTrack t = track(id);
        if (t == null) {
            return;
        }
        try {
            if (t.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) {
                t.stop();
            }
            t.setPlaybackHeadPosition(0);
            t.play();
        } catch (Exception e) {
            Log.w(TAG, "play " + id + " failed: " + e);
        }
    }

    private void stopAll() {
        for (AudioTrack t : tracks) {
            if (t != null) {
                try {
                    t.stop();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private AudioTrack track(int id) {
        if (dead[id]) {
            return null;
        }
        AudioTrack t = tracks[id];
        if (t != null) {
            return t;
        }
        short[] pcm = render(id);
        if (pcm.length == 0) {
            dead[id] = true;
            return null;
        }
        try {
            AudioAttributes attrs = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build();
            AudioFormat fmt = new AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build();
            t = new AudioTrack.Builder()
                    .setAudioAttributes(attrs)
                    .setAudioFormat(fmt)
                    .setBufferSizeInBytes(pcm.length * 2)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build();
            int wrote = t.write(pcm, 0, pcm.length);
            if (wrote != pcm.length) {
                Log.w(TAG, "short write " + wrote + "/" + pcm.length);
            }
            tracks[id] = t;
            return t;
        } catch (Exception e) {
            dead[id] = true;
            Log.w(TAG, "build " + id + " failed: " + e);
            return null;
        }
    }

    // ────────────────────────── 合成 ──────────────────────────

    private static short[] render(int id) {
        switch (id) {
            case TICK:
                return toPcm(stoneOnWood(), 0.40);
            case HUSH:
                return toPcm(mutedWood(), 0.36);
            case SETTLE:
                return toPcm(woodSettle(), 0.46);
            case POKE:
                return toPcm(pebbleTap(), 0.26);
            default:
                return new short[0];
        }
    }

    /** 一颗小石子掉在木桌上：一个很短的噪声起音，加三个非谐和的木头模态。 */
    private static double[] stoneOnWood() {
        double[] b = new double[(int) (0.075 * RATE)];
        Random r = new Random(11);
        grain(b, 0.000, 0.0045, 0.95, 0.30, r);   // 碰撞那一下的暗噪声
        grain(b, 0.000, 0.0016, 0.45, 0.82, r);   // 碎屑，亮而极短
        mode(b, 0.000, 2180, 0.013, 0.40, phase(r));
        mode(b, 0.000, 3340, 0.009, 0.24, phase(r));
        mode(b, 0.000, 4680, 0.006, 0.13, phase(r));
        mode(b, 0.000, 176, 0.030, 0.18, phase(r));   // 桌面的那一点沉
        return b;
    }

    /** 布把木头包住：噪声先被低通压暗，再垫两个很低的木头模态。 */
    private static double[] mutedWood() {
        double[] b = new double[(int) (0.19 * RATE)];
        Random r = new Random(23);
        double a = Math.exp(-2 * Math.PI * 620 / RATE);
        double lp = 0;
        for (int i = 0; i < b.length; i++) {
            double t = i / (double) RATE;
            double env = (1 - Math.exp(-t / 0.020)) * Math.exp(-t / 0.070);
            lp = a * lp + (1 - a) * (r.nextDouble() * 2 - 1);
            b[i] += env * lp * 1.9;
        }
        mode(b, 0.000, 118, 0.060, 0.46, phase(r));
        mode(b, 0.000, 236, 0.036, 0.24, phase(r));
        mode(b, 0.000, 468, 0.020, 0.10, phase(r));
        return b;
    }

    /** 木块放稳：两下轻磕，第二下更轻，最后收一点摩擦。 */
    private static double[] woodSettle() {
        double[] b = new double[(int) (0.34 * RATE)];
        Random r = new Random(37);
        grain(b, 0.000, 0.0060, 0.40, 0.14, r);   // 磕到桌面的那层木头声
        mode(b, 0.000, 196, 0.070, 0.46, phase(r));
        mode(b, 0.000, 473, 0.045, 0.24, phase(r));
        mode(b, 0.000, 915, 0.026, 0.12, phase(r));
        grain(b, 0.062, 0.0050, 0.28, 0.16, r);
        mode(b, 0.062, 208, 0.050, 0.18, phase(r));
        mode(b, 0.062, 508, 0.030, 0.10, phase(r));
        grain(b, 0.100, 0.180, 0.10, 0.80, r);
        return b;
    }

    /** 极轻的一颗石子：只够听见，不够叫醒谁。 */
    private static double[] pebbleTap() {
        double[] b = new double[(int) (0.050 * RATE)];
        Random r = new Random(53);
        grain(b, 0.000, 0.0022, 0.85, 0.72, r);
        mode(b, 0.000, 2750, 0.0055, 0.30, phase(r));
        mode(b, 0.000, 4050, 0.0038, 0.18, phase(r));
        mode(b, 0.000, 1180, 0.0090, 0.12, phase(r));
        return b;
    }

    /** 一个衰减模态：敲击之后，木头/石头自己振的那一下。 */
    private static void mode(double[] b, double start, double freq,
                             double tau, double amp, double phase) {
        int i0 = (int) (start * RATE);
        for (int i = i0; i < b.length; i++) {
            double t = (i - i0) / (double) RATE;
            b[i] += amp * Math.exp(-t / tau)
                    * Math.sin(2 * Math.PI * freq * t + phase);
        }
    }

    /** 起音的那一阵噪声：石子碰到木头的瞬间，不是音，是摩擦。 */
    private static void grain(double[] b, double start, double dur,
                              double amount, double lpCoeff, Random r) {
        int i0 = (int) (start * RATE);
        int n = (int) (dur * RATE);
        double lp = 0;
        for (int i = 0; i < n && i0 + i < b.length; i++) {
            double t = i / (double) RATE;
            lp = lpCoeff * lp + (1 - lpCoeff) * (r.nextDouble() * 2 - 1);
            b[i0 + i] += amount * lp * 3.0 * Math.exp(-t / (dur * 0.35));
        }
    }

    private static double phase(Random r) {
        return r.nextDouble() * Math.PI * 2;
    }

    /** 归一化、两端淡入淡出（防止爆音），再落到 16bit。 */
    private static short[] toPcm(double[] b, double peak) {
        int n = b.length;
        int fadeIn = (int) (0.0004 * RATE);
        int fadeOut = (int) (0.005 * RATE);
        // 先淡再归一化：否则起音正好落在淡入段里，峰值会被削掉。
        for (int i = 0; i < n; i++) {
            if (i < fadeIn) {
                b[i] *= i / (double) fadeIn;
            }
            int rem = n - 1 - i;
            if (rem < fadeOut) {
                b[i] *= rem / (double) fadeOut;
            }
        }
        double max = 0;
        for (double v : b) {
            max = Math.max(max, Math.abs(v));
        }
        if (max < 1e-9) {
            return new short[0];
        }
        double g = peak / max;
        short[] out = new short[n];
        for (int i = 0; i < n; i++) {
            double v = b[i] * g;
            if (v > 0.98) {
                v = 0.98;
            }
            if (v < -0.98) {
                v = -0.98;
            }
            out[i] = (short) Math.round(v * 32767);
        }
        return out;
    }
}

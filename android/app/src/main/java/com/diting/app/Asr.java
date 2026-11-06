package com.diting.app;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;

import java.util.ArrayList;

/**
 * 一次一句的听写。
 *
 * 这一版用系统自带的 SpeechRecognizer：不用 key、不用下模型，今天就能听见。
 * 它内部走哪条路（本地还是云端）由手机厂商决定，不是我们能控制的——
 * 所以它在叙事上只能算「临时手段」，最终要换成 AudioRecord + sherpa-onnx。
 * 接口按这个前提设计：外面只知道 onText / onError，不关心实现。
 */
public class Asr {

    public interface Callback {
        void onPartial(String text);
        void onFinal(String text);
        void onError(String message);
    }

    private final Context ctx;
    private final Handler main = new Handler(Looper.getMainLooper());
    private SpeechRecognizer sr;
    private Callback cb;

    public Asr(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    public boolean available() {
        return SpeechRecognizer.isRecognitionAvailable(ctx);
    }

    public void listen(Callback callback) {
        this.cb = callback;
        main.post(new Runnable() {
            @Override public void run() {
                try {
                    if (sr != null) {
                        sr.destroy();
                    }
                    sr = SpeechRecognizer.createSpeechRecognizer(ctx);
                    sr.setRecognitionListener(new RecognitionListener() {
                        @Override public void onReadyForSpeech(Bundle params) { }
                        @Override public void onBeginningOfSpeech() { }
                        @Override public void onRmsChanged(float rmsdB) { }
                        @Override public void onBufferReceived(byte[] buffer) { }
                        @Override public void onEndOfSpeech() { }

                        @Override public void onError(int error) {
                            if (cb != null) {
                                cb.onError(errName(error));
                            }
                        }

                        @Override public void onResults(Bundle results) {
                            String t = first(results);
                            if (cb != null) {
                                if (t.isEmpty()) {
                                    cb.onError("没听清");
                                } else {
                                    cb.onFinal(t);
                                }
                            }
                        }

                        @Override public void onPartialResults(Bundle partial) {
                            String t = first(partial);
                            if (cb != null && !t.isEmpty()) {
                                cb.onPartial(t);
                            }
                        }

                        @Override public void onEvent(int eventType, Bundle params) { }
                    });

                    Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
                    i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                            RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
                    i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN");
                    i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
                    i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
                    sr.startListening(i);
                } catch (Throwable e) {
                    if (cb != null) {
                        cb.onError(e.getClass().getSimpleName());
                    }
                }
            }
        });
    }

    public void stop() {
        main.post(new Runnable() {
            @Override public void run() {
                if (sr != null) {
                    try { sr.stopListening(); } catch (Throwable ignored) { }
                }
            }
        });
    }

    public void destroy() {
        main.post(new Runnable() {
            @Override public void run() {
                if (sr != null) {
                    try { sr.destroy(); } catch (Throwable ignored) { }
                    sr = null;
                }
            }
        });
    }

    private static String first(Bundle b) {
        if (b == null) {
            return "";
        }
        ArrayList<String> list = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        if (list == null || list.isEmpty()) {
            return "";
        }
        return list.get(0) == null ? "" : list.get(0).trim();
    }

    private static String errName(int code) {
        switch (code) {
            case SpeechRecognizer.ERROR_AUDIO: return "录音出错";
            case SpeechRecognizer.ERROR_CLIENT: return "客户端出错";
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS: return "没有麦克风权限";
            case SpeechRecognizer.ERROR_NETWORK: return "网络不可用";
            case SpeechRecognizer.ERROR_NETWORK_TIMEOUT: return "网络超时";
            case SpeechRecognizer.ERROR_NO_MATCH: return "没听清";
            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY: return "它还在听上一句";
            case SpeechRecognizer.ERROR_SERVER: return "服务端出错";
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT: return "没听到声音";
            default: return "识别失败(" + code + ")";
        }
    }
}

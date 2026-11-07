package com.diting.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 到点以后真正干活的地方。
 *
 * 为什么不在 receiver 里直接写：自动写要跑两件事（三栏 + 一篇文章），
 * 文章两千字起，模型慢慢吐要几分钟。receiver 的 goAsync 只借到很短的时间，
 * 进程随时可能被收走。前台服务把「正在写」摆在通知栏上，系统不会半路杀掉。
 */
public class AutoWriteService extends Service {

    private static final String TAG = "diting-auto";
    private static final int PROGRESS_ID = 4003;
    private static final String PROGRESS_CHANNEL = "diting_auto_progress";

    private PowerManager.WakeLock lock;

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) {
            NotificationChannel ch = new NotificationChannel(
                    PROGRESS_CHANNEL, "正在写", NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(PROGRESS_ID, progress());

        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        lock = pm == null ? null : pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "diting:autowrite");
        if (lock != null) {
            lock.acquire(8 * 60 * 1000L);
        }

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    write();
                } catch (Throwable t) {
                    Log.w(TAG, "auto write failed", t);
                    AutoWrite.notifyDone(AutoWriteService.this,
                            "自动写失败了：" + t.getClass().getSimpleName());
                } finally {
                    if (lock != null && lock.isHeld()) {
                        lock.release();
                    }
                    stopForeground(true);
                    stopSelf();
                }
            }
        }).start();

        return START_NOT_STICKY;
    }

    private Notification progress() {
        Intent open = new Intent(this, MainActivity.class);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pi = PendingIntent.getActivity(this, 4002, open, flags);
        Notification.Builder b = new Notification.Builder(this, PROGRESS_CHANNEL);
        b.setContentTitle("谛听")
                .setContentText("正在写今晚这一篇…")
                .setSmallIcon(android.R.drawable.ic_menu_edit)
                .setOngoing(true)
                .setContentIntent(pi);
        return b.build();
    }

    private void write() {
        final Store store = new Store(this);
        final List<Store.Event> events = store.load();
        if (events == null || events.isEmpty()) {
            return;
        }

        List<String> src = new ArrayList<String>();
        for (Store.Event e : events) {
            String line = e.mine == null ? "" : e.mine;
            if (e.denied) {
                line = line + "（这句被否认过，请换一种说法）";
            }
            src.add(line);
        }

        final AtomicInteger remaining = new AtomicInteger(2);
        final boolean[] summaryOk = {false};
        final int[] need = {0};
        final String[] articleTitle = {""};
        final int[] articleChapters = {0};
        final int[] articleChars = {0};
        final boolean[] articleOk = {false};

        final Runnable done = new Runnable() {
            @Override
            public void run() {
                String msg;
                if (articleTitle[0] != null && !articleTitle[0].isEmpty()) {
                    msg = articleChapters[0] <= 1
                            ? "整理好了 · 今晚写了《" + articleTitle[0] + "》 · " + articleChars[0] + " 字"
                            : "整理好了 · 今晚写了《" + articleTitle[0] + "》共 " + articleChapters[0] + " 章";
                } else if (summaryOk[0]) {
                    msg = need[0] > 0
                            ? "整理好了，今天有 " + need[0] + " 件事要你看"
                            : "整理好了，今天没什么要你看的";
                } else {
                    msg = "模型没连上，用了兜底";
                }
                if (!articleOk[0] && articleTitle[0] != null && !articleTitle[0].isEmpty()) {
                    msg = msg + "（文章是兜底）";
                }
                AutoWrite.notifyDone(AutoWriteService.this, msg);
            }
        };

        new Lm().summarize(store.endpoint(), store.apiKey(), store.model(), src,
                new Lm.SummaryCallback() {
                    @Override
                    public void onDone(Store.Summary s, boolean fromModel, String note) {
                        if (s != null) {
                            store.saveSummary(s);
                        }
                        summaryOk[0] = fromModel;
                        need[0] = s == null ? 0 : s.needCount();
                        if (remaining.decrementAndGet() == 0) {
                            done.run();
                        }
                    }
                });

        new Lm().writeArticle(store.endpoint(), store.apiKey(), store.model(), src,
                new Lm.ArticleCallback() {
                    @Override
                    public void onDone(String text, boolean fromModel, String note) {
                        Store.Article a = Store.Article.parse(text);
                        a.fromModel = fromModel;
                        a.note = fromModel ? "" : (note == null ? "" : note);
                        if (a.hasContent()) {
                            store.saveArticleDay(Store.today(), a);
                            articleTitle[0] = a.title;
                            articleChapters[0] = a.chapters.size();
                            articleChars[0] = a.charCount();
                            articleOk[0] = fromModel;
                        }
                        if (remaining.decrementAndGet() == 0) {
                            done.run();
                        }
                    }
                });
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}

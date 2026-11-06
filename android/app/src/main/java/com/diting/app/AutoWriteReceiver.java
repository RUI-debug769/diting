package com.diting.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.PowerManager;

import java.util.ArrayList;
import java.util.List;

/**
 * 闹钟到点：把当天的话交给模型写一遍，存盘、发通知、排下一天。
 *
 * receiver 默认只有十几秒，所以用 goAsync() 把时间借过来，写完再 finish()。
 */
public class AutoWriteReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(final Context ctx, Intent intent) {
        final PendingResult pr = goAsync();
        final Context app = ctx.getApplicationContext();

        new Thread(new Runnable() {
            @Override
            public void run() {
                PowerManager pm = (PowerManager) app.getSystemService(Context.POWER_SERVICE);
                PowerManager.WakeLock lock = pm == null ? null
                        : pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "diting:autowrite");
                if (lock != null) {
                    lock.acquire(3 * 60 * 1000L);
                }
                try {
                    write(app, pr);
                } catch (Throwable t) {
                    AutoWrite.notifyDone(app, "自动写失败了：" + t.getClass().getSimpleName());
                    AutoWrite.reschedule(app);
                    pr.finish();
                } finally {
                    if (lock != null && lock.isHeld()) {
                        lock.release();
                    }
                }
            }
        }).start();
    }

    private void write(Context app, final PendingResult pr) {
        final Store store = new Store(app);
        final List<Store.Event> events = store.load();
        if (events.isEmpty()) {
            AutoWrite.reschedule(app);
            pr.finish();
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

        new Lm().rewrite(store.endpoint(), store.apiKey(), store.model(), src,
                new Lm.Callback() {
                    @Override
                    public void onDone(String[] lines, boolean fromModel, String note) {
                        int n = 0;
                        for (int i = 0; i < events.size() && i < lines.length; i++) {
                            if (lines[i] != null && !lines[i].trim().isEmpty()) {
                                events.get(i).its = lines[i];
                                n++;
                            }
                        }
                        store.save(events);
                        AutoWrite.notifyDone(app, fromModel
                                ? "今天的话写好了，" + n + " 条"
                                : "模型没连上，用了兜底：" + note);
                        AutoWrite.reschedule(app);
                        pr.finish();
                    }
                });
    }
}

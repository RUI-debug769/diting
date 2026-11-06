package com.diting.app;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import java.util.Calendar;

/**
 * 每天到点自动「让它写」。
 *
 * 用 AlarmManager 的 exact 闹钟而不是 Handler：Handler 只在进程活着时有效，
 * 手机一锁屏被系统杀掉就永远不写。闹钟到点会拉起 receiver，写完自己排下一天。
 */
public final class AutoWrite {

    private static final int REQ = 4001;
    static final String CHANNEL = "diting_auto";

    private AutoWrite() {
    }

    /** 按设置里的时间排下一次；时间是空串就取消。设置页保存、开机、每次写完都会调。 */
    public static void reschedule(Context ctx) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) {
            return;
        }
        PendingIntent pi = pending(ctx);
        am.cancel(pi);

        String at = new Store(ctx).autoAt();
        if (!isTime(at)) {
            return;
        }
        String[] hm = at.split(":");
        Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, Integer.parseInt(hm[0]));
        c.set(Calendar.MINUTE, Integer.parseInt(hm[1]));
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        if (c.getTimeInMillis() <= System.currentTimeMillis()) {
            c.add(Calendar.DAY_OF_YEAR, 1);
        }
        try {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, c.getTimeInMillis(), pi);
        } catch (SecurityException e) {
            // 没给「精准闹钟」权限时退一步：可能晚几分钟，但不会不写
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, c.getTimeInMillis(), pi);
        }
    }

    static boolean isTime(String s) {
        return s != null && s.matches("\\d{1,2}:\\d{2}");
    }

    private static PendingIntent pending(Context ctx) {
        Intent i = new Intent(ctx, AutoWriteReceiver.class);
        i.setAction("com.diting.app.AUTO_WRITE");
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        return PendingIntent.getBroadcast(ctx, REQ, i, flags);
    }

    /** 写完发一条通知，用户不用打开 App 也知道今天写过了。 */
    static void notifyDone(Context ctx, String text) {
        try {
            NotificationManager nm =
                    (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) {
                return;
            }
            if (Build.VERSION.SDK_INT >= 26) {
                NotificationChannel ch = new NotificationChannel(
                        CHANNEL, "每天自动写", NotificationManager.IMPORTANCE_LOW);
                nm.createNotificationChannel(ch);
            }
            Intent open = new Intent(ctx, MainActivity.class);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 23) {
                flags |= PendingIntent.FLAG_IMMUTABLE;
            }
            PendingIntent pi = PendingIntent.getActivity(ctx, 4002, open, flags);

            Notification.Builder b = Build.VERSION.SDK_INT >= 26
                    ? new Notification.Builder(ctx, CHANNEL)
                    : new Notification.Builder(ctx);
            b.setContentTitle("谛听")
                    .setContentText(text)
                    .setSmallIcon(android.R.drawable.ic_menu_edit)
                    .setAutoCancel(true)
                    .setContentIntent(pi);
            nm.notify(4002, b.build());
        } catch (Throwable ignored) {
            // 通知失败不影响已经写好的内容
        }
    }
}

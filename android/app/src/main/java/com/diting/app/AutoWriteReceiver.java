package com.diting.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/**
 * 闹钟到点：把活交给前台服务，排下一天，然后立刻结束。
 *
 * receiver 借到的时间很短，而文章两千字起、要写几分钟。
 * 这里不再自己写，只负责把 AutoWriteService 拉起来。
 */
public class AutoWriteReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context ctx, Intent intent) {
        Context app = ctx.getApplicationContext();
        Intent svc = new Intent(app, AutoWriteService.class);
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                app.startForegroundService(svc);
            } else {
                app.startService(svc);
            }
        } catch (Throwable t) {
            AutoWrite.notifyDone(app, "自动写没起来：" + t.getClass().getSimpleName());
        }
        AutoWrite.reschedule(app);
    }
}

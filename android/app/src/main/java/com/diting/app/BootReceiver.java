package com.diting.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** 开机／应用更新后闹钟会被清掉，这里重新排一次。 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context ctx, Intent intent) {
        String a = intent == null ? null : intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(a)
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(a)
                || "android.intent.action.QUICKBOOT_POWERON".equals(a)) {
            AutoWrite.reschedule(ctx);
        }
    }
}

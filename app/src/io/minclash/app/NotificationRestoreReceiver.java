package io.minclash.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** System broadcasts may restore the user's enabled foreground controls without opening the UI. */
public final class NotificationRestoreReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())
            || Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction())) {
            ProxyNotificationService.restore(context);
        }
    }
}

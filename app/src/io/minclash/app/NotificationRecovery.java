package io.minclash.app;

import android.content.Context;
import android.os.SystemClock;
import android.util.Log;

/** Persist only the user's opt-in in the module; legacy modules keep their existing behavior. */
final class NotificationRecovery {
    private static boolean pending;
    private static long retryAfter;

    static synchronized void sync(Context context, Module.Status status) {
        if (status == null || !status.runtime.notificationRecovery()) return;
        boolean desired = ProxyNotificationService.enabled(context);
        if (desired == status.runtime.notificationEnabled() || pending || SystemClock.elapsedRealtime() < retryAfter) return;
        pending = true;
        RoutingStore.WORKER.execute(() -> {
            try {
                Module.run(desired ? Module.Command.NOTIFICATION_ON : Module.Command.NOTIFICATION_OFF, null);
            } catch (Exception error) {
                Log.w("MinClash", "Cannot synchronize notification recovery preference", error);
            } finally {
                synchronized (NotificationRecovery.class) {
                    pending = false;
                    retryAfter = SystemClock.elapsedRealtime() + 15000;
                }
            }
        });
    }
    private NotificationRecovery() {}
}

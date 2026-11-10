package io.minclash.app;

import android.app.AppOpsManager;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Process;
import java.util.concurrent.TimeUnit;

final class ForegroundApp {
    private final Context context;
    private long lastQuery;
    private long lastEvent;
    private String packageName;
    private String label;

    ForegroundApp(Context context) { this.context = context; }

    static boolean allowed(Context context) {
        AppOpsManager ops = context.getSystemService(AppOpsManager.class);
        return ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.getPackageName())
            == AppOpsManager.MODE_ALLOWED;
    }

    String label() {
        if (!allowed(context)) {
            lastQuery = 0;
            lastEvent = 0;
            packageName = null;
            label = null;
            return "当前应用 · 未授权";
        }
        long now = System.currentTimeMillis();
        long lookback = now - TimeUnit.HOURS.toMillis(24);
        if (lastQuery > now) lastEvent = 0;
        long start = lastQuery > 0 && lastQuery <= now ? Math.max(lookback, lastQuery - 5000) : lookback;
        UsageEvents events = context.getSystemService(UsageStatsManager.class).queryEvents(start, now);
        if (events == null) return "当前应用 · 未知";
        lastQuery = now;
        UsageEvents.Event event = new UsageEvents.Event();
        String foreground = packageName;
        while (events.hasNextEvent() && events.getNextEvent(event)) {
            // MOVE_TO_FOREGROUND is the API 28 name for ACTIVITY_RESUMED.
            if (event.getEventType() == UsageEvents.Event.MOVE_TO_FOREGROUND
                && event.getTimeStamp() >= lastEvent
                && !ProxyOverlayLauncher.class.getName().equals(event.getClassName())
                && !"com.android.systemui".equals(event.getPackageName())) {
                foreground = event.getPackageName();
                lastEvent = event.getTimeStamp();
            }
        }
        if (foreground != null && !foreground.equals(packageName)) {
            packageName = foreground;
            try {
                PackageManager manager = context.getPackageManager();
                label = manager.getApplicationLabel(manager.getApplicationInfo(packageName, 0)).toString();
            } catch (PackageManager.NameNotFoundException error) {
                label = null;
            }
        }
        return label == null ? "当前应用 · 未知" : label;
    }
}

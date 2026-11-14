package io.minclash.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import java.util.Set;

final class SubscriptionReminders {
    static final int JOB_ID = 103;
    static final String CHANNEL = "subscription_expiry";
    static final String ACTION_OPEN = "io.minclash.app.OPEN_SUBSCRIPTION_USAGE";
    static final String EXTRA_KEY = "subscription_key";
    private static final String TAG_PREFIX = "subscription_expiry_";
    private static final String SCHEDULED = "subscription_reminder_scheduled";
    private static final int NOTIFICATION_ID = 103;

    static SharedPreferences preferences(Context context) { return context.getSharedPreferences("MainActivity", Context.MODE_PRIVATE); }
    static String key(String address) { return NodeList.favoritesKey(address).replace("node_favorites_", ""); }
    private static String receiptKey(String address) { return "subscription_reminder_sent_" + key(address); }
    private static String attemptKey(String address) { return "subscription_reminder_attempt_" + key(address); }

    static void createChannel(Context context) {
        NotificationChannel channel = new NotificationChannel(CHANNEL, "订阅到期提醒", NotificationManager.IMPORTANCE_DEFAULT);
        channel.setDescription("当前订阅临近到期或已到期时提醒，不依赖 TUN 和通知栏控制。");
        context.getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    static boolean canShow(Context context) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        NotificationChannel channel = manager.getNotificationChannel(CHANNEL);
        return manager.areNotificationsEnabled() && (channel == null || channel.getImportance() != NotificationManager.IMPORTANCE_NONE);
    }

    static String summary(Context context, SharedPreferences prefs, SubscriptionUsage usage) {
        if (!SubscriptionPreferences.enabled(prefs)) return "到期提醒已关闭 · 显示与提醒";
        if (SubscriptionPreferences.days(prefs).isEmpty()) return "尚未选择提醒时间 · 显示与提醒";
        if (!canShow(context)) return "通知权限未开启 · 点此设置";
        if (usage.expire() < 0) return "有效期未提供，暂时无法安排提醒";
        if (!prefs.getBoolean(SCHEDULED, true)) return "后台提醒未能安排 · 点此重试";
        return "到期前 " + SubscriptionPreferences.daysLabel(prefs) + " 天提醒";
    }

    static boolean schedule(Context context, long delay) {
        SharedPreferences prefs = preferences(context);
        android.app.job.JobScheduler scheduler = context.getSystemService(android.app.job.JobScheduler.class);
        if (delay < 0 || !SubscriptionPreferences.enabled(prefs) || SubscriptionPreferences.days(prefs).isEmpty()) {
            scheduler.cancel(JOB_ID); cancelOther(context, null); return true;
        }
        boolean scheduled;
        try {
            long latency = Math.max(1_000, delay);
            JobInfo job = new JobInfo.Builder(JOB_ID, new ComponentName(context, SubscriptionReminderJob.class))
                .setMinimumLatency(latency).setOverrideDeadline(latency + 3_600_000)
                .setPersisted(true).build();
            scheduled = scheduler.schedule(job) == android.app.job.JobScheduler.RESULT_SUCCESS;
        } catch (RuntimeException unavailable) { scheduled = false; }
        prefs.edit().putBoolean(SCHEDULED, scheduled).apply();
        return scheduled;
    }

    static void request(Context context) { schedule(context.getApplicationContext(), 1_000); }

    /** Called on the routing worker after a catalog, preference, or metadata change. */
    static void reconcile(Context context, SubscriptionCatalog.Snapshot catalog) {
        try { schedule(context, evaluate(context, catalog)); }
        catch (RuntimeException unavailable) { preferences(context).edit().putBoolean(SCHEDULED, false).apply(); }
    }

    static long evaluate(Context context, SubscriptionCatalog.Snapshot catalog) {
        SharedPreferences prefs = preferences(context);
        Set<Integer> days = SubscriptionPreferences.days(prefs);
        SubscriptionCatalog.Entry entry = catalog.active();
        if (!SubscriptionPreferences.enabled(prefs) || days.isEmpty() || entry == null) {
            cancelOther(context, null); return -1;
        }
        String tag = TAG_PREFIX + key(entry.address());
        cancelOther(context, tag);
        SubscriptionUsage usage = SubscriptionUsage.load(prefs, entry.address());
        long now = System.currentTimeMillis();
        int stage = SubscriptionReminderPolicy.stage(usage.expire(), now, days);
        if (stage < 0) context.getSystemService(NotificationManager.class).cancel(tag, NOTIFICATION_ID);
        // Refresh old snapshots before alerting; an offline attempt permits a clearly dated cached reminder.
        if (!refreshDue(prefs, entry.address(), now) && canShow(context)
            && SubscriptionReminderPolicy.shouldNotify(usage.expire(), stage, prefs.getString(receiptKey(entry.address()), ""))) {
            createChannel(context);
            Intent open = new Intent(context, MainActivity.class).setAction(ACTION_OPEN)
                .setData(Uri.parse("minclash://subscription/" + key(entry.address())))
                .putExtra(EXTRA_KEY, key(entry.address())).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            PendingIntent content = PendingIntent.getActivity(context, 0, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            String title = entry.name() + " · " + usage.expiryLabel(now);
            String body = usage.expiryDate() + "。" + (stage == 0 ? "请续费后刷新订阅信息。" : "请提前续费。")
                + "\n依据" + usage.freshness(now) + "，若已续费请刷新。";
            Notification notification = new Notification.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title).setContentText(body).setStyle(new Notification.BigTextStyle().bigText(body))
                .setContentIntent(content).setAutoCancel(true).setCategory(Notification.CATEGORY_REMINDER)
                .setOnlyAlertOnce(false).build();
            try {
                context.getSystemService(NotificationManager.class).notify(tag, NOTIFICATION_ID, notification);
                prefs.edit().putString(receiptKey(entry.address()), usage.expire() + ":" + stage).commit();
            } catch (SecurityException denied) { /* Permission can be revoked between the check and notify. */ }
        }
        long delay = SubscriptionReminderPolicy.nextDelay(usage.expire(), now, days);
        long last = Math.max(usage.checkedAt(), prefs.getLong(attemptKey(entry.address()), 0));
        long age = Math.max(0, now - last);
        return Math.min(delay, Math.max(1_000, SubscriptionReminderPolicy.CHECK_INTERVAL - age));
    }

    static boolean refreshDue(SharedPreferences prefs, String address, long now) {
        long last = Math.max(SubscriptionUsage.load(prefs, address).checkedAt(), prefs.getLong(attemptKey(address), 0));
        return last == 0 || now < last || now - last >= SubscriptionReminderPolicy.CHECK_INTERVAL;
    }
    static void markAttempt(SharedPreferences prefs, String address, long now) { prefs.edit().putLong(attemptKey(address), now).apply(); }

    private static void cancelOther(Context context, String keep) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        for (android.service.notification.StatusBarNotification active : manager.getActiveNotifications()) {
            String tag = active.getTag();
            if (tag != null && tag.startsWith(TAG_PREFIX) && !tag.equals(keep)) manager.cancel(tag, active.getId());
        }
    }
}

package io.minclash.app;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Build;
import java.util.Objects;

final class ProxyNotificationChannels {
    static String selected(Context context) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager.getNotificationChannel(NotificationChannelPolicy.PRIORITY_CONTROLS) != null) return NotificationChannelPolicy.PRIORITY_CONTROLS;
        NotificationChannel controls = manager.getNotificationChannel(NotificationChannelPolicy.CONTROLS);
        if (controls != null) return NotificationChannelPolicy.select(false, Build.VERSION.SDK_INT, previous(controls), null);
        NotificationChannel legacy = manager.getNotificationChannel(NotificationChannelPolicy.LEGACY);
        return NotificationChannelPolicy.select(false, Build.VERSION.SDK_INT, null, legacy == null ? null : previous(legacy));
    }

    private static NotificationChannelPolicy.Previous previous(NotificationChannel previous) {
        NotificationChannel baseline = defaultChannel(previous.getId());
        long[] pattern = previous.getVibrationPattern();
        return new NotificationChannelPolicy.Previous(
            previous.getImportance(), Build.VERSION.SDK_INT >= 29 && previous.hasUserSetImportance(),
            Build.VERSION.SDK_INT >= 30 && previous.hasUserSetSound(),
            !Objects.equals(previous.getSound(), baseline.getSound()) || previous.shouldVibrate() != baseline.shouldVibrate()
                || (pattern != null && pattern.length != 0)
                || previous.shouldShowLights() != baseline.shouldShowLights() || previous.canBypassDnd() != baseline.canBypassDnd()
                || previous.getLockscreenVisibility() != baseline.getLockscreenVisibility()
                || previous.getGroup() != null || previous.canShowBadge() != baseline.canShowBadge());
    }

    static String ensure(Context context) {
        String id = selected(context);
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager.getNotificationChannel(id) == null) manager.createNotificationChannel(defaultChannel(id));
        return id;
    }

    private static NotificationChannel defaultChannel(String id) {
        int importance = switch (id) {
            case NotificationChannelPolicy.LEGACY -> NotificationManager.IMPORTANCE_LOW;
            case NotificationChannelPolicy.CONTROLS -> NotificationManager.IMPORTANCE_DEFAULT;
            default -> NotificationManager.IMPORTANCE_HIGH;
        };
        String name = id.equals(NotificationChannelPolicy.PRIORITY_CONTROLS)
            ? "代理状态与快捷控制（优先显示）" : "代理状态与快捷控制";
        NotificationChannel channel = new NotificationChannel(id, name, importance);
        if (!id.equals(NotificationChannelPolicy.LEGACY)) {
            channel.setDescription("静音显示连接、网速与快捷控制。刷新的通知时间保持稳定。");
            channel.setSound(null, null);
            channel.enableVibration(false);
            channel.enableLights(false);
            channel.setShowBadge(false);
        }
        return channel;
    }

    private ProxyNotificationChannels() {}
}

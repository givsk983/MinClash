package io.minclash.app;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Build;
import java.util.Objects;

final class ProxyNotificationChannels {
    static String selected(Context context) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager.getNotificationChannel(NotificationChannelPolicy.CONTROLS) != null) return NotificationChannelPolicy.CONTROLS;
        NotificationChannel previous = manager.getNotificationChannel(NotificationChannelPolicy.LEGACY);
        NotificationChannel baseline = new NotificationChannel(NotificationChannelPolicy.LEGACY, "代理状态与快捷控制", NotificationManager.IMPORTANCE_LOW);
        NotificationChannelPolicy.Previous settings = previous == null ? null : new NotificationChannelPolicy.Previous(
            previous.getImportance(), Build.VERSION.SDK_INT >= 29 && previous.hasUserSetImportance(),
            Build.VERSION.SDK_INT >= 30 && previous.hasUserSetSound(),
            !Objects.equals(previous.getSound(), baseline.getSound()) || previous.shouldVibrate() != baseline.shouldVibrate()
                || previous.shouldShowLights() != baseline.shouldShowLights() || previous.canBypassDnd() != baseline.canBypassDnd()
                || previous.getLockscreenVisibility() != baseline.getLockscreenVisibility()
                || previous.getGroup() != null || previous.canShowBadge() != baseline.canShowBadge());
        return NotificationChannelPolicy.select(false, Build.VERSION.SDK_INT, settings);
    }

    static String ensure(Context context) {
        String id = selected(context);
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager.getNotificationChannel(id) != null) return id;
        NotificationChannel channel = new NotificationChannel(id, "代理状态与快捷控制", NotificationManager.IMPORTANCE_DEFAULT);
        channel.setDescription("静音显示连接、网速与快捷控制；刷新不改变通知时间，显示顺序由系统决定。");
        channel.setSound(null, null);
        channel.enableVibration(false);
        channel.enableLights(false);
        channel.setShowBadge(false);
        manager.createNotificationChannel(channel);
        return id;
    }

    private ProxyNotificationChannels() {}
}

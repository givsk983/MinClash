package io.minclash.app;

import android.app.PendingIntent;
import android.widget.RemoteViews;

final class ProxyNotificationCard {
    private final ProxyNotificationService service;

    ProxyNotificationCard(ProxyNotificationService service) { this.service = service; }

    RemoteViews view(String app, String mode, String node, ProxyNotificationService.Speed speed,
                     PendingIntent modePicker, PendingIntent nodePicker) {
        RemoteViews view = new RemoteViews(service.getPackageName(), R.layout.proxy_notification_header);
        view.setTextViewText(R.id.notification_app, app);
        view.setTextViewText(R.id.notification_mode, mode + " ›");
        view.setTextViewText(R.id.notification_node, node);
        view.setTextViewText(R.id.notification_down, "↓ " + speed.down());
        view.setTextViewText(R.id.notification_up, "↑ " + speed.up());
        view.setContentDescription(R.id.notification_mode_target, "切换代理模式，" + mode + "，当前应用 " + app);
        view.setContentDescription(R.id.notification_node_target, "选择代理节点，" + node
            + "，下载 " + speed.down() + "，上传 " + speed.up());
        view.setOnClickPendingIntent(R.id.notification_mode_target, modePicker);
        view.setOnClickPendingIntent(R.id.notification_node_target, nodePicker);
        return view;
    }
}

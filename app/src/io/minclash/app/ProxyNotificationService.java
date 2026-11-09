package io.minclash.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class ProxyNotificationService extends Service {
    static final String ACTION_PICK_NODE = "io.minclash.app.PICK_NODE";
    private static final String CHANNEL = "proxy_status";
    private static final int NOTIFICATION_ID = 1;
    private static boolean started;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Runnable poll = this::pollStatus;
    private NotificationManager manager;
    private Status last;
    private int inactivePolls;

    private record Status(String title, String detail, boolean canSwitch, boolean inactive) {}

    static boolean canShow(Context context) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        NotificationChannel channel = manager.getNotificationChannel(CHANNEL);
        return manager.areNotificationsEnabled() && (channel == null || channel.getImportance() != NotificationManager.IMPORTANCE_NONE);
    }

    static void sync(Context context, boolean enabled) {
        Intent intent = new Intent(context, ProxyNotificationService.class);
        if (enabled && !started && canShow(context)) {
            try {
                context.startForegroundService(intent);
                started = true;
            } catch (IllegalStateException | SecurityException error) {
                Log.w("MinClash", "Cannot start proxy status notification", error);
            }
        } else if (!enabled && started) {
            context.stopService(intent);
        }
    }

    @Override public void onCreate() {
        super.onCreate();
        manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(CHANNEL, "代理状态与节点切换", NotificationManager.IMPORTANCE_LOW));
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (last == null) {
            last = new Status("MinClash", "正在读取代理状态…", false, false);
            startForeground(NOTIFICATION_ID, notification(last));
            main.post(poll);
        }
        return START_NOT_STICKY;
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public void onDestroy() {
        main.removeCallbacks(poll);
        worker.shutdownNow();
        stopForeground(STOP_FOREGROUND_REMOVE);
        started = false;
        super.onDestroy();
    }

    private void pollStatus() {
        if (!canShow(this)) { stopSelf(); return; }
        worker.execute(() -> {
            Status status = readStatus();
            main.post(() -> {
                if (worker.isShutdown()) return;
                inactivePolls = status.inactive() ? inactivePolls + 1 : 0;
                if (inactivePolls >= 2) { stopSelf(); return; }
                if (!status.equals(last)) {
                    manager.notify(NOTIFICATION_ID, notification(status));
                    last = status;
                }
                main.postDelayed(poll, 5000);
            });
        });
    }

    private static Status readStatus() {
        try {
            Module.Status status = Module.status();
            if (!status.installed() || !status.enabled || status.state.equals("stopped")) {
                return new Status("MinClash · TUN 已停止", "", false, true);
            }
            if (!status.state.equals("running")) {
                String detail = switch (status.state) {
                    case "starting" -> "正在建立 TUN";
                    case "retrying" -> "正在等待重连";
                    default -> "请在 App 中检查连接";
                };
                return new Status("MinClash · TUN 未就绪", detail, false, false);
            }
            if (!status.control) return new Status("MinClash · TUN 运行中", "节点控制不可用", false, false);
            try {
                Controller.Mode mode = Controller.mode();
                String detail = mode == Controller.Mode.DIRECT ? "直连模式"
                    : mode.label + "模式 · " + Controller.primaryNode(mode);
                return new Status("MinClash · TUN 运行中", detail, true, false);
            } catch (Exception error) {
                return new Status("MinClash · TUN 运行中", "暂时无法读取当前节点", true, false);
            }
        } catch (Exception error) {
            return new Status("MinClash · 状态暂不可用", "请在 App 中检查连接", false, false);
        }
    }

    private Notification notification(Status status) {
        Notification.Builder builder = new Notification.Builder(this, CHANNEL)
            .setSmallIcon(getResources().getIdentifier("ic_notification", "drawable", getPackageName()))
            .setContentTitle(status.title()).setContentText(status.detail())
            .setStyle(new Notification.BigTextStyle().bigText(status.detail()))
            .setContentIntent(openApp(false)).setShowWhen(false).setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE).setVisibility(Notification.VISIBILITY_PRIVATE).setOngoing(true);
        if (Build.VERSION.SDK_INT >= 31) builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        if (status.canSwitch()) builder.addAction(new Notification.Action.Builder(null, "切换节点", openApp(true)).build());
        return builder.build();
    }

    private PendingIntent openApp(boolean pickNode) {
        Intent intent = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        if (pickNode) intent.setAction(ACTION_PICK_NODE);
        return PendingIntent.getActivity(this, pickNode ? 1 : 0, intent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }
}

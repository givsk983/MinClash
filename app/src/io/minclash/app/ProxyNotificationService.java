package io.minclash.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.hardware.display.DisplayManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.Display;
import android.view.WindowManager;
import android.widget.Toast;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class ProxyNotificationService extends Service {
    static final String ACTION_OPEN_PANEL = "io.minclash.app.OPEN_PROXY_PANEL";
    static final String EXTRA_MODE_PANEL = "mode_panel";
    private static final String CHANNEL = "proxy_status";
    private static final int NOTIFICATION_ID = 1;
    private static final long SAMPLE_INTERVAL = 3000;
    private static final long CONNECTION_INTERVAL = 9000;
    private static boolean started;
    private static volatile boolean refreshConnection;

    static void connectionChanged() { refreshConnection = true; }

    private record Connection(String mode, String node, boolean running, boolean inactive) {}

    // Compare the text that System UI will show, so rounded samples don't cause reposts.
    record Speed(String down, String up) {
        static final Speed UNKNOWN = new Speed("—", "—");

        static Speed measured(long down, long up) {
            return new Speed(rate(down), rate(up));
        }

        private static String rate(long bytes) {
            if (bytes >= 1024 * 1024) return String.format(Locale.ROOT, "%.1f MB/s", bytes / (1024.0 * 1024));
            if (bytes >= 1024) return String.format(Locale.ROOT, "%.0f KB/s", bytes / 1024.0);
            return bytes + " B/s";
        }
    }

    private record Sample(Connection connection, String app, Speed speed, boolean overlayAllowed) {}
    private record CounterSample(Module.TunCounters counters, long time) {}

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Runnable poll = this::pollStatus;
    private NotificationManager manager;
    private ForegroundApp foregroundApp;
    private Sample current;
    private ProxyQuickControls panel;
    private long notificationSince;
    private int inactivePolls;
    // Owned by the worker.
    private Connection sampledConnection;
    private long connectionTime;
    private CounterSample previousCounters;

    static boolean canShow(Context context) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        NotificationChannel channel = manager.getNotificationChannel(CHANNEL);
        return manager.areNotificationsEnabled() && (channel == null || channel.getImportance() != NotificationManager.IMPORTANCE_NONE);
    }

    static Intent overlaySettings(Context context) {
        return new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.fromParts("package", context.getPackageName(), null));
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
        manager.createNotificationChannel(new NotificationChannel(CHANNEL, "代理状态与快捷控制", NotificationManager.IMPORTANCE_LOW));
        foregroundApp = new ForegroundApp(this);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_OPEN_PANEL.equals(intent.getAction())) {
            if (current == null) stopSelf(startId);
            else showPanel(intent.getBooleanExtra(EXTRA_MODE_PANEL, false));
            return START_NOT_STICKY;
        }
        if (current == null) {
            notificationSince = System.currentTimeMillis();
            current = new Sample(new Connection("代理模式", "正在读取连接…", false, false), "MinClash",
                Speed.UNKNOWN, Settings.canDrawOverlays(this));
            startForeground(NOTIFICATION_ID, notification());
            main.post(poll);
        }
        return START_NOT_STICKY;
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public void onDestroy() {
        main.removeCallbacksAndMessages(null);
        closePanel();
        worker.shutdownNow();
        stopForeground(STOP_FOREGROUND_REMOVE);
        started = false;
        super.onDestroy();
    }

    private void pollStatus() {
        if (!canShow(this)) { stopSelf(); return; }
        worker.execute(() -> {
            Sample sample = sample();
            main.post(() -> {
                if (worker.isShutdown()) return;
                if (!sample.overlayAllowed() || sample.connection().inactive()
                    || !getSystemService(PowerManager.class).isInteractive()) closePanel();
                inactivePolls = sample.connection().inactive() ? inactivePolls + 1 : 0;
                if (inactivePolls >= 2) { stopSelf(); return; }
                if (!sample.equals(current)) {
                    current = sample;
                    manager.notify(NOTIFICATION_ID, notification());
                }
                schedulePoll();
            });
        });
    }

    private void schedulePoll() {
        main.removeCallbacks(poll);
        boolean interactive = getSystemService(PowerManager.class).isInteractive();
        main.postDelayed(poll, interactive ? SAMPLE_INTERVAL : CONNECTION_INTERVAL);
    }

    private Sample sample() {
        long now = SystemClock.elapsedRealtime();
        if (refreshConnection || sampledConnection == null || now - connectionTime >= CONNECTION_INTERVAL) {
            refreshConnection = false;
            sampledConnection = readConnection();
            connectionTime = SystemClock.elapsedRealtime();
        }
        boolean interactive = getSystemService(PowerManager.class).isInteractive();
        Speed speed = sampleSpeed(interactive && sampledConnection.running());
        String app = "MinClash";
        if (interactive) {
            try { app = foregroundApp.label(); }
            catch (RuntimeException error) { app = "当前应用 · 未知"; }
        }
        return new Sample(sampledConnection, app, speed, Settings.canDrawOverlays(this));
    }

    private Speed sampleSpeed(boolean read) {
        if (read) {
            try {
                Module.TunCounters counters = Module.tunCounters();
                CounterSample before = previousCounters;
                previousCounters = new CounterSample(counters, SystemClock.elapsedRealtime());
                if (before == null) return Speed.UNKNOWN;
                long elapsed = previousCounters.time() - before.time();
                long down = counters.received() - before.counters().received();
                long up = counters.transmitted() - before.counters().transmitted();
                if (elapsed > 0 && down >= 0 && up >= 0) {
                    return Speed.measured(Math.round(down * 1000.0 / elapsed), Math.round(up * 1000.0 / elapsed));
                }
                return Speed.UNKNOWN;
            } catch (Exception ignored) { }
        }
        previousCounters = null;
        return Speed.UNKNOWN;
    }

    private static Connection readConnection() {
        try {
            Module.Status status = Module.status();
            if (!status.installed() || !status.enabled || status.state.equals("stopped")) {
                return new Connection("TUN 已停止", "暂无节点", false, true);
            }
            if (!status.state.equals("running")) {
                String detail = switch (status.state) {
                    case "starting" -> "正在建立 TUN";
                    case "retrying" -> "TUN 等待重连";
                    default -> "TUN 未就绪";
                };
                return new Connection(detail, "等待连接", false, false);
            }
            if (status.control) {
                try {
                    Controller.Mode mode = Controller.mode();
                    String node = mode == Controller.Mode.DIRECT ? "直接连接" : Controller.primaryNode(mode);
                    return new Connection(mode.label + "模式", node, true, false);
                } catch (Exception ignored) { }
            }
            return new Connection("TUN 运行中", "控制暂不可用", true, false);
        } catch (Exception error) {
            return new Connection("状态不可用", "暂无节点", false, false);
        }
    }

    private Notification notification() {
        Connection connection = current.connection();
        ProxyNotificationCard card = new ProxyNotificationCard(this);
        PendingIntent modePicker = openPicker(true);
        PendingIntent nodePicker = openPicker(false);
        Notification.Builder builder = new Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification).setContentTitle("MinClash")
            .setContentText(connection.mode() + " · " + connection.node())
            .setCustomContentView(card.view(current.app(), connection.mode(), connection.node(), current.speed(), modePicker, nodePicker))
            .setContentIntent(modePicker).setWhen(notificationSince).setShowWhen(false).setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE).setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(new Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("MinClash").setContentText("代理状态").setWhen(notificationSince).setShowWhen(false).build())
            .setOngoing(true);
        if (Build.VERSION.SDK_INT >= 31) builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        return builder.build();
    }

    private PendingIntent openPicker(boolean mode) {
        Intent intent = current.overlayAllowed() ? new Intent(this, ProxyOverlayLauncher.class)
            .putExtra(EXTRA_MODE_PANEL, mode)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK)
            : overlaySettings(this).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return PendingIntent.getActivity(this, mode ? 1 : 2, intent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private void showPanel(boolean mode) {
        closePanel();
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "请先允许 MinClash 显示在其他应用上层", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            Context context = this;
            if (Build.VERSION.SDK_INT >= 30) {
                Display display = getSystemService(DisplayManager.class).getDisplay(Display.DEFAULT_DISPLAY);
                if (display != null) {
                    context = createDisplayContext(display).createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null);
                }
            }
            ProxyQuickControls next = new ProxyQuickControls(context, mode);
            next.setOnDismissListener(dialog -> {
                if (panel == next) panel = null;
            });
            next.show();
            panel = next;
        } catch (WindowManager.BadTokenException | WindowManager.InvalidDisplayException | SecurityException error) {
            Log.w("MinClash", "Cannot show proxy overlay", error);
            Toast.makeText(this, "浮窗暂不可用，请检查系统权限", Toast.LENGTH_SHORT).show();
        }
    }

    private void closePanel() {
        if (panel == null) return;
        panel.dismiss();
        panel = null;
    }
}

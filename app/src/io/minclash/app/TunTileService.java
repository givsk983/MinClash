package io.minclash.app;

import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class TunTileService extends TileService {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService statusWorker = Executors.newSingleThreadExecutor();
    private final Runnable poll = this::refresh;
    private Module.Status status;
    private boolean listening;
    private boolean reading;
    private boolean operating;
    private int operation;

    @Override public void onStartListening() {
        super.onStartListening();
        listening = true;
        if (!operating) render(Tile.STATE_UNAVAILABLE, "正在检测");
        refresh();
    }

    @Override public void onStopListening() {
        listening = false;
        main.removeCallbacks(poll);
        super.onStopListening();
    }

    @Override public void onDestroy() {
        listening = false;
        main.removeCallbacks(poll);
        statusWorker.shutdownNow();
        super.onDestroy();
    }

    @Override public void onClick() {
        super.onClick();
        if (!listening || operating || !canToggle(status)) return;
        boolean start = !active(status);
        if (isLocked()) unlockAndRun(() -> toggle(start));
        else toggle(start);
    }

    private void refresh() {
        main.removeCallbacks(poll);
        if (!listening || reading || operating) return;
        reading = true;
        int currentOperation = operation;
        statusWorker.execute(() -> {
            Module.Status current;
            try { current = Module.status(); }
            catch (Exception error) { current = null; }
            Module.Status result = current;
            main.post(() -> {
                reading = false;
                if (!listening || operating) return;
                if (currentOperation == operation) renderStatus(result);
                scheduleRefresh();
            });
        });
    }

    private void toggle(boolean start) {
        if (operating) return;
        operating = true;
        operation++;
        main.removeCallbacks(poll);
        render(Tile.STATE_UNAVAILABLE, start ? "正在启动" : "正在停止");
        RoutingStore.WORKER.execute(() -> {
            try {
                Module.Status current = Module.status();
                if (start != active(current)) {
                    if (!canToggle(current)) throw new IOException("TUN prerequisites unavailable");
                    Module.run(start ? Module.Command.START : Module.Command.STOP, null);
                    current = Module.status();
                }
                SharedPreferences preferences = getSharedPreferences("MainActivity", MODE_PRIVATE);
                preferences.edit().putBoolean("connected", true).apply();
                Module.Status result = current;
                main.post(() -> {
                    ProxyNotificationService.sync(this, preferences.getBoolean(MainActivity.NOTIFICATION_CONTROL, false)
                        && result.enabled && active(result));
                    finish(result, false);
                });
            } catch (Exception error) {
                Module.Status current;
                try { current = Module.status(); }
                catch (Exception unavailable) { current = null; }
                Module.Status result = current;
                main.post(() -> finish(result, true));
            }
        });
    }

    private void finish(Module.Status current, boolean failed) {
        operating = false;
        if (current != null) {
            ProxyNotificationService.sync(this, ProxyNotificationService.enabled(this)
                && current.enabled && active(current));
        }
        if (!listening) return;
        renderStatus(current);
        if (failed && canToggle(current)) render(active(current) ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE, "操作失败");
        scheduleRefresh();
    }

    private void scheduleRefresh() {
        main.removeCallbacks(poll);
        if (listening && !operating) main.postDelayed(poll, 3000);
    }

    private static boolean active(Module.Status current) {
        return current.active();
    }

    private static boolean canToggle(Module.Status current) {
        return current != null && (active(current) || current.installed() && current.enabled && current.profile);
    }

    private void renderStatus(Module.Status current) {
        status = current;
        if (!canToggle(current)) {
            String reason = current == null ? "在 App 中检查 Root"
                : !current.installed() ? "需要安装模块"
                : !current.enabled ? "模块已禁用" : "需要导入配置";
            render(Tile.STATE_UNAVAILABLE, reason);
            return;
        }
        String detail = current.phase().label;
        NotificationRecovery.sync(this, current);
        render(active(current) ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE, detail);
    }

    private void render(int state, String detail) {
        if (!listening) return;
        Tile tile = getQsTile();
        if (tile == null) return;
        tile.setState(state);
        tile.setContentDescription("MinClash TUN，" + detail);
        if (Build.VERSION.SDK_INT >= 29) tile.setSubtitle(detail);
        else tile.setLabel("MinClash · " + detail);
        tile.updateTile();
    }
}

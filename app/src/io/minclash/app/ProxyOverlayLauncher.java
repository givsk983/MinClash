package io.minclash.app;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Toast;

/** Lets System UI close the shade on tap, then removes the temporary task before showing any UI. */
public final class ProxyOverlayLauncher extends Activity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        try {
            if (Settings.canDrawOverlays(this)) {
                boolean mode = getIntent().getBooleanExtra(ProxyNotificationService.EXTRA_MODE_PANEL, false);
                startService(new Intent(this, ProxyNotificationService.class)
                    .setAction(ProxyNotificationService.ACTION_OPEN_PANEL)
                    .putExtra(ProxyNotificationService.EXTRA_MODE_PANEL, mode));
            } else {
                Toast.makeText(this, "请先允许 MinClash 显示在其他应用上层", Toast.LENGTH_SHORT).show();
            }
        } catch (IllegalStateException | SecurityException error) {
            Toast.makeText(this, "浮窗暂不可用，请重试", Toast.LENGTH_SHORT).show();
        } finally {
            finishAndRemoveTask();
        }
    }
}

package io.minclash.app;

import android.app.Activity;
import android.os.Bundle;
import android.widget.Toast;

/** Lets System UI close the shade on tap, then removes the temporary task before showing any UI. */
public final class ProxyOverlayLauncher extends Activity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        try {
            boolean mode = getIntent().getBooleanExtra(ProxyNotificationService.EXTRA_MODE_PANEL, false);
            if (!ProxyNotificationService.requestPanel(this, mode)) {
                startActivity(ProxyNotificationService.overlayHelp(this, mode));
            }
        } catch (IllegalStateException | SecurityException error) {
            Toast.makeText(this, "浮窗暂不可用，请重试", Toast.LENGTH_SHORT).show();
        } finally {
            finishAndRemoveTask();
        }
    }
}

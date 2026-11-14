package io.minclash.app;

import android.app.Instrumentation;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Bundle;
import java.lang.reflect.Method;

/** Instrument only a clean, disposable emulator installation of MinClash. */
public final class AndroidNotificationChannelsCheck extends Instrumentation {
    @Override public void onCreate(Bundle arguments) { start(); }

    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            Context context = getTargetContext();
            NotificationManager manager = context.getSystemService(NotificationManager.class);
            require(manager.getNotificationChannels().isEmpty(), "requires a clean emulator installation");
            manager.createNotificationChannel(new NotificationChannel("proxy_status", "代理状态与快捷控制", NotificationManager.IMPORTANCE_LOW));
            Class<?> channels = context.getClassLoader().loadClass("io.minclash.app.ProxyNotificationChannels");
            Method selected = channels.getDeclaredMethod("selected", Context.class);
            Method ensure = channels.getDeclaredMethod("ensure", Context.class);
            selected.setAccessible(true);
            ensure.setAccessible(true);
            require(selected.invoke(null, context).equals("proxy_controls"), "Android's persisted default legacy settings qualify for migration");
            require(ensure.invoke(null, context).equals("proxy_controls"), "new control channel is selected");
            NotificationChannel controls = manager.getNotificationChannel("proxy_controls");
            require(controls != null && controls.getImportance() == NotificationManager.IMPORTANCE_DEFAULT, "ordinary importance");
            require(controls.getSound() == null && !controls.shouldVibrate() && !controls.shouldShowLights(), "silent without light or vibration");
            require(!controls.canBypassDnd() && !controls.canShowBadge(), "no DND bypass or launcher badge");
            require(ensure.invoke(null, context).equals("proxy_controls") && manager.getNotificationChannels().size() == 2, "repeated setup keeps stable channels");
            require(manager.getNotificationChannel("proxy_status").getImportance() == NotificationManager.IMPORTANCE_LOW, "legacy channel is retained intact");
            result.putString("stream", "PASS: persisted Android defaults migrate, controls use ordinary importance and stay silent, repeated setup is stable.\n");
            finish(-1, result);
        } catch (Throwable error) {
            result.putString("stream", "FAIL: " + error + "\n");
            finish(0, result);
        }
    }

    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}

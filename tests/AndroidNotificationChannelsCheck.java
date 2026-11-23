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
            String priorityId = "proxy_controls_priority";
            require(selected.invoke(null, context).equals(priorityId), "Android's persisted low settings qualify for migration");
            NotificationChannel previous = new NotificationChannel("proxy_controls", "代理状态与快捷控制", NotificationManager.IMPORTANCE_DEFAULT);
            previous.setSound(null, null);
            previous.setShowBadge(false);
            manager.createNotificationChannel(previous);
            require(selected.invoke(null, context).equals(priorityId), "Android's persisted silent default settings also qualify for migration");
            require(ensure.invoke(null, context).equals(priorityId), "new control channel is selected");
            NotificationChannel controls = manager.getNotificationChannel(priorityId);
            require(controls != null && controls.getImportance() == NotificationManager.IMPORTANCE_HIGH, "high importance");
            require(controls.getSound() == null && !controls.shouldVibrate() && !controls.shouldShowLights(), "silent without light or vibration");
            require(!controls.canBypassDnd() && !controls.canShowBadge(), "no DND bypass or launcher badge");
            require(ensure.invoke(null, context).equals(priorityId) && manager.getNotificationChannels().size() == 3, "repeated setup keeps stable channels");
            require(manager.getNotificationChannel("proxy_status").getImportance() == NotificationManager.IMPORTANCE_LOW, "legacy channel is retained intact");
            require(manager.getNotificationChannel("proxy_controls").getImportance() == NotificationManager.IMPORTANCE_DEFAULT, "previous controls channel is retained intact");
            result.putString("stream", "PASS: persisted Android defaults migrate, controls use high importance and stay silent, repeated setup is stable.\n");
            finish(-1, result);
        } catch (Throwable error) {
            result.putString("stream", "FAIL: " + error + "\n");
            finish(0, result);
        }
    }

    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}

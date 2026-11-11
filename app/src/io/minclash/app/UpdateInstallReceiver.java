package io.minclash.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import java.util.function.Consumer;

public final class UpdateInstallReceiver extends BroadcastReceiver {
    private static Intent pending;
    private static Consumer<Intent> listener;
    static volatile boolean installing;

    static void listen(Consumer<Intent> callback) {
        listener = callback;
        if (callback != null && pending != null) {
            Intent result = pending;
            pending = null;
            callback.accept(result);
        }
    }

    @Override public void onReceive(Context context, Intent intent) {
        installing = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
            == PackageInstaller.STATUS_PENDING_USER_ACTION;
        if (listener != null) listener.accept(intent);
        else pending = intent;
    }
}

package io.minclash.app;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

/** Lifecycle-bound, coalesced network updates; the Root supervisor recovers independently. */
final class NetworkObserver implements AutoCloseable {
    private final ConnectivityManager manager;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable changed;
    private boolean registered;
    private final ConnectivityManager.NetworkCallback callback = new ConnectivityManager.NetworkCallback() {
        @Override public void onAvailable(Network network) { dispatch(); }
        @Override public void onLost(Network network) { dispatch(); }
        @Override public void onLinkPropertiesChanged(Network network, LinkProperties properties) { dispatch(); }
    };

    NetworkObserver(Context context, Runnable changed) {
        manager = context.getSystemService(ConnectivityManager.class);
        this.changed = changed;
        try {
            manager.registerDefaultNetworkCallback(callback, main);
            registered = true;
        } catch (SecurityException | IllegalArgumentException error) {
            Log.w("MinClash", "Network callbacks unavailable; retaining status polling", error);
        }
    }

    private void dispatch() {
        main.removeCallbacks(changed);
        if (registered) main.postDelayed(changed, 750);
    }

    @Override public void close() {
        main.removeCallbacks(changed);
        if (!registered) return;
        registered = false;
        try { manager.unregisterNetworkCallback(callback); }
        catch (IllegalArgumentException ignored) { }
    }
}

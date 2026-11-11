package io.minclash.app;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Downloads must never occupy the serialized configuration/control queue. */
final class BackgroundWork {
    static final ExecutorService DOWNLOADS = Executors.newFixedThreadPool(2);
    final RetainedTask<AppUpdate.Release> updateCheck = new RetainedTask<>();
    final RetainedTask<SubscriptionPreview> preview = new RetainedTask<>();
    final RetainedTask<Void> updateDownload = new RetainedTask<>();

    void observe(java.util.concurrent.Executor dispatcher, Runnable callback) {
        updateCheck.observe(dispatcher, callback);
        preview.observe(dispatcher, callback);
        updateDownload.observe(dispatcher, callback);
    }

    void stopObserving() {
        updateCheck.stopObserving();
        preview.stopObserving();
        updateDownload.stopObserving();
    }
}

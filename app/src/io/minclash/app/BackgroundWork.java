package io.minclash.app;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Downloads must never occupy the serialized configuration/control queue. */
final class BackgroundWork {
    static final ExecutorService DOWNLOADS = Executors.newFixedThreadPool(2);
    final RetainedTask<AppUpdate.Release> updateCheck = new RetainedTask<>();
    final RetainedTask<SubscriptionPreview> preview = new RetainedTask<>();
    final RetainedTask<Void> updateDownload = new RetainedTask<>();
    final RetainedTask<Object> backup = new RetainedTask<>();
    byte[] pendingExport;
    boolean backupRestoring;
    record BackupExport(byte[] bytes) {}
    record BackupLocked(byte[] bytes, boolean incorrect) {}
    record BackupPreview(BackupArchive.Data data, String stamp) {}
    record BackupDone(boolean restored, Module.Status status, String warning) {}

    void observe(java.util.concurrent.Executor dispatcher, Runnable callback) {
        updateCheck.observe(dispatcher, callback);
        preview.observe(dispatcher, callback);
        updateDownload.observe(dispatcher, callback);
        backup.observe(dispatcher, callback);
    }

    void stopObserving() {
        updateCheck.stopObserving();
        preview.stopObserving();
        updateDownload.stopObserving();
        backup.stopObserving();
    }
}

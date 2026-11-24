package io.minclash.app;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Downloads must never occupy the serialized configuration/control queue. */
final class BackgroundWork {
    static final ExecutorService DOWNLOADS = Executors.newFixedThreadPool(2);
    final RetainedTask<AppUpdate.Release> updateCheck = new RetainedTask<>();
    final RetainedTask<ProfilePreview> preview = new RetainedTask<>();
    final RetainedTask<Void> subscriptionSave = new RetainedTask<>();
    final RetainedTask<Void> updateDownload = new RetainedTask<>();
    final RetainedTask<Object> backup = new RetainedTask<>();
    final RetainedTask<UsageResult> usage = new RetainedTask<>();
    record ProfilePreview(SubscriptionPreview changes, RoutingStore.State copyFrom, List<String> missingProtectionTargets,
                          String profileNotice, boolean cachedSubscription) {}
    SubscriptionPreview editedPreview;
    boolean copiedPreviewRules;
    record UsageResult(SubscriptionCatalog.Entry entry, SubscriptionUsage value) {}
    byte[] pendingExport;
    boolean backupRestoring;
    record BackupExport(byte[] bytes) {}
    record BackupLocked(byte[] bytes, boolean incorrect) {}
    record BackupPreview(BackupArchive.Data data, String stamp) {}
    record BackupDone(boolean restored, Module.Status status, String warning) {}

    void observe(java.util.concurrent.Executor dispatcher, Runnable callback) {
        updateCheck.observe(dispatcher, callback);
        preview.observe(dispatcher, callback);
        subscriptionSave.observe(dispatcher, callback);
        updateDownload.observe(dispatcher, callback);
        backup.observe(dispatcher, callback);
        usage.observe(dispatcher, callback);
    }

    void stopObserving() {
        updateCheck.stopObserving();
        preview.stopObserving();
        subscriptionSave.stopObserving();
        updateDownload.stopObserving();
        backup.stopObserving();
        usage.stopObserving();
    }
}

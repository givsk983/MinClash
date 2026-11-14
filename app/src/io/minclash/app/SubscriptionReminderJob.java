package io.minclash.app;

import android.app.job.JobParameters;
import android.app.job.JobService;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

/** Deadline checks work offline. Header refreshes never import or apply a configuration. */
public final class SubscriptionReminderJob extends JobService {
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile JobParameters active;

    @Override public boolean onStartJob(JobParameters params) {
        active = params;
        RoutingStore.WORKER.execute(() -> prepare(params));
        return true;
    }
    @Override public boolean onStopJob(JobParameters params) {
        if (active == params) active = null;
        return SubscriptionPreferences.enabled(SubscriptionReminders.preferences(this));
    }

    private SubscriptionCatalog.Snapshot catalog() throws Exception {
        return new SubscriptionCatalog(SubscriptionReminders.preferences(this), new RoutingStore(this)).load();
    }

    private void prepare(JobParameters params) {
        if (active != params) return;
        SharedPreferences prefs = SubscriptionReminders.preferences(this);
        try {
            SubscriptionCatalog.Snapshot snapshot = catalog();
            SubscriptionCatalog.Entry entry = snapshot.active();
            if (!SubscriptionPreferences.enabled(prefs) || SubscriptionPreferences.days(prefs).isEmpty() || entry == null) {
                finish(params, SubscriptionReminders.evaluate(this, snapshot)); return;
            }
            long now = System.currentTimeMillis();
            if (!SubscriptionReminders.refreshDue(prefs, entry.address(), now)) {
                finish(params, SubscriptionReminders.evaluate(this, snapshot)); return;
            }
            // A failed/offline attempt is also throttled, so missing metadata cannot create a busy loop.
            SubscriptionReminders.markAttempt(prefs, entry.address(), now);
            BackgroundWork.DOWNLOADS.execute(() -> download(params, entry));
        } catch (Exception unavailable) { finish(params, SubscriptionReminderPolicy.CHECK_INTERVAL); }
    }

    private void download(JobParameters params, SubscriptionCatalog.Entry entry) {
        if (active != params) return;
        SubscriptionUsage downloaded = null;
        try { downloaded = Subscription.usage(entry.address()); }
        catch (Exception unavailable) { /* Keep the known expiry and still check its local deadline. */ }
        SubscriptionUsage result = downloaded;
        RoutingStore.WORKER.execute(() -> {
            if (active != params) return;
            try {
                SharedPreferences prefs = SubscriptionReminders.preferences(this);
                SubscriptionCatalog.Snapshot latest = catalog();
                if (result != null && SubscriptionPreferences.enabled(prefs) && latest.active() != null
                    && latest.active().address().equals(entry.address())
                    && result.checkedAt() >= SubscriptionUsage.load(prefs, entry.address()).checkedAt()) result.cache(prefs, entry.address());
                finish(params, SubscriptionReminders.evaluate(this, latest));
            } catch (Exception unavailable) { finish(params, SubscriptionReminderPolicy.CHECK_INTERVAL); }
        });
    }

    private void finish(JobParameters params, long nextDelay) {
        main.post(() -> {
            if (active != params) return;
            active = null;
            jobFinished(params, false);
            SubscriptionReminders.schedule(this, nextDelay);
        });
    }
}

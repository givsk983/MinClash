package io.minclash.app;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import java.util.Date;
import java.util.concurrent.TimeUnit;

public final class SubscriptionUpdateJob extends JobService {
    static final String ENABLED = "auto_subscription_update";
    private static final String LAST_ATTEMPT = "auto_subscription_attempt";
    private static final String LAST_OUTCOME = "auto_subscription_outcome";
    private static final int JOB_ID = 102;
    private volatile JobParameters active;
    private volatile DownloadRequest downloadRequest;
    private final Handler main = new Handler(Looper.getMainLooper());

    static boolean schedule(Context context, boolean enabled) {
        JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        if (!enabled) {
            scheduler.cancel(JOB_ID);
            return true;
        }
        if (scheduler.getPendingJob(JOB_ID) != null) return true;
        JobInfo job = new JobInfo.Builder(JOB_ID, new ComponentName(context, SubscriptionUpdateJob.class))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPeriodic(TimeUnit.DAYS.toMillis(1))
            .setPersisted(true)
            .build();
        return scheduler.schedule(job) == JobScheduler.RESULT_SUCCESS;
    }

    static String summary(Context context, SharedPreferences preferences) {
        long attempt = preferences.getLong(LAST_ATTEMPT, 0);
        String schedule = "开启后，Android 会在有网络时安排每日刷新当前订阅。";
        if (attempt == 0 || !preferences.getBoolean(ENABLED, false)) return schedule;
        String date = android.text.format.DateFormat.getMediumDateFormat(context).format(new Date(attempt));
        String time = android.text.format.DateFormat.getTimeFormat(context).format(new Date(attempt));
        return schedule + "\n最近一次  " + date + " " + time + " · " + preferences.getString(LAST_OUTCOME, "");
    }

    @Override public boolean onStartJob(JobParameters params) {
        if (downloadRequest != null) downloadRequest.cancel();
        DownloadRequest request = new DownloadRequest();
        downloadRequest = request;
        active = params;
        RoutingStore.WORKER.execute(() -> refresh(params, request));
        return true;
    }

    @Override public boolean onStopJob(JobParameters params) {
        if (active == params) {
            active = null;
            if (downloadRequest != null) downloadRequest.cancel();
        }
        return preferences().getBoolean(ENABLED, false);
    }

    private SharedPreferences preferences() {
        return getSharedPreferences("MainActivity", MODE_PRIVATE);
    }

    private void refresh(JobParameters params, DownloadRequest request) {
        SharedPreferences preferences = preferences();
        try {
            if (active != params || !preferences.getBoolean(ENABLED, false)) { finish(params, null, request); return; }
            RoutingStore routing = new RoutingStore(this);
            SubscriptionCatalog.Entry current = new SubscriptionCatalog(preferences, routing).load().active();
            if (current == null) { finish(params, null, request); return; }
            Module.Status status = Module.status();
            if (!status.enabled || !status.profile || !status.state.equals("running") && !status.state.equals("stopped")) {
                finish(params, "跳过，模块或配置未就绪", request);
                return;
            }
            int generation = RoutingStore.generation();
            BackgroundWork.DOWNLOADS.execute(() -> download(params, current, generation, request));
        } catch (Exception error) {
            finish(params, "更新失败，请在配置页手动重试", request);
        }
    }

    private void download(JobParameters params, SubscriptionCatalog.Entry current, int generation, DownloadRequest request) {
        if (active != params || !preferences().getBoolean(ENABLED, false)) { finish(params, null, request); return; }
        try {
            Subscription.Download downloaded = Subscription.download(current.address(), request);
            RoutingStore.WORKER.execute(() -> apply(params, current, generation, downloaded, request));
        } catch (Exception error) {
            finish(params, "更新失败，请在配置页手动重试", request);
        }
    }

    private void apply(JobParameters params, SubscriptionCatalog.Entry current, int generation, Subscription.Download downloaded, DownloadRequest request) {
        SharedPreferences preferences = preferences();
        String outcome = null;
        try {
            if (active != params || !preferences.getBoolean(ENABLED, false)) return;
            request.seal();
            RoutingStore routing = new RoutingStore(this);
            SubscriptionCatalog.Entry latest = new SubscriptionCatalog(preferences, routing).load().active();
            if (latest != null && latest.address().equals(current.address())) {
                downloaded.usage().cache(preferences, current.address());
                SubscriptionReminders.reconcile(this, new SubscriptionCatalog(preferences, routing).load());
            }
            if (generation != RoutingStore.generation() || latest == null || !latest.address().equals(current.address())) {
                outcome = "跳过，下载期间配置或当前订阅已变化";
                return;
            }
            Module.Status status = Module.status();
            if (!status.enabled || !status.profile || !status.state.equals("running") && !status.state.equals("stopped")) {
                outcome = "跳过，模块或配置未就绪";
                return;
            }
            routing.importProfile(downloaded.source(), current.address());
            outcome = "更新成功";
        } catch (RoutingStore.SelectionRestoreFailure warning) {
            outcome = "已更新，请检查节点选择";
        } catch (Exception error) {
            outcome = "更新失败，请在配置页手动重试";
        } finally {
            finish(params, outcome, request);
        }
    }

    private void finish(JobParameters params, String outcome, DownloadRequest request) {
        request.close();
        main.post(() -> {
            if (active != params) return;
            if (outcome != null) record(preferences(), outcome);
            active = null;
            jobFinished(params, false);
        });
    }

    private static void record(SharedPreferences preferences, String outcome) {
        preferences.edit().putLong(LAST_ATTEMPT, System.currentTimeMillis()).putString(LAST_OUTCOME, outcome).apply();
    }
}

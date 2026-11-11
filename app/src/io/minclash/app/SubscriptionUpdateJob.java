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
        active = params;
        RoutingStore.WORKER.execute(() -> refresh(params));
        return true;
    }

    @Override public boolean onStopJob(JobParameters params) {
        if (active == params) active = null;
        return preferences().getBoolean(ENABLED, false);
    }

    private SharedPreferences preferences() {
        return getSharedPreferences("MainActivity", MODE_PRIVATE);
    }

    private void refresh(JobParameters params) {
        SharedPreferences preferences = preferences();
        try {
            if (active != params || !preferences.getBoolean(ENABLED, false)) { finish(params, null); return; }
            RoutingStore routing = new RoutingStore(this);
            SubscriptionCatalog.Entry current = new SubscriptionCatalog(preferences, routing).load().active();
            if (current == null) { finish(params, null); return; }
            Module.Status status = Module.status();
            if (!status.enabled || !status.profile || !status.state.equals("running") && !status.state.equals("stopped")) {
                finish(params, "跳过，模块或配置未就绪");
                return;
            }
            int generation = RoutingStore.generation();
            BackgroundWork.DOWNLOADS.execute(() -> download(params, current, generation));
        } catch (Exception error) {
            finish(params, "更新失败，请在配置页手动重试");
        }
    }

    private void download(JobParameters params, SubscriptionCatalog.Entry current, int generation) {
        if (active != params || !preferences().getBoolean(ENABLED, false)) { finish(params, null); return; }
        try {
            byte[] source = Subscription.fetch(current.address());
            RoutingStore.WORKER.execute(() -> apply(params, current, generation, source));
        } catch (Exception error) {
            finish(params, "更新失败，请在配置页手动重试");
        }
    }

    private void apply(JobParameters params, SubscriptionCatalog.Entry current, int generation, byte[] source) {
        SharedPreferences preferences = preferences();
        String outcome = null;
        try {
            if (active != params || !preferences.getBoolean(ENABLED, false)) return;
            RoutingStore routing = new RoutingStore(this);
            SubscriptionCatalog.Entry latest = new SubscriptionCatalog(preferences, routing).load().active();
            if (generation != RoutingStore.generation() || latest == null || !latest.address().equals(current.address())) {
                outcome = "跳过，下载期间配置或当前订阅已变化";
                return;
            }
            Module.Status status = Module.status();
            if (!status.enabled || !status.profile || !status.state.equals("running") && !status.state.equals("stopped")) {
                outcome = "跳过，模块或配置未就绪";
                return;
            }
            routing.importProfile(source, current.address());
            outcome = "更新成功";
        } catch (RoutingStore.SelectionRestoreFailure warning) {
            outcome = "已更新，请检查节点选择";
        } catch (Exception error) {
            outcome = "更新失败，请在配置页手动重试";
        } finally {
            finish(params, outcome);
        }
    }

    private void finish(JobParameters params, String outcome) {
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

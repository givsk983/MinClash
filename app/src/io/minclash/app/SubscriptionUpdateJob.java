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
    private static final long RETRY_DELAY_MS = 15_000;
    private volatile JobParameters active;
    private volatile DownloadRequest downloadRequest;
    private final Handler main = new Handler(Looper.getMainLooper());
    private Runnable pendingRetry;
    record Attempt(String address, int generation, long id, boolean retried) {
        boolean stillCurrent(String activeAddress, int currentGeneration) {
            return address.equals(activeAddress) && generation == currentGeneration;
        }

        Attempt retry() { return new Attempt(address, generation, id, true); }
    }

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
        cancelRetry();
        if (downloadRequest != null) downloadRequest.cancel();
        DownloadRequest request = new DownloadRequest();
        downloadRequest = request;
        active = params;
        RoutingStore.WORKER.execute(() -> refresh(params, request, null));
        return true;
    }

    @Override public boolean onStopJob(JobParameters params) {
        if (active == params) {
            cancelRetry();
            active = null;
            if (downloadRequest != null) downloadRequest.cancel();
            downloadRequest = null;
        }
        return preferences().getBoolean(ENABLED, false);
    }

    @Override public void onDestroy() {
        cancelRetry();
        active = null;
        if (downloadRequest != null) downloadRequest.cancel();
        downloadRequest = null;
        super.onDestroy();
    }

    private void cancelRetry() {
        if (pendingRetry != null) main.removeCallbacks(pendingRetry);
        pendingRetry = null;
    }

    private SharedPreferences preferences() {
        return getSharedPreferences("MainActivity", MODE_PRIVATE);
    }

    private boolean isActive(JobParameters params, DownloadRequest request) {
        return active == params && downloadRequest == request;
    }

    private void refresh(JobParameters params, DownloadRequest request, Attempt retry) {
        SharedPreferences preferences = preferences();
        try {
            if (!isActive(params, request) || !preferences.getBoolean(ENABLED, false)) { finish(params, null, request); return; }
            if (new BackupStore(this).recoverPending()) { finish(params, null, request); return; }
            RoutingStore routing = new RoutingStore(this);
            SubscriptionCatalog.Entry current = new SubscriptionCatalog(preferences, routing).load().active();
            if (current == null) { finish(params, null, request); return; }
            int generation = RoutingStore.generation();
            if (retry != null && !retry.stillCurrent(current.address(), generation)) {
                finish(params, "跳过，等待重试期间配置或当前订阅已变化", request);
                return;
            }
            Attempt attempt = retry == null ? new Attempt(current.address(), generation, SubscriptionUpdateStatus.begin(), false) : retry;
            Module.Status status = Module.status();
            if (!canRefresh(status)) {
                finish(params, "跳过，模块或配置未就绪", request, attempt, null, recovering(status));
                return;
            }
            BackgroundWork.DOWNLOADS.execute(() -> download(params, attempt, request));
        } catch (Exception error) {
            finish(params, "更新失败，请在配置页手动重试", request);
        }
    }

    private void download(JobParameters params, Attempt attempt, DownloadRequest request) {
        if (!isActive(params, request) || !preferences().getBoolean(ENABLED, false)) { finish(params, null, request); return; }
        try {
            Subscription.Download downloaded = Subscription.download(attempt.address(), request);
            RoutingStore.WORKER.execute(() -> apply(params, attempt, downloaded, request));
        } catch (DownloadRequest.Cancelled error) {
            finish(params, null, request);
        } catch (Subscription.SecureRequired error) {
            finish(params, "旧 HTTP 订阅已跳过，请添加 HTTPS 链接", request, attempt, SubscriptionUpdateStatus.downloadFailure(error), false);
        } catch (Exception error) {
            String reason = SubscriptionUpdateStatus.downloadFailure(error);
            boolean retryable = error instanceof Subscription.NetworkFailure || error instanceof DownloadRequest.Expired;
            finish(params, "更新失败 · " + reason, request, attempt, reason, retryable);
        }
    }

    private void apply(JobParameters params, Attempt attempt, Subscription.Download downloaded, DownloadRequest request) {
        SharedPreferences preferences = preferences();
        String outcome = null;
        String failure = null;
        boolean retryable = false;
        try {
            if (!isActive(params, request) || !preferences.getBoolean(ENABLED, false)) return;
            BackupStore.requireResolved(this);
            request.seal();
            RoutingStore routing = new RoutingStore(this);
            SubscriptionCatalog.Entry latest = new SubscriptionCatalog(preferences, routing).load().active();
            if (latest != null && latest.address().equals(attempt.address())) {
                downloaded.usage().cache(preferences, attempt.address());
                SubscriptionReminders.reconcile(this, new SubscriptionCatalog(preferences, routing).load());
            }
            if (latest == null || !attempt.stillCurrent(latest.address(), RoutingStore.generation())) {
                outcome = "跳过，下载期间配置或当前订阅已变化";
                return;
            }
            Module.Status status = Module.status();
            if (!canRefresh(status)) {
                outcome = "跳过，模块或配置未就绪";
                retryable = recovering(status);
                return;
            }
            routing.importProfile(downloaded.source(), attempt.address());
            SubscriptionUpdateStatus.complete(preferences, attempt.address(), attempt.id(), "");
            outcome = "更新成功";
        } catch (RoutingStore.SelectionRestoreFailure warning) {
            SubscriptionUpdateStatus.complete(preferences, attempt.address(), attempt.id(), "");
            outcome = "已更新，请检查节点选择";
        } catch (DownloadRequest.Cancelled error) {
            outcome = null;
        } catch (Exception error) {
            failure = error instanceof DownloadRequest.Expired ? SubscriptionUpdateStatus.downloadFailure(error)
                : SubscriptionUpdateStatus.applyFailure(error);
            outcome = "更新失败 · " + failure;
        } finally {
            finish(params, outcome, request, attempt, failure, retryable);
        }
    }

    private static boolean canRefresh(Module.Status status) {
        return status.enabled && status.profile && (status.running() || status.phase() == Module.Phase.STOPPED);
    }

    static boolean recovering(Module.Status status) {
        if (!status.enabled || !status.profile) return false;
        return switch (status.phase()) {
            case STARTING, WAITING_NETWORK, RETRYING, DEGRADED -> true;
            default -> false;
        };
    }

    private void finish(JobParameters params, String outcome, DownloadRequest request) {
        finish(params, outcome, request, null, null, false);
    }

    private void finish(JobParameters params, String outcome, DownloadRequest request, Attempt attempt, String failure,
                        boolean retryable) {
        request.close();
        main.post(() -> {
            if (!isActive(params, request)) return;
            SharedPreferences preferences = preferences();
            boolean enabled = preferences.getBoolean(ENABLED, false);
            if (retryable && !attempt.retried() && enabled) {
                record(preferences, outcome + " · 稍后自动重试一次");
                pendingRetry = () -> {
                    pendingRetry = null;
                    if (!isActive(params, request)) return;
                    DownloadRequest next = new DownloadRequest();
                    downloadRequest = next;
                    RoutingStore.WORKER.execute(() -> refresh(params, next, attempt.retry()));
                };
                main.postDelayed(pendingRetry, RETRY_DELAY_MS);
                return;
            }
            if (failure != null && enabled) {
                SubscriptionUpdateStatus.complete(preferences, attempt.address(), attempt.id(), failure);
            }
            if (outcome != null) record(preferences, outcome);
            active = null;
            downloadRequest = null;
            jobFinished(params, false);
        });
    }

    private static void record(SharedPreferences preferences, String outcome) {
        preferences.edit().putLong(LAST_ATTEMPT, System.currentTimeMillis()).putString(LAST_OUTCOME, outcome).apply();
    }
}

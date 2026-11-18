package io.minclash.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.format.DateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

final class SubscriptionUpdateStatus {
    private static final String PREFIX = "subscription_update_";
    private static final Map<String, Long> completedAttempts = new HashMap<>();
    private static long sequence;

    record Result(long lastSuccess, String failure) {
        boolean failed() { return !failure.isEmpty(); }

        String summary(Context context) {
            String success = lastSuccess == 0 ? "暂无成功更新记录"
                : "上次成功 · " + DateFormat.getMediumDateFormat(context).format(new Date(lastSuccess))
                    + " " + DateFormat.getTimeFormat(context).format(new Date(lastSuccess));
            return failed() ? success + "\n更新失败 · " + failure : success;
        }
    }

    static synchronized long begin() { return ++sequence; }

    static synchronized Result load(SharedPreferences preferences, String address) {
        String key = PREFIX + SubscriptionReminders.key(address);
        return new Result(preferences.getLong(key + "_success", 0), preferences.getString(key + "_failure", ""));
    }

    static synchronized void complete(SharedPreferences preferences, String address, long attempt, String failure) {
        String key = PREFIX + SubscriptionReminders.key(address);
        long completed = completedAttempts.getOrDefault(key, 0L);
        SharedPreferences.Editor edit = preferences.edit();
        if (failure.isEmpty()) edit.putLong(key + "_success", System.currentTimeMillis());
        if (attempt >= completed) {
            completedAttempts.put(key, attempt);
            if (failure.isEmpty()) edit.remove(key + "_failure");
            else edit.putString(key + "_failure", failure);
        }
        edit.apply();
    }

    static String downloadFailure(Exception error) {
        if (error instanceof DownloadRequest.Expired) return "订阅请求超时";
        if (error instanceof Subscription.NetworkFailure network) return switch (network.kind) {
            case DNS -> "无法解析订阅服务器";
            case TIMEOUT -> "订阅请求超时";
            case CONNECT -> "无法连接订阅服务器";
        };
        if (error instanceof Subscription.SecureRequired) return "请使用 HTTPS 订阅链接";
        if (error instanceof Subscription.TlsFailure) return "TLS 连接失败";
        if (error instanceof Subscription.HttpFailure response) return switch (response.status) {
            case 401, 403 -> "订阅链接未获授权（HTTP " + response.status + "）";
            case 404, 410 -> "订阅链接可能已失效（HTTP " + response.status + "）";
            default -> "服务器返回 HTTP " + response.status;
        };
        if (error instanceof Subscription.ConfigFailure) return "订阅配置校验失败";
        if (error instanceof Profile.TooLarge) return "订阅配置超过 2 MiB";
        return "无法读取订阅，请检查网络和链接";
    }

    static String applyFailure(Exception error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof Module.ProfileFailure) return "内核配置校验失败";
            if (cause instanceof Profile.MissingRoutingTarget) return "订阅缺少分流规则需要的代理组";
        }
        return "应用配置失败，请检查 TUN 状态或重新预览";
    }

    private SubscriptionUpdateStatus() {}
}

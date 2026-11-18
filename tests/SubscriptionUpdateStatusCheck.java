package io.minclash.app;

import android.content.SharedPreferences;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;

public final class SubscriptionUpdateStatusCheck {
    public static void main(String[] args) throws Exception {
        Map<String, Object> values = new HashMap<>();
        SharedPreferences prefs = (SharedPreferences) Proxy.newProxyInstance(
            SubscriptionUpdateStatusCheck.class.getClassLoader(), new Class<?>[]{SharedPreferences.class},
            (proxy, method, inputs) -> switch (method.getName()) {
                case "getLong", "getString" -> values.getOrDefault(inputs[0], inputs[1]);
                case "edit" -> Proxy.newProxyInstance(SubscriptionUpdateStatusCheck.class.getClassLoader(),
                    new Class<?>[]{SharedPreferences.Editor.class}, (editor, operation, fields) -> switch (operation.getName()) {
                        case "putLong", "putString" -> { values.put((String) fields[0], fields[1]); yield editor; }
                        case "remove" -> { values.remove(fields[0]); yield editor; }
                        case "apply" -> null;
                        default -> throw new UnsupportedOperationException(operation.getName());
                    });
                default -> throw new UnsupportedOperationException(method.getName());
            });
        String address = "https://example.invalid/private-token";
        long first = SubscriptionUpdateStatus.begin();
        long second = SubscriptionUpdateStatus.begin();
        SubscriptionUpdateStatus.complete(prefs, address, second, "");
        long lastSuccess = SubscriptionUpdateStatus.load(prefs, address).lastSuccess();
        SubscriptionUpdateStatus.complete(prefs, address, first, "subscription timed out");
        require(lastSuccess > 0 && !SubscriptionUpdateStatus.load(prefs, address).failed(), "late failure cannot replace newer success");
        String expiredLink = SubscriptionUpdateStatus.downloadFailure(new Subscription.HttpFailure(410));
        SubscriptionUpdateStatus.complete(prefs, address, SubscriptionUpdateStatus.begin(), expiredLink);
        var failed = SubscriptionUpdateStatus.load(prefs, address);
        require(failed.lastSuccess() == lastSuccess && failed.failure().contains("410"), "failure keeps the last success time");
        require(!SubscriptionUpdateStatus.load(prefs, "https://other.invalid/config").failed(), "independent subscription records");
        require(values.keySet().stream().noneMatch(key -> key.contains("private-token")), "subscription token stays out of status keys");
        SubscriptionUpdateStatus.complete(prefs, address, SubscriptionUpdateStatus.begin(), "");
        require(!SubscriptionUpdateStatus.load(prefs, address).failed(), "successful retry clears failure");
        require(SubscriptionUpdateStatus.downloadFailure(new DownloadRequest.Expired()).contains("超时"), "timed out requests have an actionable reason");
        require(SubscriptionUpdateStatus.applyFailure(new java.io.IOException(new Module.ProfileFailure("details")))
            .contains("校验"), "wrapped core validation remains distinguishable");
        var attempt = new SubscriptionUpdateJob.Attempt(address, 7, second, false);
        var retry = attempt.retry();
        require(!attempt.retried() && retry.retried() && retry.id() == second && retry.stillCurrent(address, 7),
            "retry keeps the original update and consumes its allowance");
        require(!retry.stillCurrent(address, 8) && !retry.stillCurrent("https://other.invalid/config", 7),
            "retry rejects changed profile generation and subscription");
        String installed = "\nenabled=1\nprofile=1\n";
        for (String state : new String[]{"starting", "waiting_network", "retrying", "running\nhealth=1"}) {
            require(SubscriptionUpdateJob.recovering(Module.parseStatus("state=" + state + installed)), "transient " + state);
        }
        for (String state : new String[]{"stopped", "orphaned", "running"}) {
            require(!SubscriptionUpdateJob.recovering(Module.parseStatus("state=" + state + installed)), "no retry for " + state);
        }
        require(!SubscriptionUpdateJob.recovering(Module.parseStatus("state=starting\nenabled=1\n")),
            "missing profile cannot recover via a subscription retry");
        System.out.println("PASS: per-subscription status, late results, sanitized failures, and scoped transient retry.");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}

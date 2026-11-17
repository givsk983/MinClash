package io.minclash.app;

import android.content.SharedPreferences;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class UsageOrderingCheck {
    public static void main(String[] args) throws Exception {
        Map<String, String> stored = new HashMap<>();
        boolean[] fail = {false};
        SharedPreferences prefs = (SharedPreferences) Proxy.newProxyInstance(UsageOrderingCheck.class.getClassLoader(),
            new Class<?>[]{SharedPreferences.class}, (proxy, method, values) -> {
                if (method.getName().equals("getString")) return stored.getOrDefault((String) values[0], (String) values[1]);
                if (!method.getName().equals("edit")) throw new UnsupportedOperationException(method.getName());
                Map<String, String> pending = new HashMap<>();
                return Proxy.newProxyInstance(UsageOrderingCheck.class.getClassLoader(), new Class<?>[]{SharedPreferences.Editor.class}, (editor, edit, fields) -> {
                    if (edit.getName().equals("putString")) { pending.put((String) fields[0], (String) fields[1]); return editor; }
                    if (edit.getName().equals("commit")) { if (fail[0]) return false; stored.putAll(pending); return true; }
                    throw new UnsupportedOperationException(edit.getName());
                });
            });
        String address = "https://example.invalid/subscription";
        SubscriptionUsage newer = usage(2000), older = usage(1000);
        newer.save(prefs, address);
        require(older.saveLatest(prefs, address).equals(newer), "manual refresh returns persisted newer snapshot");
        require(older.cache(prefs, address) && SubscriptionUsage.load(prefs, address).equals(newer), "late preview/job cache cannot overwrite");
        var workers = Executors.newFixedThreadPool(4);
        for (int i = 0; i < 40; i++) { int number = i; workers.submit(() -> usage(3000 + number).cache(prefs, address)); }
        workers.shutdown(); require(workers.awaitTermination(5, TimeUnit.SECONDS), "writers finish");
        require(SubscriptionUsage.load(prefs, address).checkedAt() == 3039, "concurrent writers preserve newest time");
        fail[0] = true;
        require(!usage(4000).cache(prefs, address) && SubscriptionUsage.load(prefs, address).checkedAt() == 3039, "disk failure preserves prior snapshot");
        System.out.println("PASS: delayed manual/preview/job writes, returned freshness, concurrent ordering and failed persistence.");
    }
    private static SubscriptionUsage usage(long checked) { return new SubscriptionUsage(1, 2, 100, 1893456000, checked); }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
}

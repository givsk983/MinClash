package io.minclash.app;

import android.content.SharedPreferences;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public final class SubscriptionReminderCheck {
    public static void main(String[] args) {
        long now = 1_794_528_000_000L, day = SubscriptionReminderPolicy.DAY;
        Set<Integer> defaults = Set.of(7, 3, 1);
        long expiry = (now + 10 * day) / 1000;
        require(SubscriptionReminderPolicy.stage(expiry, now, defaults) == -1, "no early warning outside selected windows");
        require(SubscriptionReminderPolicy.stage(expiry, now + 3 * day, defaults) == 7, "seven day boundary");
        require(SubscriptionReminderPolicy.stage(expiry, now + 7 * day, defaults) == 3, "three day boundary");
        require(SubscriptionReminderPolicy.stage(expiry, now + 9 * day, defaults) == 1, "one day boundary");
        require(SubscriptionReminderPolicy.stage(expiry, now + 10 * day, defaults) == 0, "expiry boundary");
        require(SubscriptionReminderPolicy.stage(expiry, now + 100 * day, defaults) == 0, "old expiry does not overflow");
        require(SubscriptionReminderPolicy.stage(expiry, now + 9 * day + 1_000, defaults) == 1, "missed windows collapse to one urgent stage");
        require(SubscriptionReminderPolicy.stage(expiry, now + 9 * day, Set.of(5, 2)) == 2, "custom windows are respected");
        require(SubscriptionReminderPolicy.stage(-1, now, defaults) == -1
            && SubscriptionReminderPolicy.stage(0, now, defaults) == -1
            && SubscriptionReminderPolicy.stage(253402300800L, now, defaults) == -1, "unknown or invalid expiry never alerts");
        require(SubscriptionReminderPolicy.stage(expiry, now + 11 * day, Set.of()) == -1, "empty settings also suppress expired notices");

        require(SubscriptionReminderPolicy.shouldNotify(expiry, 7, ""), "first reminder");
        String saved = expiry + ":7";
        require(!SubscriptionReminderPolicy.shouldNotify(expiry, 7, saved), "persisted receipt suppresses repeat after restart");
        require(SubscriptionReminderPolicy.shouldNotify(expiry, 3, saved), "next urgent stage remains available");
        require(!SubscriptionReminderPolicy.shouldNotify(expiry, 7, expiry + ":3"), "backward clock or settings change does not replay old stages");
        require(SubscriptionReminderPolicy.shouldNotify(expiry + 30 * 86400, 7, saved), "renewal starts a new expiry cycle");
        require(!SubscriptionReminderPolicy.shouldNotify(expiry, -1, saved), "not due cannot notify");
        require(SubscriptionReminderPolicy.shouldNotify(expiry, 1, "damaged")
            && SubscriptionReminderPolicy.shouldNotify(expiry, 1, expiry + ":-1"), "invalid receipt cannot hide a due reminder");
        require(!SubscriptionReminderPolicy.shouldNotify(expiry, 0, expiry + ":0"), "expired notices also deduplicate");
        require(SubscriptionReminderPolicy.nextDelay(expiry, now, defaults) == SubscriptionReminderPolicy.CHECK_INTERVAL, "background refresh bounded to six hours");
        require(SubscriptionReminderPolicy.nextDelay((now + 7 * day + 60_000) / 1000, now, defaults) == 60_000, "next selected threshold is scheduled sooner");
        require(SubscriptionReminderPolicy.nextDelay((now + 30_000) / 1000, now, defaults) == 30_000, "expiry itself is scheduled");
        require(SubscriptionReminderPolicy.nextDelay(-1, now, defaults) == SubscriptionReminderPolicy.CHECK_INTERVAL, "unknown metadata cannot spin the scheduler");

        Map<String, Object> memory = new HashMap<>();
        SharedPreferences preferences = (SharedPreferences) Proxy.newProxyInstance(SubscriptionReminderCheck.class.getClassLoader(),
            new Class<?>[] {SharedPreferences.class}, (proxy, method, values) -> {
                if (method.getName().equals("getString") || method.getName().equals("getStringSet") || method.getName().equals("getBoolean") || method.getName().equals("getLong"))
                    return memory.getOrDefault(values[0], values[1]);
                throw new UnsupportedOperationException(method.getName());
            });
        require(SubscriptionPreferences.style(preferences).equals("quota") && SubscriptionPreferences.days(preferences).equals(defaults), "defaults preserve both display and reminder choices");
        memory.put(SubscriptionPreferences.STYLE, "dual");
        require(SubscriptionPreferences.style(preferences).equals("dual"), "saved alternative layout is used");
        memory.put(SubscriptionPreferences.STYLE, "invalid");
        require(SubscriptionPreferences.style(preferences).equals("quota"), "unknown layout falls back safely");
        memory.put(SubscriptionPreferences.DAYS, Set.of("bad", "0", "31", "2", "10"));
        require(SubscriptionPreferences.days(preferences).equals(Set.of(2, 10)), "invalid saved thresholds are ignored");
        require(SubscriptionPreferences.parseDays("7，3、1 01").equals(Set.of("7", "3", "1")), "localized input is normalized and deduplicated");
        require(SubscriptionPreferences.parseDays("  ").isEmpty(), "disabled reminder can retain empty thresholds");
        for (String input : new String[] {"0", "31", "-1", "1.5", "seven", "99999999999"}) {
            try { SubscriptionPreferences.parseDays(input); throw new AssertionError("invalid day accepted: " + input); }
            catch (IllegalArgumentException expected) { }
        }
        String address = "https://example.invalid/subscription";
        String hash = SubscriptionReminders.key(address);
        require(SubscriptionReminders.refreshDue(preferences, address, now), "missing metadata triggers header read");
        memory.put("subscription_usage_" + hash, "{\"checkedAt\":" + now + "}");
        require(!SubscriptionReminders.refreshDue(preferences, address, now + 1000), "fresh foreground read avoids duplicate background fetch");
        require(SubscriptionReminders.refreshDue(preferences, address, now + SubscriptionReminderPolicy.CHECK_INTERVAL), "old metadata refreshes before alerting");
        memory.put("subscription_reminder_attempt_" + hash, now + SubscriptionReminderPolicy.CHECK_INTERVAL);
        require(!SubscriptionReminders.refreshDue(preferences, address, now + SubscriptionReminderPolicy.CHECK_INTERVAL + 1000), "offline attempt allows cached alert without spinning");
        require(SubscriptionReminders.refreshDue(preferences, address, now - 1000), "clock rollback rechecks freshness");

        SubscriptionUsage usage = new SubscriptionUsage(10, 20, 100, expiry, now);
        require(Math.abs(usage.remainingRatio() - .7) < 1e-12, "bar encodes remaining, not used quota");
        require(new SubscriptionUsage(10, 20, 0, -1, now).remainingRatio() == -1, "zero total never looks exhausted or unlimited");
        require(new SubscriptionUsage(10, -1, 100, -1, now).remainingRatio() == -1, "missing fields cannot fabricate a bar");
        require(new SubscriptionUsage(100, 20, 100, -1, now).remainingRatio() == 0, "over quota clamps the remaining bar");
        require(usage.expiryLabel(now).equals("10 天后到期"), "days countdown");
        require(usage.expiryLabel(expiry * 1000 - 7_200_000).equals("2 小时后到期"), "hours countdown near expiry");
        require(usage.expiryLabel(expiry * 1000 - 1000).contains("不足 1 小时"), "last hour does not look expired");
        require(usage.expiryLabel(expiry * 1000).contains("已到期"), "expired state wins over remaining bytes");
        require(new SubscriptionUsage(-1, -1, -1, -1, 0).expiryLabel(now).contains("未提供"), "missing expiry is explicit");
        require(usage.freshness(now + day).contains("较旧"), "stale snapshots are labeled");
        System.out.println("PASS: deadline boundaries, missed stages, renewal, persisted suppression, custom settings, unknown metadata, remaining ratios and countdowns.");
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}

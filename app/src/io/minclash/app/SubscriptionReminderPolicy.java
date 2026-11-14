package io.minclash.app;

import java.util.Set;

/** Pure deadline policy. A missed check emits only the most urgent applicable stage. */
final class SubscriptionReminderPolicy {
    static final long DAY = 86_400_000L;
    static final long CHECK_INTERVAL = 6 * 3_600_000L;

    static int stage(long expirySeconds, long now, Set<Integer> days) {
        if (expirySeconds <= 0 || expirySeconds > 253402300799L || days.isEmpty()) return -1;
        long left = expirySeconds * 1000 - now;
        if (left <= 0) return 0;
        return days.stream().filter(day -> day >= 1 && day <= 30 && left <= day * DAY)
            .min(Integer::compareTo).orElse(-1);
    }
    static boolean shouldNotify(long expiry, int stage, String previous) {
        if (stage < 0) return false;
        try {
            String[] parts = previous.split(":", -1);
            if (parts.length == 2 && Long.parseLong(parts[0]) == expiry) {
                int priorStage = Integer.parseInt(parts[1]);
                if (priorStage >= 0 && priorStage <= 30) return stage < priorStage;
            }
        } catch (NumberFormatException invalid) { /* An invalid receipt does not suppress a reminder. */ }
        return true;
    }
    static long nextDelay(long expirySeconds, long now, Set<Integer> days) {
        long delay = CHECK_INTERVAL;
        if (expirySeconds <= 0 || expirySeconds > 253402300799L || days.isEmpty()) return delay;
        long expiry = expirySeconds * 1000;
        if (expiry > now) delay = Math.min(delay, expiry - now);
        for (int day : days) {
            if (day < 1 || day > 30) continue;
            long threshold = expiry - day * DAY;
            if (threshold > now) delay = Math.min(delay, threshold - now);
        }
        return Math.max(1_000, delay);
    }
}

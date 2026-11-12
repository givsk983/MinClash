package io.minclash.app;

/** A cached stopped sample must not end controls during a brief TUN restart. */
final class NotificationLifetime {
    private static final long STOP_GRACE = 30000;
    private static final long RESTORE_GRACE = 180000;
    private long inactiveSince = -1;
    private long restoreUntil;

    void restoring(long now) { restoreUntil = now + RESTORE_GRACE; }

    boolean shouldStop(boolean inactive, long now) {
        if (!inactive) {
            inactiveSince = -1;
            return false;
        }
        if (inactiveSince < 0) inactiveSince = now;
        return now - inactiveSince >= STOP_GRACE && now >= restoreUntil;
    }
}

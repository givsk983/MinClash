package io.minclash.app;

/** A cached stopped sample must not end controls during a brief TUN restart. */
final class NotificationLifetime {
    private static final long STOP_GRACE = 30000;
    private static final long RESTORE_GRACE = 180000;
    private static final long CONTENT_UPDATE_INTERVAL = 9000;
    private long inactiveSince = -1;
    private long restoreUntil;
    private long lastPosted;

    void restoring(long now) { restoreUntil = now + RESTORE_GRACE; }

    void posted(long now) { lastPosted = now; }

    boolean canUpdate(boolean immediate, long now) {
        return immediate || now - lastPosted >= CONTENT_UPDATE_INTERVAL;
    }

    boolean shouldStop(boolean inactive, long now) {
        if (!inactive) {
            inactiveSince = -1;
            return false;
        }
        if (inactiveSince < 0) inactiveSince = now;
        return now - inactiveSince >= STOP_GRACE && now >= restoreUntil;
    }
}

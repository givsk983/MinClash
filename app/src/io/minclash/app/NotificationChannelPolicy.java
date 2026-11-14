package io.minclash.app;

/** Never replace a channel that the user has configured or blocked. */
final class NotificationChannelPolicy {
    static final String LEGACY = "proxy_status";
    static final String CONTROLS = "proxy_controls";
    record Previous(int importance, boolean userImportance, boolean userSound, boolean otherChanges) {}

    static String select(boolean controlsExist, int androidApi, Previous previous) {
        if (controlsExist || previous == null) return CONTROLS;
        // Android 9 cannot tell us whether LOW was explicitly chosen by the user.
        if (androidApi < 29 || previous.importance() != 2 || previous.userImportance()
            || previous.userSound() || previous.otherChanges()) return LEGACY;
        return CONTROLS;
    }

    private NotificationChannelPolicy() {}
}

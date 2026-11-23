package io.minclash.app;

/** Never replace a channel that the user has configured or blocked. */
final class NotificationChannelPolicy {
    static final String LEGACY = "proxy_status";
    static final String CONTROLS = "proxy_controls";
    static final String PRIORITY_CONTROLS = "proxy_controls_priority";
    record Previous(int importance, boolean userImportance, boolean userSound, boolean otherChanges) {}

    static String select(boolean priorityExists, int androidApi, Previous controls, Previous legacy) {
        if (priorityExists) return PRIORITY_CONTROLS;
        if (controls != null) return unchanged(controls, 3, androidApi) ? PRIORITY_CONTROLS : CONTROLS;
        if (legacy != null && !unchanged(legacy, 2, androidApi)) return LEGACY;
        return PRIORITY_CONTROLS;
    }

    private static boolean unchanged(Previous previous, int originalImportance, int androidApi) {
        // Android 9 cannot tell us whether the original importance was explicitly chosen.
        return androidApi >= 29 && previous.importance() == originalImportance && !previous.userImportance()
            && !previous.userSound() && !previous.otherChanges();
    }

    private NotificationChannelPolicy() {}
}

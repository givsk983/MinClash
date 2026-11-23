package io.minclash.app;

public final class NotificationChannelCheck {
    public static void main(String[] args) {
        String priority = NotificationChannelPolicy.PRIORITY_CONTROLS;
        require(selected(36, null, null).equals(priority), "new installation uses the higher priority channel");
        require(selected(36, null, old(2, false, false, false)).equals(priority), "untouched low channel upgrades");
        require(selected(36, old(3, false, false, false), null).equals(priority), "untouched default channel upgrades");
        require(selected(29, old(3, false, false, false), null).equals(priority), "Android 10 supports importance detection");
        verifyPreserved(NotificationChannelPolicy.LEGACY, 2);
        verifyPreserved(NotificationChannelPolicy.CONTROLS, 3);
        require(selected(36, old(0, false, false, false), old(2, false, false, false)).equals(NotificationChannelPolicy.CONTROLS),
            "blocked controls take precedence over an untouched legacy channel");
        require(NotificationChannelPolicy.select(true, 36, old(0, true, true, true), null).equals(priority),
            "keep the same priority channel after system settings change");
        System.out.println("PASS: priority controls, safe migration of both legacy channels and protected user choices.");
    }

    private static void verifyPreserved(String channel, int originalImportance) {
        boolean controls = channel.equals(NotificationChannelPolicy.CONTROLS);
        NotificationChannelPolicy.Previous[] previous = {
            old(0, false, false, false), old(1, false, false, false), old(4, false, false, false),
            old(originalImportance, true, false, false), old(originalImportance, false, true, false),
            old(originalImportance, false, false, true)
        };
        for (NotificationChannelPolicy.Previous choice : previous) {
            require(selected(36, controls ? choice : null, controls ? null : choice).equals(channel),
                "blocked, promoted or customized channel stays in use");
        }
        NotificationChannelPolicy.Previous baseline = old(originalImportance, false, false, false);
        require(selected(28, controls ? baseline : null, controls ? null : baseline).equals(channel),
            "unknown Android 9 choice stays intact");
    }

    private static NotificationChannelPolicy.Previous old(int importance, boolean userImportance, boolean userSound, boolean other) {
        return new NotificationChannelPolicy.Previous(importance, userImportance, userSound, other);
    }
    private static String selected(int api, NotificationChannelPolicy.Previous controls, NotificationChannelPolicy.Previous legacy) {
        return NotificationChannelPolicy.select(false, api, controls, legacy);
    }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
}

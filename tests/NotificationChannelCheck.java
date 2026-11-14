package io.minclash.app;

public final class NotificationChannelCheck {
    public static void main(String[] args) {
        require(selected(36, null).equals(NotificationChannelPolicy.CONTROLS), "new installation gets accessible controls");
        require(selected(36, old(2, false, false, false)).equals(NotificationChannelPolicy.CONTROLS), "untouched low channel upgrades");
        for (int importance : new int[] {0, 1, 3, 4}) {
            require(selected(36, old(importance, false, false, false)).equals(NotificationChannelPolicy.LEGACY),
                "blocked, minimized or promoted legacy channel stays in use");
        }
        require(selected(36, old(2, true, false, false)).equals(NotificationChannelPolicy.LEGACY), "explicit low choice survives");
        require(selected(36, old(2, false, true, false)).equals(NotificationChannelPolicy.LEGACY), "explicit sound choice survives");
        require(selected(36, old(2, false, false, true)).equals(NotificationChannelPolicy.LEGACY), "privacy and other settings survive");
        require(selected(28, old(2, false, false, false)).equals(NotificationChannelPolicy.LEGACY), "unknown Android 9 choice stays intact");
        require(selected(29, old(2, false, false, false)).equals(NotificationChannelPolicy.CONTROLS), "Android 10 supports importance detection");
        require(NotificationChannelPolicy.select(true, 36, old(0, true, true, true)).equals(NotificationChannelPolicy.CONTROLS),
            "after migration use the current channel, including its user changes, without recreating another channel");
        System.out.println("PASS: new controls, legacy migration, blocked/customized channels, Android 9 and stable channel selection.");
    }

    private static NotificationChannelPolicy.Previous old(int importance, boolean userImportance, boolean userSound, boolean other) {
        return new NotificationChannelPolicy.Previous(importance, userImportance, userSound, other);
    }
    private static String selected(int api, NotificationChannelPolicy.Previous old) { return NotificationChannelPolicy.select(false, api, old); }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
}

package io.minclash.app;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Desktop checks for the same decisions used after a lost App or Root reply. */
public final class ProfileApplyRecoveryCheck {
    public static void main(String[] args) throws Exception {
        byte[] oldRouting = bytes("old app config");
        byte[] newRouting = bytes("new app config");
        String oldRoot = ProfileApplyJournal.digest(bytes("old Root config"));
        String newRoot = ProfileApplyJournal.digest(bytes("new Root config"));
        var pending = new ProfileApplyJournal.Pending(oldRoot, newRoot, ProfileApplyJournal.digest(oldRouting),
            newRouting, "00000000000000000000000000000001");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        pending.write(output);
        byte[] encoded = output.toByteArray();
        pending = ProfileApplyJournal.Pending.read(new ByteArrayInputStream(encoded));
        require(Arrays.equals(newRouting, pending.newRouting()), "recover the exact pending App bytes");
        require(pending.resolve(new LiveProfile.RecoveryState(newRoot, true), oldRouting)
            == ProfileApplyJournal.Resolution.COMMIT, "Root changed before the App commit or reply");
        require(pending.resolve(new LiveProfile.RecoveryState(newRoot, false), newRouting)
            == ProfileApplyJournal.Resolution.CLEAR, "both sides committed before the process died");
        require(pending.resolve(new LiveProfile.RecoveryState(oldRoot, true), oldRouting)
            == ProfileApplyJournal.Resolution.STOP_REQUIRED, "uncertain live rules wait for an explicit stop");
        require(pending.resolve(new LiveProfile.RecoveryState(oldRoot, false), oldRouting)
            == ProfileApplyJournal.Resolution.CLEAR, "original disk and confirmed runtime recover without writing");
        require(pending.resolve(new LiveProfile.RecoveryState("-", false), oldRouting)
            == ProfileApplyJournal.Resolution.CONFLICT, "external Root change is preserved");
        require(pending.resolve(new LiveProfile.RecoveryState(newRoot, false), bytes("external App change"))
            == ProfileApplyJournal.Resolution.CONFLICT, "external App change is preserved");
        try {
            ProfileApplyJournal.Pending.read(new ByteArrayInputStream(Arrays.copyOf(encoded, encoded.length - 1)));
            throw new AssertionError("truncated journal was accepted");
        } catch (IOException expected) { }
        System.out.println("PASS: interrupted configuration apply and external-change recovery.");
    }

    private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}

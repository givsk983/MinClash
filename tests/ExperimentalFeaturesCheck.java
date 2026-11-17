package io.minclash.app;

import java.io.IOException;
import java.util.Set;

public final class ExperimentalFeaturesCheck {
    public static void main(String[] args) throws Exception {
        Set<String> saved = Set.of("com.example.game");
        ExperimentalFeatures.checkBypass(false, Set.of(), Set.of());
        ExperimentalFeatures.checkBypass(false, saved, saved);
        ExperimentalFeatures.checkBypass(false, saved, Set.of());
        ExperimentalFeatures.checkBypass(true, Set.of(), saved);
        for (Set<String> before : java.util.List.of(Set.<String>of(), Set.of("com.example.old"))) {
            try {
                ExperimentalFeatures.checkBypass(false, before, saved);
                throw new AssertionError("new bypass must require opt-in, including backup restore");
            } catch (IOException expected) {
                if (!expected.getMessage().contains("开发中功能")) throw new AssertionError("actionable opt-in message");
            }
        }
        if (BackupArchive.portableKey(ExperimentalFeatures.KEY)) throw new AssertionError("opt-in must remain local");
        System.out.println("PASS: opt-in required for new bypass; existing policy can be kept or removed; backups cannot enable experiments.");
    }
}

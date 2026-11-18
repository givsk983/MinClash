package io.minclash.app;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class ExperimentalFeaturesCheck {
    public static void main(String[] args) throws Exception {
        Set<String> saved = Set.of("com.example.game");
        ExperimentalFeatures.checkBypass(false, Set.of());
        ExperimentalFeatures.checkBypass(true, saved);
        try {
            ExperimentalFeatures.checkBypass(false, saved);
            throw new AssertionError("no bypass can be applied while off, including from an old save or backup");
        } catch (IOException expected) {
            if (!expected.getMessage().contains("开发中功能")) throw new AssertionError("actionable opt-in message");
        }

        byte[] source = "dns: {enhanced-mode: fake-ip}\nrules: ['MATCH,DIRECT']\n".getBytes(StandardCharsets.UTF_8);
        Map<String, String> apps = Map.of("com.example.other", "DIRECT");
        List<PersonalRules.Rule> rules = List.of(PersonalRules.create("DOMAIN", "example.com", "REJECT"));
        RoutingStore.State state = new RoutingStore.State(source, apps, null, rules, saved);
        byte[] active = Profile.withPersonalRules(source, apps, rules, saved);
        if (!Profile.hasTunBypass(active)) throw new AssertionError("active profile has a TUN exclusion");

        RoutingStore.State disabled = state.withBypassedApps(Set.of());
        byte[] clean = Profile.withPersonalRules(disabled.source(), disabled.applications(), disabled.rules(), disabled.bypassedApps());
        String yaml = new String(clean, StandardCharsets.UTF_8);
        if (Profile.hasTunBypass(clean) || !yaml.contains("enhanced-mode: fake-ip")
            || !yaml.contains("PROCESS-NAME,com.example.other,DIRECT") || !yaml.contains("DOMAIN,example.com,REJECT")) {
            throw new AssertionError("shutdown removes TUN exclusion and restores source DNS, keeping ordinary rules");
        }
        if (BackupArchive.portableKey(ExperimentalFeatures.KEY)) throw new AssertionError("opt-in must remain local");
        System.out.println("PASS: shutdown removes bypass and restores DNS; bypass and backups still require opt-in.");
    }
}

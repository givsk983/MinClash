package io.minclash.app;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;
import org.yaml.snakeyaml.Yaml;

public final class TunBypassCheck {
    private static final String GAME = "com.example.game";
    private static final byte[] SOURCE = ("mode: rule\ndns:\n  enhanced-mode: fake-ip\n"
        + "  nameserver: ['https://223.5.5.5/dns-query']\n"
        + "proxies: [{name: test, type: socks5, server: 127.0.0.1, port: 9}]\n"
        + "proxy-groups: [{name: US, type: select, proxies: [test]}]\n"
        + "rules: ['MATCH,DIRECT']\n").getBytes(StandardCharsets.UTF_8);

    public static void main(String[] args) throws Exception {
        byte[] source = Profile.normalize(SOURCE);
        Map<String, String> overrides = Map.of("com.example.chat", "US");
        RoutingStore.State state = new RoutingStore.State(source, overrides, "", List.of(), Set.of(GAME));
        byte[] merged = Profile.withPersonalRules(state.source(), state.applications(), state.rules(), state.bypassedApps());
        Map<?, ?> config = yaml(merged);
        require(((Map<?, ?>) config.get("tun")).get("exclude-package").equals(List.of(GAME)), "package exclusion is a YAML sequence");
        require(((Map<?, ?>) config.get("dns")).get("enhanced-mode").equals("redir-host"), "excluded games receive routable real IPs");
        require(((List<?>) config.get("rules")).get(0).equals("PROCESS-NAME,com.example.chat,US"), "other app routing remains intact");
        require(!new String(merged, StandardCharsets.UTF_8).contains("PROCESS-NAME," + GAME), "bypass is not a DIRECT routing rule");
        require(((Map<?, ?>) yaml(source).get("dns")).get("enhanced-mode").equals("fake-ip"), "subscription source stays unchanged");
        for (String mode : List.of("rule", "global", "direct")) {
            Map<?, ?> live = yaml(Profile.forLiveReload(merged, mode, false));
            require(((Map<?, ?>) live.get("tun")).get("exclude-package").equals(List.of(GAME)), "bypass survives all proxy modes");
        }
        byte[] restored = Profile.withPersonalRules(source, overrides, List.of(), Set.of());
        require(!((Map<?, ?>) yaml(restored).get("tun")).containsKey("exclude-package")
            && ((Map<?, ?>) yaml(restored).get("dns")).get("enhanced-mode").equals("fake-ip"), "removal restores normal TUN and original DNS mode");
        RoutingStore.State decoded = RoutingStore.decodeState(RoutingStore.encodeState(state));
        require(decoded.bypassedApps().equals(Set.of(GAME)), "routing state round trip");
        require(state.withSource(source, "another").bypassedApps().equals(Set.of(GAME))
            && state.withApplications(Map.of()).bypassedApps().equals(Set.of(GAME))
            && state.withRules(List.of()).bypassedApps().equals(Set.of(GAME)), "subscription and rule updates retain exclusions");
        JSONObject legacy = RoutingStore.encodeState(state).put("version", 2);
        legacy.remove("bypassedApps");
        require(RoutingStore.decodeState(legacy).bypassedApps().isEmpty(), "version 2 state migration");
        BackupArchive.Data backup = new BackupArchive.Data(state, List.of(), Map.of(), 1, "0.2.7");
        for (char[] password : List.of(new char[0], "password-123".toCharArray())) {
            require(BackupArchive.decode(BackupArchive.encode(backup, password), password).state().bypassedApps().equals(Set.of(GAME)), "plain/encrypted backup keeps package selections");
        }
        JSONObject oldBackup = BackupArchive.json(backup).put("version", 2);
        oldBackup.remove("bypassedApps");
        require(BackupArchive.decode(envelope(oldBackup), new char[0]).state().bypassedApps().isEmpty(), "version 2 backup migration");
        reject(() -> BackupArchive.decode(envelope(BackupArchive.json(backup).put("version", 4)), new char[0]), "unknown backup version");
        reject(() -> TunBypass.parse(new JSONArray().put(GAME).put(GAME)), "duplicate package");
        reject(() -> TunBypass.parse(new JSONArray().put(1)), "non-string package");
        reject(() -> TunBypass.validate(Set.of("com.game\nuidrange 0-0"), Map.of()), "unsafe package");
        reject(() -> TunBypass.validate(Set.of("io.minclash.app"), Map.of()), "own app exclusion");
        reject(() -> Profile.withPersonalRules(source, Map.of(GAME, "DIRECT"), List.of(), Set.of(GAME)), "overlapping policies");
        List<TunBypass.App> apps = List.of(new TunBypass.App(GAME, 12345), new TunBypass.App("com.example.other", 12346));
        TunBypass.check(Set.of(GAME), apps, new ProtectionPolicy());
        TunBypass.check(Set.of("com.example.uninstalled"), apps, new ProtectionPolicy());
        ProtectionPolicy protectedGame = new ProtectionPolicy();
        protectedGame.apps.add(new ProtectionPolicy.App(GAME, 12345, "US"));
        reject(() -> TunBypass.check(Set.of(GAME), apps, protectedGame), "protected package");
        ProtectionPolicy sharedProtection = new ProtectionPolicy();
        sharedProtection.apps.add(new ProtectionPolicy.App("com.example.protected", 112345, "US"));
        reject(() -> TunBypass.check(Set.of(GAME), apps, sharedProtection), "protected same app ID in another Android user");
        ProtectionPolicy dns = new ProtectionPolicy(); dns.strictDns = true;
        reject(() -> TunBypass.check(Set.of(GAME), apps, dns), "strict DNS conflict");
        reject(() -> TunBypass.check(Set.of(GAME), List.of(new TunBypass.App(GAME, 1000)), new ProtectionPolicy()), "system UID");
        reject(() -> TunBypass.check(Set.of(GAME), List.of(new TunBypass.App(GAME, 12345), new TunBypass.App("com.example.shared", 12345)), new ProtectionPolicy()), "shared UID cannot be independently bypassed");
        Module.Status ready = Module.parseStatus("state=running\nenabled=1\ncontrol=1\nvalidate=1\nhealth=1\ncore_running=1\ntun4=1\ncontroller_ready=1\n");
        require(RoutingStore.canReload(ready, state, state.withApplications(Map.of()), merged), "ordinary rule edits retain existing bypass without restarting TUN");
        require(!RoutingStore.canReload(ready, state, state.withBypassedApps(Set.of()), merged), "changing exclusions requires full TUN recreation");
        require(!RoutingStore.canReload(ready, state, state, merged, true), "explicit reapply rebuilds routes even when choices and config are unchanged");
        List<AppRouting.Choice> choices = AppRouting.choices(List.of("US", "DIRECT", "绕过 TUN", "DIRECT"), true);
        require(choices.get(0).equals(new AppRouting.Choice("", false))
            && choices.get(1).equals(new AppRouting.Choice("DIRECT", false))
            && choices.get(2).equals(new AppRouting.Choice("", true)), "follow, direct and bypass are ahead of subscription groups");
        require(choices.get(3).target().equals("US") && !choices.get(3).bypassed()
            && choices.get(4).target().equals("绕过 TUN") && !choices.get(4).bypassed(), "proxy identity survives bypass insertion and group-name collisions");
        require(choices.size() == 5 && choices.indexOf(new AppRouting.Choice("US", false)) == 3, "duplicates cannot shift the saved proxy selection");
        require(AppRouting.choices(List.of("DIRECT", "US"), false).stream().noneMatch(AppRouting.Choice::bypassed), "system applications do not offer bypass");
        AppRouting.Entry entry = new AppRouting.Entry("Game", GAME, "", true, false, true, true);
        require(entry.assigned() && AppCategories.all(List.of()).stream().filter(c -> c.id().equals(AppCategories.ASSIGNED)).findFirst().orElseThrow().includes(entry), "bypassed game is shown under assigned apps");
        SubscriptionPreview preview = SubscriptionPreview.compare(new SubscriptionCatalog.Entry("new", "https://example.test/"), source, 0, false, state, List.of());
        require(!preview.conflicts() && preview.summary().contains("绕过 TUN"), "subscription preview retains exclusions without a fake proxy target");
        if (args.length > 0) {
            Files.createDirectories(Path.of(args[0]));
            Files.write(Path.of(args[0], "tun-bypass.yaml"), merged);
            Files.write(Path.of(args[0], "tun-restored.yaml"), restored);
        }
        System.out.println("PASS: package bypass, real-IP DNS, all modes, state/backup migration, protection/shared-UID conflicts, choice identity and explicit TUN recreation.");
    }

    private static byte[] envelope(JSONObject json) throws Exception {
        byte[] payload = json.toString().getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write("MINCLASH-BACKUP\n".getBytes(StandardCharsets.US_ASCII)); output.write(1); output.write(0);
        output.write(MessageDigest.getInstance("SHA-256").digest(payload)); output.write(payload);
        return output.toByteArray();
    }
    private static Map<?, ?> yaml(byte[] bytes) { return new Yaml().load(new String(bytes, StandardCharsets.UTF_8)); }
    private interface Check { void run() throws Exception; }
    private static void reject(Check check, String message) throws Exception {
        try { check.run(); throw new AssertionError("accepted " + message); }
        catch (IOException expected) { }
    }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
}

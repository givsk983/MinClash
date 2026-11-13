package io.minclash.app;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;
import org.yaml.snakeyaml.Yaml;

public final class PersonalRulesCheck {
    public static void main(String[] args) throws Exception {
        byte[] source = Profile.normalize(("proxies: [{name: node, type: socks5, server: 127.0.0.1, port: 9}]\n"
            + "proxy-groups: [{name: US, type: select, proxies: [node]}]\nrules: ['MATCH,DIRECT']\n").getBytes(StandardCharsets.UTF_8));
        PersonalRules.Rule domain = PersonalRules.create("DOMAIN-SUFFIX", "Example.COM.", "US");
        PersonalRules.Rule ip = PersonalRules.create("IP-CIDR", "192.0.2.79/24", "DIRECT");
        PersonalRules.Rule v6 = PersonalRules.create("IP-CIDR6", "2001:db8::1234/32", "REJECT");
        require(domain.value().equals("example.com") && ip.value().equals("192.0.2.0/24"), "domain and network canonicalization");
        require(PersonalRules.create("DOMAIN", "例子.测试", "US").value().startsWith("xn--"), "IDN support");
        require(PersonalRules.create("IP-CIDR", "192.0.2.1", "DIRECT").value().endsWith("/32"), "single IPv4 host");
        for (String invalid : List.of("https://example.com", "example.com/path", "*.example.com", "example.com,REJECT", "a\nb.com", "a..com")) {
            reject(() -> PersonalRules.create("DOMAIN", invalid, "US"));
        }
        for (String invalid : List.of("192.0.2.256", "127.1", "192.0.2.1/33", "192.0.2.1/-1", "example.com/24", "001.2.3.4")) {
            reject(() -> PersonalRules.create("IP-CIDR", invalid, "US"));
        }
        for (String invalid : List.of("example.com", "2001:db8::/129", "fe80::1%wlan0", "2001:::1", "1:2")) {
            reject(() -> PersonalRules.create("IP-CIDR6", invalid, "US"));
        }
        reject(() -> PersonalRules.create("DOMAIN", "example.com", "US\nMATCH,DIRECT"));
        List<PersonalRules.Rule> personal = List.of(domain, ip, v6);
        Map<String, String> apps = Map.of("com.example.app", "US");
        byte[] merged = Profile.withPersonalRules(source, apps, personal);
        Map<?, ?> yaml = new Yaml().load(new String(merged, StandardCharsets.UTF_8));
        List<?> rules = (List<?>) yaml.get("rules");
        require(rules.get(0).equals("PROCESS-NAME,com.example.app,US") && rules.get(1).equals(domain.line())
            && rules.get(2).equals(ip.line()) && rules.get(3).equals(v6.line()) && rules.get(4).equals("MATCH,DIRECT"), "app > ordered personal > subscription priority");
        require(ip.line().endsWith(",no-resolve"), "IP rules do not force DNS lookup");
        require(((List<?>) ((Map<?, ?>) new Yaml().load(new String(source, StandardCharsets.UTF_8))).get("rules")).size() == 1, "source kept separate");
        reject(() -> Profile.withPersonalRules(source, apps, List.of(domain, domain)));
        reject(() -> Profile.withPersonalRules(source, apps, List.of(new PersonalRules.Rule("DOMAIN", "example.com", "missing"))));
        RoutingStore.State state = new RoutingStore.State(source, apps, "https://example.test/config", personal);
        RoutingStore.State decoded = RoutingStore.decodeState(RoutingStore.encodeState(state));
        require(decoded.rules().equals(personal) && decoded.applications().equals(apps), "new persisted state preserves both kinds of rules");
        JSONObject legacy = RoutingStore.encodeState(state).put("version", 1);
        legacy.remove("personalRules");
        require(RoutingStore.decodeState(legacy).rules().isEmpty(), "legacy state migration");
        require(state.withSource(source, "https://another.test/").rules().equals(personal)
            && state.withApplications(Map.of()).rules().equals(personal), "subscription refresh and app edits preserve personal rules");
        require(state.withRules(List.of()).applications().equals(apps), "personal rule deletion preserves app settings");
        byte[] changed = Profile.normalize(new String(source, StandardCharsets.UTF_8).replace("US", "HK").getBytes(StandardCharsets.UTF_8));
        SubscriptionPreview preview = SubscriptionPreview.compare(new SubscriptionCatalog.Entry("new", "https://another.test/"), changed, 0, false,
            state.withApplications(Map.of()), List.of());
        require(preview.conflicts() && preview.summary().contains("example.com"), "missing target prevents subscription replacement");
        BackupArchive.Data data = new BackupArchive.Data(state, List.of(new SubscriptionCatalog.Entry("test", state.subscription())), Map.of(), 1, "0.1.8");
        require(BackupArchive.decode(BackupArchive.encode(data, new char[0]), new char[0]).state().rules().equals(personal), "backup includes personal rules");
        JSONObject oldBackup = BackupArchive.json(data).put("version", 1);
        oldBackup.remove("personalRules");
        byte[] payload = oldBackup.toString().getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream envelope = new ByteArrayOutputStream();
        envelope.write("MINCLASH-BACKUP\n".getBytes(StandardCharsets.US_ASCII)); envelope.write(1); envelope.write(0);
        envelope.write(MessageDigest.getInstance("SHA-256").digest(payload)); envelope.write(payload);
        require(BackupArchive.decode(envelope.toByteArray(), new char[0]).state().rules().isEmpty(), "0.1.7 backup remains readable");
        Files.createDirectories(Path.of(args[0]));
        Files.write(Path.of(args[0], "personal-rules.yaml"), merged);
        Files.write(Path.of(args[0], "personal-rules-no-apps.yaml"), Profile.withPersonalRules(source, Map.of(), personal));
        System.out.println("PASS: domain/IP validation, priority and ordering, state migration, subscription conflicts, edit preservation and old/new backups.");
    }
    private interface Attempt { void run() throws Exception; }
    private static void reject(Attempt attempt) throws Exception {
        try { attempt.run(); throw new AssertionError("invalid rule accepted"); } catch (IOException expected) { }
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}

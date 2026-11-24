package io.minclash.app;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.yaml.snakeyaml.Yaml;

public final class AppRoutingCheck {
    private static final String SOURCE = """
        mode: rule
        find-process-mode: off
        proxies:
          - {name: test-us, type: socks5, server: 127.0.0.1, port: 9}
          - {name: test-hk, type: socks5, server: 127.0.0.1, port: 9}
        proxy-groups:
          - {name: US, type: select, proxies: [test-us]}
          - {name: OpenAI, type: select, proxies: [test-hk]}
        rules:
          - DOMAIN-SUFFIX,claude.ai,OpenAI
          - DOMAIN-SUFFIX,anthropic.com,OpenAI
          - MATCH,OpenAI
        """;

    private static final String NODES = """
        proxies:
          - {name: 德国 VPS, type: socks5, server: 127.0.0.1, port: 9}
          - {name: 日本 VPS, type: socks5, server: 127.0.0.1, port: 9}
          - {name: 旁路备用, type: socks5, server: 127.0.0.1, port: 9}
          - {name: 本地直连, type: direct}
        """;

    private static final String GLOBAL_ONLY = NODES + """
        proxy-groups:
          - {name: GLOBAL, type: select, proxies: [DIRECT, 日本 VPS, 本地直连, 德国 VPS]}
        """;

    private static final List<String> REMOTE_NODES = List.of("德国 VPS", "日本 VPS", "旁路备用");
    private static final String EMPTY_PROXIES = NODES + """
        proxy-groups:
          - {name: PROXIES, type: select, disable-udp: true, proxies: []}
          - {name: 日本, type: select, proxies: [日本 VPS]}
        rules:
          - DOMAIN-SUFFIX,example.org,日本
          - MATCH,PROXIES
        """;

    public static void main(String[] args) throws Exception {
        byte[] base = Profile.normalize(bytes(SOURCE));
        Map<String, String> personal = Map.of("com.anthropic.claude", "US", "com.ss.android.ugc.aweme", "DIRECT");
        byte[] effective = Profile.withAppRules(base, personal);
        Map<?, ?> parsed = parse(effective);
        List<?> rules = (List<?>) parsed.get("rules");
        require(rules.get(0).equals("PROCESS-NAME,com.anthropic.claude,US"), "personal US precedes airport HK");
        require(rules.get(1).equals("PROCESS-NAME,com.ss.android.ugc.aweme,DIRECT"), "app direct precedes fallback");
        require(rules.get(2).equals("DOMAIN-SUFFIX,claude.ai,OpenAI"), "airport rules preserved");
        require(parsed.get("find-process-mode").equals("always"), "process matching enabled despite imported off");
        require(((List<?>) parse(base).get("rules")).size() == 3, "saved source stays independent");
        require(Profile.routingTargets(base).equals(List.of("DIRECT", "US", "OpenAI")), "targets from source groups");

        byte[] refreshed = Profile.withAppRules(bytes(SOURCE.replace("MATCH,OpenAI", "MATCH,US")), personal);
        List<?> refreshedRules = (List<?>) parse(refreshed).get("rules");
        require(refreshedRules.size() == 5 && refreshedRules.get(4).equals("MATCH,US"), "update retains personal and new airport rules");
        List<?> removed = (List<?>) parse(Profile.withAppRules(base, Map.of())).get("rules");
        require(removed.size() == 3 && removed.get(0).equals("DOMAIN-SUFFIX,claude.ai,OpenAI"), "follow rules removes overrides");
        reject(base, Map.of("com.anthropic.claude", "missing"));
        reject(base, Map.of("com.bad,package", "US"));
        reject(bytes(SOURCE.replace("name: US,", "name: renamed,")), personal);
        byte[] nodesOnly = Profile.withAppRules(bytes("proxies: [{name: test, type: socks5, server: 127.0.0.1, port: 9}]"),
            Map.of("com.example.app", "MinClash"));
        require(((List<?>) parse(nodesOnly).get("rules")).equals(List.of("PROCESS-NAME,com.example.app,MinClash", "MATCH,MinClash")),
            "nodes-only imports retain generated fallback");
        // Keep Unicode group names intact when serializing the generated rules.
        byte[] unicode = Profile.withAppRules(bytes(SOURCE.replace("name: US,", "name: 美国,")), Map.of("com.example.app", "美国"));
        require(((List<?>) parse(unicode).get("rules")).get(0).equals("PROCESS-NAME,com.example.app,美国"), "Unicode group names");
        byte[] loopback = Profile.normalize(bytes(SOURCE + """
            dns:
              default-nameserver: [127.0.0.1]
              nameserver: [udp://127.0.0.2:7874]
              proxy-server-nameserver: ['https://[::1]:443/dns-query', '127.0.0.1:53']
              proxy-server-nameserver-policy:
                '+.node.example': 'tcp://localhost:5353'
              nameserver-policy:
                '+.home.arpa': 'udp://127.0.0.1:53'
            """));
        byte[] repaired = Profile.withAppRules(loopback, personal);
        Map<?, ?> dns = (Map<?, ?>) parse(repaired).get("dns");
        List<String> direct = List.of("https://223.5.5.5/dns-query#DIRECT", "https://1.1.1.1/dns-query#DIRECT");
        require(direct.equals(dns.get("nameserver")) && direct.equals(dns.get("default-nameserver"))
            && direct.equals(dns.get("proxy-server-nameserver")), "replace loopback-only DNS with direct HTTPS");
        require(direct.equals(((Map<?, ?>) dns.get("proxy-server-nameserver-policy")).get("+.node.example")), "repair node DNS policy");
        require("udp://127.0.0.1:53".equals(((Map<?, ?>) dns.get("nameserver-policy")).get("+.home.arpa")),
            "preserve application domain policies");
        require(Profile.usesLoopbackProxyDns(loopback) && !Profile.usesLoopbackProxyDns(repaired)
            && Profile.repairDns(repaired) == repaired, "source stays independent and applied repair is idempotent");
        byte[] mixed = Profile.normalize(bytes(SOURCE + "dns: {nameserver: ['127.0.0.1', 'https://dns.example/dns-query']}\n"));
        require(Profile.repairDns(mixed) == mixed, "preserve mixed DNS");
        require(parse(repaired).get("rules").equals(rules), "DNS repair retains routing rules");
        AppRouting.Entry chrome = new AppRouting.Entry("Chrome", "com.android.chrome", "", true, false, true);
        AppRouting.Entry play = new AppRouting.Entry("Play", "com.android.vending", "", true, true, false);
        AppRouting.Entry system = new AppRouting.Entry("Settings", "com.android.settings", "", true, true, false);
        AppCategories.Category custom = new AppCategories.Category("custom:test", "常用", Set.of(chrome.packageName()));
        List<AppCategories.Category> categories = AppCategories.all(List.of(custom));
        AppCategories.Category systemCategory = categories.stream().filter(category -> category.id().equals("system")).findFirst().orElseThrow();
        AppCategories.Category googleCategory = categories.stream().filter(category -> category.id().equals("google")).findFirst().orElseThrow();
        require(systemCategory.includes(system) && systemCategory.includes(play) && !systemCategory.includes(chrome), "system package flags");
        require(googleCategory.includes(chrome) && googleCategory.includes(play) && !googleCategory.includes(system), "Google package matching");
        require(custom.includes(chrome) && !custom.includes(play), "custom category membership");
        Files.createDirectories(Path.of(args[0]));
        Files.write(Path.of(args[0], "app-routing.yaml"), effective);
        Files.write(Path.of(args[0], "app-routing-nodes-only.yaml"), nodesOnly);
        Files.write(Path.of(args[0], "dns-repaired.yaml"), repaired);
        Files.write(Path.of(args[0], "dns-via-proxy.yaml"), checkProxyDns());
        Files.write(Path.of(args[0], "app-routing-global-only.yaml"), checkGlobalOnly());
        Files.write(Path.of(args[0], "app-routing-empty-proxies.yaml"), checkMissingOrEmptyGroups());
        Files.write(Path.of(args[0], "app-routing-provider-only.yaml"), checkProviderOnly());
        Files.write(Path.of(args[0], "app-routing-protection-alias.yaml"), checkProtectionAlias());
        System.out.println("PASS: routing overrides, DNS repair, subscription refresh, missing/empty main proxy groups and app categories.");
    }

    private static byte[] checkProxyDns() throws Exception {
        for (String dns : List.of("", "dns:\n", "dns: {}\n", "dns: {enable: true}\n",
            "dns: {nameserver: [], fallback: null, nameserver-policy: {}}\n")) {
            require(Profile.canUseProxyDns(bytes(SOURCE + dns)), "empty or managed-only DNS can use a proxy upstream");
        }
        for (String dns : List.of("nameserver: [system]", "fallback: [8.8.8.8]", "direct-nameserver: [223.5.5.5]",
            "default-nameserver: [1.1.1.1]", "proxy-server-nameserver: [1.1.1.1]", "nameserver-policy: {example.org: system}",
            "proxy-server-nameserver-policy: {example.org: system}")) {
            byte[] configured = bytes(SOURCE + "dns: {" + dns + "}\n");
            require(!Profile.canUseProxyDns(configured), "explicit DNS configuration is preserved");
            rejectProxyDns(configured, "US");
        }
        byte[] inherited = bytes(SOURCE + """
            x-dns: &upstreams {nameserver: [system]}
            dns: {<<: *upstreams}
            """);
        require(!Profile.canUseProxyDns(inherited), "a YAML merge does not hide an explicit upstream");
        rejectProxyDns(inherited, "US");

        String target = "美国 + e\u0301 %";
        byte[] source = Profile.normalize(bytes(SOURCE.replace("name: US,", "name: '" + target + "',") + """
            dns:
              enhanced-mode: fake-ip
              fake-ip-filter: ['*.lan']
              prefer-h3: true
            """));
        byte[] result = Profile.withProxyDns(source, target);
        Map<?, ?> original = parse(source);
        Map<?, ?> updated = parse(result);
        Map<?, ?> dns = (Map<?, ?>) updated.get("dns");
        List<?> upstream = (List<?>) dns.get("nameserver");
        URI endpoint = URI.create(upstream.get(0).toString());
        require(upstream.size() == 1 && upstream.equals(dns.get("direct-nameserver"))
            && "https".equals(endpoint.getScheme()) && "1.1.1.1".equals(endpoint.getHost())
            && target.equals(endpoint.getFragment()), "all app DNS uses the exact Unicode proxy name over DoH");
        require(dns.get("proxy-server-nameserver").equals(dns.get("default-nameserver"))
            && dns.get("proxy-server-nameserver").equals(List.of("https://223.5.5.5/dns-query#DIRECT", "https://1.1.1.1/dns-query#DIRECT")),
            "node resolution has independent encrypted bootstrap");
        require(Boolean.FALSE.equals(dns.get("prefer-h3")) && "fake-ip".equals(dns.get("enhanced-mode"))
            && List.of("*.lan").equals(dns.get("fake-ip-filter")), "prefer TCP and preserve compatible DNS settings");
        require(updated.get("rules").equals(original.get("rules")) && updated.get("proxy-groups").equals(original.get("proxy-groups"))
            && !((Map<?, ?>) original.get("dns")).containsKey("nameserver"), "routing and saved source remain independent");

        byte[] groups = bytes(NODES + """
            x-group: &direct-members {type: select, proxies: [DIRECT, 日本 VPS]}
            proxy-providers:
              remote: {type: file, path: ./remote.yaml}
            proxy-groups:
              - {name: Mixed, type: select, proxies: [DIRECT, 日本 VPS]}
              - {name: Merged, <<: *direct-members}
              - {name: Nested, type: select, proxies: [Mixed]}
              - {name: Local, type: select, proxies: [本地直连, 日本 VPS]}
              - {name: Safe, type: select, proxies: [REJECT, 日本 VPS]}
              - {name: Filtered, type: select, proxies: [日本 VPS], exclude-filter: 日本}
              - {name: Remote, type: select, use: [remote]}
              - {name: RemoteSafe, type: select, use: [remote], exclude-type: Direct, empty-fallback: REJECT}
              - {name: RULES, type: select, proxies: [日本 VPS]}
              - {name: 'DNS=ef', type: select, proxies: [日本 VPS]}
              - {name: 'DNS&ef', type: select, proxies: [日本 VPS]}
              - {name: 'DNS#ef', type: select, proxies: [日本 VPS]}
            """);
        require(Profile.proxyDnsTargets(groups).equals(List.of("Safe", "RemoteSafe", "德国 VPS", "日本 VPS", "旁路备用")),
            "groups with a direct path or reserved fragment syntax are not offered");
        for (String invalid : List.of("DIRECT", "Mixed", "Nested", "unknown", "RULES", "DNS=ef", "DNS&ef", "DNS#ef")) {
            rejectProxyDns(groups, invalid);
        }
        require(Profile.proxyDnsTargets(Profile.normalize(bytes(SOURCE))).contains("test-us"), "named static nodes are valid DNS outlets");
        return result;
    }

    private static void rejectProxyDns(byte[] source, String target) throws Exception {
        try { Profile.withProxyDns(source, target); throw new AssertionError("Invalid DNS overlay accepted"); }
        catch (Profile.ProxyDnsConflict expected) { }
    }

    private static byte[] checkGlobalOnly() throws Exception {
        byte[] global = Profile.normalize(bytes(GLOBAL_ONLY));
        Profile.LocalYaml prepared = Profile.prepareLocalYaml(global);
        String added = prepared.addedGroup();
        require(added.equals("PROXIES"), "GLOBAL-only import creates a recognized main proxy group");
        Map<?, ?> configured = parse(prepared.source());
        List<?> originalGroups = (List<?>) parse(global).get("proxy-groups");
        List<?> updatedGroups = (List<?>) configured.get("proxy-groups");
        require(updatedGroups.size() == 2 && updatedGroups.contains(originalGroups.get(0)), "GLOBAL and its DIRECT choice stay intact");
        require(updatedGroups.contains(Map.of("name", added, "type", "select", "proxies", REMOTE_NODES)),
            "the new group includes all declared proxies in definition order, including a node outside GLOBAL");
        require(configured.get("proxies").equals(parse(global).get("proxies")), "the original node definitions stay intact");
        require(prepared.addedCatchAll() && configured.get("rules").equals(List.of("MATCH," + added)),
            "the default rule mode gets a fallback when rules are missing");
        require(Profile.prepareLocalYaml(Profile.normalize(bytes("mode: rule\n" + GLOBAL_ONLY))).addedCatchAll(),
            "explicit rule mode also gets a fallback when rules are missing");
        require(ProtectionPolicy.safeTarget(added) && Profile.routingTargets(prepared.source()).contains(added),
            "the generated group qualifies as a protection target");
        Map<?, ?> withOverrides = parse(Profile.withAppRules(prepared.source(), Map.of("com.example.app", added)));
        require(withOverrides.get("rules").equals(List.of("PROCESS-NAME,com.example.app," + added, "MATCH," + added)),
            "app routing can use the generated group");

        byte[] rules = Profile.normalize(bytes("mode: rule\n" + GLOBAL_ONLY.replace("name: GLOBAL", "name: global")
            + "rules:\n  - DOMAIN-SUFFIX,example.org,DIRECT\n  - MATCH,global\n"));
        Profile.LocalYaml existingRules = Profile.prepareLocalYaml(rules);
        require(!existingRules.addedGroup().isEmpty() && !existingRules.addedCatchAll()
            && parse(existingRules.source()).get("rules").equals(parse(rules).get("rules")),
            "lowercase global qualifies while explicit rules retain their order and destinations");
        for (String mode : List.of("global", "direct")) {
            byte[] explicit = Profile.normalize(bytes("mode: " + mode + "\n" + GLOBAL_ONLY));
            Profile.LocalYaml result = Profile.prepareLocalYaml(explicit);
            Map<?, ?> parsed = parse(result.source());
            require(!result.addedGroup().isEmpty() && !result.addedCatchAll() && mode.equals(parsed.get("mode")) && !parsed.containsKey("rules"),
                "explicit " + mode + " mode does not receive a routing rule");
        }

        byte[] collidingNames = Profile.normalize(bytes(GLOBAL_ONLY.replace("德国 VPS", "proxies").replace("日本 VPS", "MINCLASH")));
        Profile.LocalYaml resolved = Profile.prepareLocalYaml(collidingNames);
        require(resolved.addedGroup().equals("MinClash 2") && ProxyGroupSection.of(resolved.addedGroup()) == ProxyGroupSection.MAIN,
            "generated group avoids case-insensitive node names and remains discoverable as a main group");
        byte[] providerName = Profile.normalize(bytes(GLOBAL_ONLY + "proxy-providers:\n  proxies: {type: file, path: ./providers.yaml}\n"));
        require(Profile.prepareLocalYaml(providerName).addedGroup().equals("MinClash"), "generated group avoids case-insensitive provider names");

        String members = "proxies: [DIRECT, 日本 VPS, 本地直连, 德国 VPS]";
        byte[] includeAll = Profile.normalize(bytes(GLOBAL_ONLY.replace(members, "include-all-proxies: true")));
        Profile.LocalYaml included = Profile.prepareLocalYaml(includeAll);
        List<?> includedGroups = (List<?>) parse(included.source()).get("proxy-groups");
        require(includedGroups.contains(Map.of("name", added, "type", "select", "proxies", REMOTE_NODES)),
            "unfiltered include-all-proxies uses remote nodes from the YAML in declaration order");
        byte[] filtered = Profile.normalize(bytes(GLOBAL_ONLY.replace(members, members + ", exclude-filter: 日本")));
        Profile.LocalYaml filteredResult = Profile.prepareLocalYaml(filtered);
        require(group(parse(filteredResult.source()), "GLOBAL").equals(group(parse(filtered), "GLOBAL"))
            && group(parse(filteredResult.source()), added).get("proxies").equals(REMOTE_NODES),
            "GLOBAL keeps its restrictions while the new main group includes the declared nodes");
        byte[] noUsableMembers = Profile.normalize(bytes("""
            proxies: [{name: 本地直连, type: direct}]
            proxy-groups: [{name: GLOBAL, type: select, proxies: [DIRECT, 本地直连]}]
            """));
        requireUnchangedLocal(noUsableMembers, "direct-only GLOBAL is preserved");
        requireUnchangedLocal(prepared.source(), "preparation is idempotent");
        return prepared.source();
    }

    private static byte[] checkMissingOrEmptyGroups() throws Exception {
        for (String groups : List.of("", "proxy-groups: []\n")) {
            Profile.LocalYaml result = Profile.prepareLocalYaml(bytes(NODES + groups));
            require(result.addedGroup().equals("PROXIES") && result.addedCatchAll()
                && group(parse(result.source()), "PROXIES").get("proxies").equals(REMOTE_NODES),
                "node-only imports gain PROXIES even when proxy-groups is absent or empty");
        }
        for (String source : List.of(EMPTY_PROXIES, EMPTY_PROXIES.replace(", proxies: []", ""))) {
            Profile.LocalYaml result = Profile.prepareLocalYaml(bytes(source));
            Map<?, ?> parsed = parse(result.source());
            Map<?, ?> original = parse(bytes(source));
            Map<?, ?> main = group(parsed, "PROXIES");
            require(result.addedGroup().equals("PROXIES") && !result.addedCatchAll()
                && main.get("proxies").equals(REMOTE_NODES) && Boolean.TRUE.equals(main.get("disable-udp"))
                && "select".equals(main.get("type")), "empty or missing membership is filled in place");
            require(group(parsed, "日本").equals(group(original, "日本")) && parsed.get("rules").equals(original.get("rules")),
                "filling keeps other groups and existing rules intact");
        }

        for (String membership : List.of("proxies: [日本 VPS]", "include-all-proxies: true")) {
            String existing = NODES + "proxy-groups: [{name: PROXIES, type: select, " + membership + "}]\n";
            requireCatchAllOnly(existing, "PROXIES");
            requireCatchAllOnly("mode: rule\n" + existing, "PROXIES");
            for (String rules : List.of("rules: []\n", "rules: null\n", "rules: ['MATCH,DIRECT']\n")) {
                requireUnchangedLocal(Profile.normalize(bytes(existing + rules)), "explicit rules are preserved");
            }
            for (String mode : List.of("direct", "global")) {
                requireUnchangedLocal(Profile.normalize(bytes("mode: " + mode + "\n" + existing)), "explicit modes are preserved");
            }
        }
        String providerGroup = NODES + """
            proxy-providers:
              providers: {type: file, path: ./providers.yaml}
            proxy-groups:
              - {name: PROXIES, type: select, proxies: [], use: [providers]}
            """;
        requireCatchAllOnly(providerGroup, "PROXIES");
        requireCatchAllOnly(NODES + """
            proxy-groups:
              - {name: 节点选择, type: select, proxies: [日本]}
              - {name: 日本, type: select, proxies: [日本 VPS]}
            """, "节点选择");
        requireUnchangedLocal(Profile.normalize(bytes(NODES + """
            proxy-groups:
              - {name: PROXIES, type: select, proxies: [DIRECT, 本地直连]}
            """)), "a direct-only selection stays direct even when there are unrelated remote nodes");

        Profile.LocalYaml onlyRegionalGroups = Profile.prepareLocalYaml(bytes(SOURCE));
        require(onlyRegionalGroups.addedGroup().equals("PROXIES") && !onlyRegionalGroups.addedCatchAll()
            && group(parse(onlyRegionalGroups.source()), "OpenAI").equals(group(parse(bytes(SOURCE)), "OpenAI")),
            "a profile with only service or regional groups gains a main group without replacing its routing");
        return Profile.prepareLocalYaml(bytes(EMPTY_PROXIES)).source();
    }

    private static byte[] checkProviderOnly() throws Exception {
        Profile.LocalYaml result = Profile.prepareLocalYaml(bytes("""
            proxies: []
            proxy-providers:
              Japan:
                type: inline
                payload:
                  - {name: 日本 VPS, type: socks5, server: 127.0.0.1, port: 9}
                  - {name: 本地直连, type: direct}
            """));
        Map<?, ?> configured = parse(result.source());
        Map<?, ?> group = group(configured, "PROXIES");
        require(result.addedGroup().equals("PROXIES") && group.get("use").equals(List.of("Japan"))
            && List.of(group.get("exclude-type").toString().split("\\|")).contains("Direct")
            && "REJECT".equals(group.get("empty-fallback")),
            "a provider-only source gets a group that excludes direct adapters and rejects when empty");
        return result.source();
    }

    private static byte[] checkProtectionAlias() throws Exception {
        byte[] source = Profile.normalize(bytes(SOURCE + """
            proxy-providers:
              source-provider: {type: file, path: ./providers.yaml}
            """));
        require(Profile.protectionAliasTargets(source, "PROXIES").equals(List.of("US", "OpenAI")),
            "protection alias choices are existing proxy groups");
        byte[] result = Profile.withProtectionAlias(source, "PROXIES", "OpenAI");
        Map<?, ?> original = parse(source);
        Map<?, ?> updated = parse(result);
        require(updated.get("rules").equals(original.get("rules")) && updated.get("proxies").equals(original.get("proxies"))
            && updated.get("proxy-providers").equals(original.get("proxy-providers"))
            && group(updated, "OpenAI").equals(group(original, "OpenAI")), "adding an alias preserves subscription data");
        require(group(updated, "PROXIES").equals(Map.of("name", "PROXIES", "type", "select", "hidden", true,
            "proxies", List.of("OpenAI"), "empty-fallback", "REJECT")), "alias is a hidden fail-closed selector");
        require(Profile.routingTargets(Profile.normalize(result)).contains("PROXIES"), "alias survives profile normalization");
        for (String alias : List.of("OpenAI", "test-us", "source-provider", "direct", "PASS-RULE", "default")) {
            rejectAlias(source, alias, "US");
        }
        for (String target : List.of("DIRECT", "test-us", "unknown")) rejectAlias(source, "PROXIES", target);

        byte[] cyclic = Profile.normalize(bytes(NODES + """
            proxy-groups:
              - {name: Main, type: select, proxies: [日本]}
              - {name: 日本, type: select, proxies: [PROXIES, 日本 VPS]}
              - {name: DirectOnly, type: select, proxies: [DIRECT, 本地直连]}
              - {name: Safe, type: select, proxies: [ドイツ]}
              - {name: ドイツ, type: select, proxies: [德国 VPS]}
            rules: ['MATCH,DIRECT']
            """));
        require(Profile.protectionAliasTargets(cyclic, "PROXIES").equals(List.of("Safe", "ドイツ")),
            "choices exclude direct-only groups and transitive references that would make the alias cycle");
        rejectAlias(cyclic, "PROXIES", "Main");
        rejectAlias(cyclic, "PROXIES", "DirectOnly");
        return result;
    }

    private static void rejectAlias(byte[] source, String alias, String target) throws Exception {
        try { Profile.withProtectionAlias(source, alias, target); throw new AssertionError("Invalid protection alias accepted"); }
        catch (IOException expected) { }
    }

    private static Map<?, ?> group(Map<?, ?> profile, String name) {
        for (Object value : (List<?>) profile.get("proxy-groups")) {
            if (value instanceof Map<?, ?> group && name.equals(group.get("name"))) return group;
        }
        throw new AssertionError("Missing group " + name);
    }

    private static void requireUnchangedLocal(byte[] source, String reason) throws Exception {
        Profile.LocalYaml result = Profile.prepareLocalYaml(source);
        require(result.addedGroup().isEmpty() && !result.addedCatchAll() && java.util.Arrays.equals(result.source(), source), reason);
    }

    private static void requireCatchAllOnly(String source, String target) throws Exception {
        Profile.LocalYaml result = Profile.prepareLocalYaml(bytes(source));
        Map<?, ?> configured = parse(result.source());
        require(result.addedGroup().isEmpty() && result.addedCatchAll()
            && configured.get("rules").equals(List.of("MATCH," + target))
            && configured.get("proxy-groups").equals(parse(bytes(source)).get("proxy-groups")),
            "missing rules get a catch-all without changing the populated proxy group");
        requireUnchangedLocal(result.source(), "a generated catch-all is idempotent");
    }

    private static void reject(byte[] base, Map<String, String> rules) throws Exception {
        try { Profile.withAppRules(base, rules); throw new AssertionError("Invalid routing accepted"); }
        catch (IOException expected) { }
    }

    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private static Map<?, ?> parse(byte[] value) { return new Yaml().load(new String(value, StandardCharsets.UTF_8)); }
    private static void require(boolean condition, String name) {
        if (!condition) throw new AssertionError(name);
    }
}

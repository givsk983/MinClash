package io.minclash.app;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
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
        Files.createDirectories(Path.of(args[0]));
        Files.write(Path.of(args[0], "app-routing.yaml"), effective);
        Files.write(Path.of(args[0], "app-routing-nodes-only.yaml"), nodesOnly);
        System.out.println("PASS: personal priority, direct routing, subscription update, removal, missing groups, invalid packages, nodes-only and Unicode.");
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

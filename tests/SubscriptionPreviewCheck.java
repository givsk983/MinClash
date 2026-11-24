package io.minclash.app;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

public final class SubscriptionPreviewCheck {
    public static void main(String[] args) throws Exception {
        byte[] before = profile("""
            proxies:
              - {name: retired, type: socks5, server: 127.0.0.1, port: 9}
            proxy-groups:
              - {name: Removed, type: select, proxies: [retired]}
              - {name: Static, type: select, proxies: [retired]}
            rules: ['MATCH,DIRECT']
            """);
        byte[] after = profile("""
            proxies:
              - {name: replacement, type: socks5, server: 127.0.0.1, port: 9}
            proxy-providers:
              pool: {type: file, path: ./pool.yaml}
            proxy-groups:
              - {name: Static, type: select, proxies: [replacement]}
              - {name: Provider, type: select, use: [pool]}
              - {name: Local, type: select, include-all-proxies: true}
            rules: ['MATCH,DIRECT']
            """);
        SubscriptionCatalog.Entry entry = new SubscriptionCatalog.Entry("候选", "https://example.test/config");
        List<Controller.Group> selected = List.of(group("Static", "retired"), group("Removed", "retired"),
            group("Provider", "dynamic"), group("Local", "replacement"));
        SubscriptionPreview preview = SubscriptionPreview.compare(entry, after, 7, false,
            new RoutingStore.State(before, Map.of("com.example.app", "Removed", "com.example.direct", "DIRECT")), selected);
        require(preview.conflicts() && preview.generation() == 7 && preview.source() == after, "conflict and exact candidate");
        String summary = preview.summary();
        require(summary.contains("新增节点  1\nreplacement") && summary.contains("移除节点  1\nretired"), "static node delta");
        require(summary.contains("com.example.app → Removed") && !summary.contains("com.example.direct"), "invalid personal target");
        require(summary.contains("Static → retired") && summary.contains("Removed → retired"), "lost group selections");
        require(!summary.contains("Provider → dynamic") && !summary.contains("Local → replacement"), "dynamic and include-all groups");
        require(!SubscriptionPreview.compare(entry, after, 0, true, null, null).conflicts(), "first import");

        SubscriptionCatalog.Entry local = new SubscriptionCatalog.Entry("local.yaml", "");
        RoutingStore.State baseline = new RoutingStore.State(before, Map.of("com.example.old", "Removed"));
        RoutingStore.State candidateRules = new RoutingStore.State(before,
            Map.of("com.example.app", "Removed", "com.example.direct", "DIRECT"), "https://old.test/config", List.of(
                PersonalRules.create("DOMAIN", "shared.example", "Removed"),
                PersonalRules.create("DOMAIN", "custom.example", "Personal")));
        SubscriptionPreview localPreview = SubscriptionPreview.compare(local, after, 7, false, baseline, candidateRules, selected);
        require(localPreview.missingTargets().equals(List.of("Personal", "Removed")), "group missing targets across rule types");
        require(!localPreview.summary().contains("com.example.old"), "compare baselines independently of rules");
        require(localPreview.replacementTargets("Removed").containsAll(List.of("Static", "DIRECT"))
            && !localPreview.replacementTargets("Removed").contains("REJECT"), "offer valid replacement targets for apps");
        try {
            localPreview.replaceTarget("Removed", "REJECT");
            throw new AssertionError("reject target for app rules");
        } catch (java.io.IOException expected) {}
        SubscriptionPreview mapped = localPreview.replaceTarget("Removed", "Static");
        require(mapped.missingTargets().equals(List.of("Personal")), "update all references to a resolved target");
        require(mapped.rules().applications().equals(Map.of("com.example.app", "Static", "com.example.direct", "DIRECT"))
            && mapped.rules().rules().get(0).target().equals("Static"), "preserve unaffected rules and change app and custom targets");
        require(mapped.rules().subscription().isEmpty() && mapped.source() == after && mapped.generation() == 7,
            "keep the local candidate and its generation");
        require(!mapped.replaceTarget("Personal", "REJECT").conflicts(), "offer reject for personal-only conflicts");
        require(!mapped.withRules(new RoutingStore.State(after, Map.of())).conflicts(), "preview explicitly supplied rules");
        require(candidateRules.applications().get("com.example.app").equals("Removed"), "discarded preview leaves original rules intact");
        System.out.println("PASS: subscription preview, invalid app targets, removed selections and provider uncertainty.");
    }

    private static byte[] profile(String yaml) throws Exception {
        return Profile.normalize(yaml.getBytes(StandardCharsets.UTF_8));
    }

    private static Controller.Group group(String name, String current) {
        return new Controller.Group(name, current, List.of(current), false, true);
    }

    private static void require(boolean condition, String name) {
        if (!condition) throw new AssertionError(name);
    }
}

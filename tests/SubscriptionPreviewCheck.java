package io.minclash.app;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

public final class SubscriptionPreviewCheck {
    public static void main(String[] args) throws Exception {
        Controller.Group proxy = group("MinClash", "node");
        Controller.Group hidden = new Controller.Group("PROXIES", "MinClash", List.of("MinClash"), false, false, true);
        require(Controller.primaryGroup(Controller.Mode.RULE, List.of(hidden, proxy)) == proxy
            && Controller.route(hidden, List.of(hidden, proxy)).equals(List.of("MinClash", "node")),
            "hidden protection groups resolve as routes without taking the main proxy slot");
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
        SubscriptionPreview protectedPreview = localPreview.withProtectionAlias("Removed", "Static");
        require(protectedPreview.unresolvedProtectionTargets(List.of("Removed", "Missing")).equals(List.of("Missing"))
            && protectedPreview.missingTargets().equals(List.of("Personal")), "the protection alias resolves the same group's personal references");
        SubscriptionPreview edited = protectedPreview.withRules(protectedPreview.rules()).replaceTarget("Personal", "REJECT");
        require(!edited.conflicts() && edited.protectionAliases().equals(Map.of("Removed", "Static"))
            && java.util.Arrays.equals(edited.rules().source(), edited.source()), "editing personal rules retains the protection alias");

        RoutingStore.State withDns = new RoutingStore.State(before, Map.of(), entry.address()).withProxyDns("Removed");
        RoutingStore.State decoded = RoutingStore.decodeState(RoutingStore.encodeState(withDns));
        require(decoded.proxyDnsTarget().equals("Removed"), "persist the per-profile DNS target");
        SubscriptionPreview newDns = SubscriptionPreview.compare(entry, before, 0, true, null, null);
        require(newDns.proxyDnsTargets().contains("Removed") && newDns.rules().proxyDnsTarget().isEmpty(),
            "new subscriptions without DNS can opt in and start off");
        SubscriptionPreview staleDns = SubscriptionPreview.compare(entry, after, 0, false, decoded, null);
        require(staleDns.proxyDnsConflict(), "subscription updates retain the selection and surface a missing DNS target");
        SubscriptionPreview selectedDns = staleDns.withRules(staleDns.rules().withProxyDns("Static"));
        require(!selectedDns.proxyDnsConflict() && selectedDns.rules().proxyDnsTarget().equals("Static"), "DNS target can be reselected");
        require(!java.util.Arrays.equals(RoutingStore.render(selectedDns.rules()),
            RoutingStore.render(selectedDns.rules().withProxyDns(""))), "DNS can follow the configuration again");
        byte[] explicit = profile(new String(after, StandardCharsets.UTF_8).replace("dns:\n  enable: true",
            "dns:\n  nameserver: ['8.8.8.8']"));
        SubscriptionPreview localDns = SubscriptionPreview.compare(local, explicit, 0, false, selectedDns.rules(), null);
        require(localDns.proxyDnsConflict() && localDns.proxyDnsTargets().isEmpty()
            && !localDns.withRules(localDns.rules().withProxyDns("")).proxyDnsConflict(),
            "an explicit DNS configuration takes effect after restoring the profile default");
        System.out.println("PASS: subscription preview, invalid app targets, removed selections and provider uncertainty.");
    }

    private static byte[] profile(String yaml) throws Exception {
        return Profile.normalize(yaml.getBytes(StandardCharsets.UTF_8));
    }

    private static Controller.Group group(String name, String current) {
        return new Controller.Group(name, current, List.of(current), false, true, false);
    }

    private static void require(boolean condition, String name) {
        if (!condition) throw new AssertionError(name);
    }
}

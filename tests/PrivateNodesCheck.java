package io.minclash.app;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.yaml.snakeyaml.Yaml;

public final class PrivateNodesCheck {
    private static final String OUTLET = Profile.PRIVATE_OUTLET;
    private static final String ADDRESS = "https://first.example/config";
    private static final String SECRET = "fixture-private-credential";

    public static void main(String[] args) throws Exception {
        byte[] original = profile("shared");
        byte[] updated = profile("new-shared");
        String nodes = Profile.normalizePrivateNodes("""
            proxies:
              - {name: shared, type: socks5, server: 127.0.0.1, port: 1080, password: %s}
              - {name: backup-private, type: socks5, server: 127.0.0.1, port: 1081}
            """.formatted(SECRET));
        require(nodes.equals(Profile.normalizePrivateNodes(nodes)), "the saved editor text is stable");
        require(!Profile.normalizePrivateNodes("name: single\ntype: socks5\nserver: 127.0.0.1\nport: 9\n").isEmpty(),
            "accept a single node mapping");
        expectFailure(() -> Profile.normalizePrivateNodes("- {name: dup, type: socks5}\n- {name: dup, type: socks5}"));
        expectFailure(() -> Profile.normalizePrivateNodes("- {name: bad, type: direct}"));

        RoutingStore.State saved = new RoutingStore.State(original, Map.of("com.example.app", OUTLET), ADDRESS)
            .withPrivateNodes(nodes).withPrivateNodesEnabled(true);
        RoutingStore.State reopened = RoutingStore.decodeState(RoutingStore.encodeState(saved));
        require(reopened.privateNodesEnabled() && reopened.privateNodes().equals(nodes) && !text(reopened.source()).contains(SECRET),
            "persist the toggle and private nodes apart from source");
        require(Profile.routingTargets(reopened.sourceWithPrivateNodes()).contains(OUTLET), "private group is available for app rules");
        require(reopened.withSource(updated, "https://other.example/config").privateNodes().isEmpty(), "new subscriptions stay isolated");

        SubscriptionCatalog.Entry entry = new SubscriptionCatalog.Entry("First", ADDRESS);
        Controller.Group selected = new Controller.Group(OUTLET, "backup-private", List.of("shared", "backup-private"), false, false, false);
        SubscriptionPreview preview = SubscriptionPreview.compare(entry, updated, 4, false, reopened, List.of(selected));
        require(!preview.conflicts() && !preview.summary().contains(OUTLET + " → backup-private"), "refresh previews preserve the private target and selection");
        require(preview.rules().privateNodes().equals(nodes) && reopened.withSource(updated, ADDRESS).privateNodes().equals(nodes),
            "manual and scheduled refresh retain the overlay");
        require(preview.protectionReplacementTargets("Previous").contains(OUTLET), "preview offers the enabled private outlet for protection");
        SubscriptionPreview remapped = preview.withProtectionAlias("Previous", OUTLET);
        Map<?, ?> remappedConfig = new Yaml().load(text(RoutingStore.render(remapped.rules())));
        require(group(remappedConfig, "Previous").get("proxies").equals(List.of(OUTLET))
            && !text(remapped.source()).contains(SECRET), "a protection alias points to the private outlet without persisting credentials in the source");

        byte[] rendered = RoutingStore.render(preview.rules());
        Map<?, ?> renderedYaml = new Yaml().load(text(rendered));
        Map<?, ?> providers = (Map<?, ?>) renderedYaml.get("proxy-providers");
        Map<?, ?> all = group(renderedYaml, "All");
        Map<?, ?> providerOnly = group(renderedYaml, "Providers");
        Map<?, ?> privateGroup = group(renderedYaml, OUTLET);
        String privateProvider = (String) ((List<?>) privateGroup.get("use")).get(0);
        require(providers.containsKey(privateProvider) && !privateProvider.equals("minclash-private-nodes"),
            "the inline provider has a distinct name even when the subscription uses its preferred name");
        require(privateProvider.equals(Profile.privateProviderName(preview.rules().source())), "the picker uses the generated provider for health checks");
        require(Controller.probeOutlet(List.of("GLOBAL", OUTLET, "shared"), "shared").equals(OUTLET)
            && Controller.probeOutlet(List.of("All", "shared"), "shared").equals("shared"),
            "the overview tests the private selector rather than a same-named ordinary proxy");
        require(Boolean.TRUE.equals(all.get("include-all-proxies")) && Boolean.FALSE.equals(all.get("include-all"))
            && all.get("use").equals(List.of("minclash-private-nodes")), "all-group keeps the original static and provider nodes");
        require(Boolean.FALSE.equals(providerOnly.get("include-all-providers"))
            && providerOnly.get("use").equals(List.of("minclash-private-nodes")), "provider-only group excludes the new provider");
        require(privateGroup.get("type").equals("select") && privateGroup.get("empty-fallback").equals("REJECT"),
            "private routing uses a manual selector with a rejecting empty group");
        require(renderedYaml.get("rules").equals(List.of("PROCESS-NAME,com.example.app," + OUTLET, "MATCH,All")),
            "only the assigned app receives the private routing rule");
        require(!text(preview.rules().source()).contains(SECRET), "the refreshed source has no private credentials");
        require(!Profile.routingTargets(reopened.withApplications(Map.of()).withPrivateNodes("").sourceWithPrivateNodes()).contains(OUTLET),
            "clearing private nodes removes the group");
        expectFailure(() -> RoutingStore.render(reopened.withPrivateNodes("")));
        expectFailure(() -> RoutingStore.render(reopened.withSource(profile(OUTLET), ADDRESS)));

        RoutingStore.State paused = reopened.withPrivateNodesEnabled(false);
        RoutingStore.ProtectionTargets activeTargets = reopened.protectionTargets();
        require(activeTargets.selectable().equals(List.of("All", "Providers", OUTLET))
            && activeTargets.configured().equals(activeTargets.selectable()), "the protection picker offers the enabled private outlet and excludes direct");
        ProtectionPolicy protection = new ProtectionPolicy();
        protection.putApp(new ProtectionPolicy.App("com.example.app", 10123, OUTLET), activeTargets.selectable());
        protection.validate(activeTargets.configured());
        RoutingStore.ProtectionTargets pausedTargets = paused.protectionTargets();
        protection.validate(pausedTargets.configured());
        expectFailure(() -> protection.putApp(new ProtectionPolicy.App("com.example.other", 10124, OUTLET), pausedTargets.selectable()));
        require(!pausedTargets.selectable().contains(OUTLET), "a paused private outlet stays valid for existing protection and is hidden from new choices");
        require(!reopened.withPrivateNodes("").protectionTargets().configured().contains(OUTLET), "protection requires nodes for the generated outlet");
        byte[] namedOutlet = Profile.normalize(("""
            proxies:
              - {name: shared, type: socks5, server: 127.0.0.1, port: 9}
            proxy-groups:
              - {name: %s, type: select, proxies: [shared]}
            rules: ['MATCH,DIRECT']
            """).formatted(OUTLET).getBytes(StandardCharsets.UTF_8));
        require(new RoutingStore.State(namedOutlet, Map.of(), ADDRESS).protectionTargets().selectable().contains(OUTLET),
            "a subscription's group with the same name remains selectable without private nodes");
        SubscriptionPreview pausedPreview = SubscriptionPreview.compare(entry, updated, 4, false, paused, List.of());
        require(!pausedPreview.protectionReplacementTargets("Previous").contains(OUTLET), "preview hides the paused private outlet");
        expectFailure(() -> pausedPreview.withProtectionAlias("Previous", OUTLET));
        require(!paused.selectableRoutingTargets().contains(OUTLET), "a paused outlet disappears from policy choices");
        require(paused.withPrivateNodesEnabled(true).selectableRoutingTargets().contains(OUTLET), "reenabling restores the policy choice");
        require(!paused.withSource(updated, ADDRESS).privateNodesEnabled(), "refresh retains the disabled setting");
        byte[] pausedYaml = RoutingStore.render(paused);
        Map<?, ?> pausedConfig = new Yaml().load(text(pausedYaml));
        Map<?, ?> pausedGroup = group(pausedConfig, OUTLET);
        require(Boolean.TRUE.equals(pausedGroup.get("hidden")) && pausedGroup.get("proxies").equals(List.of("REJECT"))
            && !text(pausedYaml).contains(SECRET), "a paused group rejects existing rules and excludes private credentials from core config");
        Controller.Group hidden = new Controller.Group(OUTLET, "REJECT", List.of("REJECT"), false, false, true);
        Controller.Group global = new Controller.Group("GLOBAL", OUTLET, List.of("DIRECT", OUTLET), false, true, false);
        List<Controller.Group> visible = Controller.selectable(List.of(hidden, global));
        require(visible.size() == 1 && visible.get(0).options().equals(List.of("DIRECT")), "paused outlet is absent from proxy groups and nested choices");

        SubscriptionCatalog.Entry other = new SubscriptionCatalog.Entry("Other", "https://other.example/config");
        BackupArchive.Data data = new BackupArchive.Data(new RoutingStore.State(updated, Map.of(), other.address()),
            List.of(entry, other), Map.of(), 1, "0.3.9", List.of(saved));
        char[] password = "fixture-backup-password".toCharArray();
        byte[] backup = BackupArchive.encode(data, password);
        require(!text(backup).contains(SECRET), "encrypted backup hides private credentials");
        BackupArchive.Data restored = BackupArchive.decode(backup, password);
        require(restored.state().privateNodes().isEmpty() && restored.inactiveProfiles().get(0).privateNodes().equals(nodes)
            && restored.inactiveProfiles().get(0).privateNodesEnabled(),
            "a saved inactive subscription retains its private nodes after backup restore");

        if (args.length > 0) Files.write(Path.of(args[0]), rendered);
        if (args.length > 1) Files.write(Path.of(args[1]), pausedYaml);
        if (args.length > 2) Files.write(Path.of(args[2]), RoutingStore.render(remapped.rules()));
        System.out.println("PASS: private node input, subscription refresh, app routing, protection, provider isolation and encrypted backup.");
    }

    private static byte[] profile(String node) throws IOException {
        return Profile.normalize("""
            proxies:
              - {name: '%s', type: socks5, server: 127.0.0.1, port: 9}
            proxy-providers:
              minclash-private-nodes:
                type: inline
                payload:
                  - {name: published-provider-node, type: socks5, server: 127.0.0.1, port: 9}
            proxy-groups:
              - {name: All, type: select, include-all: true}
              - {name: Providers, type: select, include-all-providers: 1}
            rules: ['MATCH,All']
            """.formatted(node).getBytes(StandardCharsets.UTF_8));
    }

    private static Map<?, ?> group(Map<?, ?> yaml, String name) {
        return ((List<?>) yaml.get("proxy-groups")).stream().map(item -> (Map<?, ?>) item)
            .filter(group -> name.equals(group.get("name"))).findFirst().orElseThrow();
    }

    private static String text(byte[] bytes) { return new String(bytes, StandardCharsets.UTF_8); }

    private static void require(boolean condition, String description) {
        if (!condition) throw new AssertionError(description);
    }

    @FunctionalInterface
    private interface Operation { void run() throws Exception; }

    private static void expectFailure(Operation operation) throws Exception {
        try { operation.run(); }
        catch (IOException expected) { return; }
        throw new AssertionError("invalid private configuration accepted");
    }
}

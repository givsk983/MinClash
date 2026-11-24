package io.minclash.app;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

record SubscriptionPreview(SubscriptionCatalog.Entry entry, byte[] source, int generation, boolean adding,
                           Comparison comparison, RoutingStore.State rules, List<String> missingTargets, String summary) {
    private record Comparison(Set<String> groups, Set<String> added, Set<String> removed, List<String> lost) {}

    static SubscriptionPreview compare(SubscriptionCatalog.Entry entry, byte[] source, int generation, boolean adding,
                                       RoutingStore.State current, List<Controller.Group> selections) throws IOException {
        return compare(entry, source, generation, adding, current, current, selections);
    }

    static SubscriptionPreview compare(SubscriptionCatalog.Entry entry, byte[] source, int generation, boolean adding,
                                       RoutingStore.State baseline, RoutingStore.State rules, List<Controller.Group> selections) throws IOException {
        Profile.PreviewLayout candidate = Profile.previewLayout(source);
        Set<String> before = baseline == null ? Collections.emptySet() : Profile.previewLayout(baseline.source()).nodes();
        Set<String> added = new TreeSet<>(candidate.nodes());
        added.removeAll(before);
        Set<String> removed = new TreeSet<>(before);
        removed.removeAll(candidate.nodes());
        List<String> lost = new ArrayList<>();
        for (Controller.Group selection : selections == null ? Collections.<Controller.Group>emptyList() : selections) {
            if (selection.automatic() || selection.current().isEmpty()) continue;
            Profile.PreviewGroup group = candidate.groups().get(selection.name());
            if (group == null && ProxyGroupSection.isGlobal(selection.name())
                && (candidate.hasProviders() || candidate.nodes().contains(selection.current())
                    || candidate.groups().containsKey(selection.current())
                    || Arrays.asList("DIRECT", "REJECT", "REJECT-DROP", "PASS", "COMPATIBLE").contains(selection.current()))) continue;
            if (group == null || !group.providerNodes() && !group.members().contains(selection.current())
                && !(group.allNodes() && candidate.nodes().contains(selection.current()))
                && !selection.current().equals("COMPATIBLE")) {
                lost.add(selection.name() + " → " + selection.current());
            }
        }
        Comparison comparison = new Comparison(Collections.unmodifiableSet(candidate.groups().keySet()),
            Collections.unmodifiableSet(added), Collections.unmodifiableSet(removed),
            selections == null ? null : Collections.unmodifiableList(lost));
        return describe(entry, source, generation, adding, comparison, forCandidate(entry, source, rules));
    }

    boolean conflicts() { return !missingTargets.isEmpty(); }

    SubscriptionPreview withRules(RoutingStore.State rules) throws IOException {
        return describe(entry, source, generation, adding, comparison, forCandidate(entry, source, rules));
    }

    List<String> replacementTargets(String oldTarget) {
        if (!missingTargets.contains(oldTarget)) return Collections.emptyList();
        List<String> targets = new ArrayList<>(new TreeSet<>(comparison.groups()));
        targets.removeIf(name -> name.equals("DIRECT") || name.equals("REJECT") || name.length() > 4096 || !name.matches("[^\\r\\n,]+"));
        targets.add("DIRECT");
        if (!rules.applications().containsValue(oldTarget)) targets.add("REJECT");
        return Collections.unmodifiableList(targets);
    }

    SubscriptionPreview replaceTarget(String oldTarget, String newTarget) throws IOException {
        if (!replacementTargets(oldTarget).contains(newTarget)) throw new IOException("请选择新配置中可用的出口。");
        Map<String, String> applications = new TreeMap<>(rules.applications());
        applications.replaceAll((name, target) -> target.equals(oldTarget) ? newTarget : target);
        List<PersonalRules.Rule> personal = new ArrayList<>(rules.rules().size());
        for (PersonalRules.Rule rule : rules.rules()) {
            personal.add(rule.target().equals(oldTarget) ? PersonalRules.create(rule.type(), rule.value(), newTarget) : rule);
        }
        RoutingStore.State updated = rules.withApplications(applications).withRules(personal);
        return describe(entry, source, generation, adding, comparison, updated);
    }

    private static RoutingStore.State forCandidate(SubscriptionCatalog.Entry entry, byte[] source, RoutingStore.State rules) {
        return rules == null ? new RoutingStore.State(source, Collections.emptyMap(), entry.address()) : rules.withSource(source, entry.address());
    }

    private static SubscriptionPreview describe(SubscriptionCatalog.Entry entry, byte[] source, int generation, boolean adding,
                                                Comparison comparison, RoutingStore.State rules) {
        Set<String> missing = new TreeSet<>();
        List<String> conflicts = new ArrayList<>();
        for (PersonalRules.Rule rule : rules.rules()) {
            String target = rule.target();
            if (!target.equals("DIRECT") && !target.equals("REJECT") && !comparison.groups().contains(target)) {
                missing.add(target);
                conflicts.add("自定义 " + rule.value() + " → " + target);
            }
        }
        for (Map.Entry<String, String> rule : rules.applications().entrySet()) {
            String target = rule.getValue();
            if (!target.equals("DIRECT") && !comparison.groups().contains(target)) {
                missing.add(target);
                conflicts.add(rule.getKey() + " → " + target);
            }
        }
        StringBuilder summary = new StringBuilder(entry.address().isEmpty()
            ? "比较 YAML 中声明的节点。动态提供者的成员以加载后的实际列表为准。"
            : "比较订阅 YAML 中声明的节点。动态提供者的成员以加载后的实际列表为准。");
        if (!rules.bypassedApps().isEmpty()) {
            summary.append("\n绕过 TUN 的应用设置将保留；启用期间 DNS 返回真实 IP。");
        }
        append(summary, "新增节点", comparison.added());
        append(summary, "移除节点", comparison.removed());
        append(summary, "失效的应用分流／自定义规则", conflicts);
        if (!conflicts.isEmpty()) summary.append("\n请为缺失的出口选择替代项。");
        if (comparison.lost() == null) summary.append("\n\n当前节点选择暂不可读取。");
        else append(summary, "预计无法沿用的节点选择", comparison.lost());
        return new SubscriptionPreview(entry, source, generation, adding, comparison, rules,
            Collections.unmodifiableList(new ArrayList<>(missing)), summary.toString());
    }

    private static void append(StringBuilder summary, String label, Collection<String> entries) {
        summary.append("\n\n").append(label).append("  ").append(entries.size());
        int shown = 0;
        for (String entry : entries) {
            if (shown++ == 20) {
                summary.append("\n…");
                break;
            }
            summary.append("\n").append(entry);
        }
    }
}

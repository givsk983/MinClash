package io.minclash.app;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

record SubscriptionPreview(SubscriptionCatalog.Entry entry, byte[] source, int generation, boolean adding,
                           String summary, boolean conflicts) {
    static SubscriptionPreview compare(SubscriptionCatalog.Entry entry, byte[] source, int generation, boolean adding,
                                       RoutingStore.State current, List<Controller.Group> selections) throws IOException {
        Profile.PreviewLayout candidate = Profile.previewLayout(source);
        Set<String> before = current == null ? Collections.emptySet() : Profile.previewLayout(current.source()).nodes();
        Set<String> added = new TreeSet<>(candidate.nodes());
        added.removeAll(before);
        Set<String> removed = new TreeSet<>(before);
        removed.removeAll(candidate.nodes());
        List<String> conflicts = new ArrayList<>();
        if (current != null) {
            for (PersonalRules.Rule rule : current.rules()) {
                if (!rule.target().equals("DIRECT") && !rule.target().equals("REJECT") && !candidate.groups().containsKey(rule.target())) {
                    conflicts.add("自定义 " + rule.value() + " → " + rule.target());
                }
            }
            for (Map.Entry<String, String> rule : current.applications().entrySet()) {
                if (!rule.getValue().equals("DIRECT") && !candidate.groups().containsKey(rule.getValue())) {
                    conflicts.add(rule.getKey() + " → " + rule.getValue());
                }
            }
        }
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
        StringBuilder summary = new StringBuilder("比较订阅 YAML 中声明的节点。动态提供者的成员以加载后的实际列表为准。");
        append(summary, "新增节点", added);
        append(summary, "移除节点", removed);
        append(summary, "失效的应用分流／自定义规则", conflicts);
        if (!conflicts.isEmpty()) summary.append("\n请先调整这些个人分流的出口，再重新预览。");
        if (selections == null) summary.append("\n\n当前节点选择暂不可读取。");
        else append(summary, "预计无法沿用的节点选择", lost);
        return new SubscriptionPreview(entry, source, generation, adding, summary.toString(), !conflicts.isEmpty());
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

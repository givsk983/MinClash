package io.minclash.app;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.io.StringWriter;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.ArrayList;
import java.util.Map;
import java.util.TreeMap;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Pattern;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.error.MarkedYAMLException;
import org.yaml.snakeyaml.error.YAMLException;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.NodeTuple;
import org.yaml.snakeyaml.nodes.ScalarNode;
import org.yaml.snakeyaml.nodes.SequenceNode;
import org.yaml.snakeyaml.nodes.Tag;

final class Profile {
    static final int MAX_BYTES = 2 * 1024 * 1024;
    static final class MissingRoutingTarget extends IOException {
        MissingRoutingTarget(String message) { super(message); }
    }
    static final class ProxyDnsConflict extends IOException {
        ProxyDnsConflict(String message) { super(message); }
    }

    private static final Pattern LOOPBACK_DNS = Pattern.compile(
        "(?i)^(?:[a-z][a-z0-9+.-]*://)?(?:localhost\\.?|127(?:\\.[0-9]{1,3}){3}|\\[(?:::1|0:0:0:0:0:0:0:1)\\])(?=[:/?#]|$)|^::1$");
    private static final List<String> DNS_UPSTREAMS = Arrays.asList("default-nameserver", "nameserver", "fallback",
        "proxy-server-nameserver", "direct-nameserver");
    private static final List<String> DNS_POLICIES = List.of("nameserver-policy", "proxy-server-nameserver-policy");
    private static final String CLOUDFLARE_DOH = "https://1.1.1.1/dns-query";
    private static final List<String> DIRECT_DNS = Arrays.asList(
        "https://223.5.5.5/dns-query#DIRECT", CLOUDFLARE_DOH + "#DIRECT");
    private static final Set<String> NON_PROXY_TYPES = new HashSet<>(Arrays.asList(
        "direct", "reject", "reject-drop", "dns", "pass", "compatible", "rematch"));
    private static final Set<String> MANAGED_FIELDS = new HashSet<>(Arrays.asList("port", "socks-port", "mixed-port",
        "redir-port", "tproxy-port", "listeners", "tunnels", "tuic-server", "iptables", "ebpf",
        "ss-config", "vmess-config", "tcptun-config", "udptun-config",
        "secret", "authentication", "bind-address", "routing-mark", "interface-name", "ntp"));

    private static final String MANAGED = """
        allow-lan: false
        ipv6: true
        tun:
          enable: true
          device: minclash0
          stack: mips
          mtu: 1500
          auto-route: true
          auto-detect-interface: true
          auto-redirect: false
          strict-route: true
          inet6-address: ['fdfe:dcba:9876::1/126']
          iproute2-table-index: 20882
          iproute2-rule-index: 8800
          dns-hijack: ['any:53', 'tcp://any:53']
        """;

    static final class TooLarge extends IOException {
        TooLarge() { super("配置文件最大为 2 MiB。"); }
    }

    static byte[] readLimited(InputStream input) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = input.read(buffer)) != -1) {
            if (bytes.size() + count > MAX_BYTES) throw new TooLarge();
            bytes.write(buffer, 0, count);
        }
        return bytes.toByteArray();
    }

    static byte[] normalize(InputStream input) throws IOException {
        return normalize(readLimited(input));
    }

    static byte[] normalize(byte[] content) throws IOException {
        return normalize(content, false).source();
    }

    static LocalYaml prepareLocalYaml(byte[] content) throws IOException {
        return normalize(content, true);
    }

    private static LocalYaml normalize(byte[] content, boolean localImport) throws IOException {
        if (content.length > MAX_BYTES) throw new TooLarge();
        String source = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(content)).toString();
        if (source.startsWith("\uFEFF")) source = source.substring(1);
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        options.setMaxAliasesForCollections(50);
        options.setNestingDepthLimit(40);
        options.setCodePointLimit(MAX_BYTES);
        options.setMergeOnCompose(true);
        Yaml yaml = new Yaml(options);
        try {
            Node parsed = yaml.compose(new StringReader(source));
            if (!(parsed instanceof MappingNode root)) throw new IOException("请选择完整的 Mihomo YAML 配置。");
            Set<String> keys = keysOf(root);
            if (!keys.contains("rules") && !keys.contains("proxies") && !keys.contains("proxy-providers")) {
                throw new IOException("配置至少需要 rules、proxies 或 proxy-providers 中的一项。");
            }
            List<NodeTuple> fields = root.getValue();
            GroupChange change = localImport ? completeMainGroup(root, yaml) : null;
            if (change == null && !keys.contains("rules") && !keys.contains("proxy-groups")) addDefaultGroup(yaml, fields);
            fields.removeIf(tuple -> {
                String key = keyOf(tuple);
                return MANAGED_FIELDS.contains(key) || key.startsWith("external-") ||
                    key.equals("allow-lan") || key.equals("ipv6") || key.equals("tun");
            });
            fields.addAll(((MappingNode) yaml.compose(new StringReader(MANAGED))).getValue());

            Node dns = field(root, "dns");
            if (dns != null && !Tag.NULL.equals(dns.getTag()) && !(dns instanceof MappingNode)) {
                throw new IOException("配置中的 dns 必须是映射。");
            }
            MappingNode managedDns = (MappingNode) yaml.compose(new StringReader("enable: true\n"));
            if (dns instanceof MappingNode importedDns) {
                keysOf(importedDns);
                for (NodeTuple tuple : importedDns.getValue()) {
                    String key = keyOf(tuple);
                    if (!key.equals("enable") && !key.equals("listen")) managedDns.getValue().add(tuple);
                }
            }
            fields.removeIf(tuple -> keyOf(tuple).equals("dns"));
            fields.add(new NodeTuple(yaml.represent("dns"), managedDns));

            StringWriter output = new StringWriter();
            yaml.serialize(root, output);
            byte[] normalized = output.toString().getBytes(StandardCharsets.UTF_8);
            if (normalized.length > MAX_BYTES && change != null) return normalize(content, false);
            if (normalized.length > MAX_BYTES) throw new IOException("应用 TUN 设置后，配置超过 2 MiB。");
            return new LocalYaml(normalized, change);
        } catch (MarkedYAMLException error) {
            int line = error.getProblemMark() == null ? 0 : error.getProblemMark().getLine() + 1;
            throw new IOException(line > 0 ? "YAML 格式有误，请检查第 " + line + " 行。" : "YAML 格式有误。", error);
        } catch (YAMLException error) {
            throw new IOException("无法读取 YAML 配置。", error);
        }
    }

    static boolean usesLoopbackProxyDns(byte[] content) throws IOException {
        if (!(field(routingRoot(content), "dns") instanceof MappingNode dns)) return false;
        if (!(field(dns, "proxy-server-nameserver") instanceof SequenceNode resolvers)) return false;
        return resolvers.getValue().stream().anyMatch(Profile::isLoopbackDns);
    }

    static boolean hasTunBypass(byte[] content) throws IOException {
        return field(routingRoot(content), "tun") instanceof MappingNode tun
            && field(tun, "exclude-package") instanceof SequenceNode packages && !packages.getValue().isEmpty();
    }

    static boolean canUseProxyDns(byte[] content) throws IOException {
        if (content.length > MAX_BYTES) throw new TooLarge();
        return canUseProxyDns(field(routingRoot(content), "dns"));
    }

    private static boolean canUseProxyDns(Node configured) throws IOException {
        if (configured == null || Tag.NULL.equals(configured.getTag())) return true;
        if (!(configured instanceof MappingNode dns)) return false;
        keysOf(dns);
        for (String upstream : DNS_UPSTREAMS) {
            if (!emptyField(field(dns, upstream))) return false;
        }
        for (String policy : DNS_POLICIES) {
            Node value = field(dns, policy);
            if (value != null && !Tag.NULL.equals(value.getTag())
                && !(value instanceof MappingNode mapping && mapping.getValue().isEmpty())) return false;
        }
        return true;
    }

    // Apply to the effective configuration. The subscription remains the source of truth.
    static byte[] withProxyDns(byte[] content, String target) throws IOException {
        if (content.length > MAX_BYTES) throw new TooLarge();
        MappingNode root = routingRoot(content);
        Node configured = field(root, "dns");
        if (!canUseProxyDns(configured)) throw new ProxyDnsConflict("当前配置已指定 DNS 上游或解析规则，不能开启 DNS 经代理。");
        if (!safeProxyDnsName(target) || !proxyDnsTargets(root).contains(target)) {
            throw new ProxyDnsConflict("DNS 代理出口已不可用，请重新选择有效的代理节点或组。");
        }

        Yaml yaml = new Yaml();
        MappingNode dns = configured instanceof MappingNode mapping
            ? new MappingNode(Tag.MAP, new ArrayList<>(mapping.getValue()), mapping.getFlowStyle())
            : (MappingNode) yaml.represent(new LinkedHashMap<>());
        // Mihomo decodes the fragment before looking up the adapter name.
        String fragment = URLEncoder.encode(target, StandardCharsets.UTF_8.name()).replace("+", "%20");
        List<String> resolver = List.of(CLOUDFLARE_DOH + "#" + fragment);
        setField(dns, yaml, "enable", yaml.represent(true));
        setField(dns, yaml, "prefer-h3", yaml.represent(false));
        setField(dns, yaml, "nameserver", yaml.represent(resolver));
        setField(dns, yaml, "direct-nameserver", yaml.represent(resolver));
        setField(dns, yaml, "default-nameserver", yaml.represent(DIRECT_DNS));
        setField(dns, yaml, "proxy-server-nameserver", yaml.represent(DIRECT_DNS));
        setField(root, yaml, "dns", dns);

        StringWriter output = new StringWriter();
        yaml.serialize(root, output);
        byte[] updated = output.toString().getBytes(StandardCharsets.UTF_8);
        if (updated.length > MAX_BYTES) throw new TooLarge();
        return updated;
    }

    static byte[] repairDns(byte[] content) throws IOException {
        MappingNode root = routingRoot(content);
        if (!(field(root, "dns") instanceof MappingNode dns)) return content;
        Yaml yaml = new Yaml();
        boolean changed = replaceLoopbackDns(dns, DNS_UPSTREAMS, yaml);
        if (field(dns, "proxy-server-nameserver-policy") instanceof MappingNode policy) {
            changed |= replaceLoopbackDns(policy, null, yaml);
        }
        if (!changed) return content;
        StringWriter output = new StringWriter();
        yaml.serialize(root, output);
        byte[] repaired = output.toString().getBytes(StandardCharsets.UTF_8);
        if (repaired.length > MAX_BYTES) throw new TooLarge();
        return repaired;
    }

    private static boolean replaceLoopbackDns(MappingNode mapping, List<String> fields, Yaml yaml) {
        boolean changed = false;
        List<NodeTuple> entries = mapping.getValue();
        for (int index = 0; index < entries.size(); index++) {
            NodeTuple tuple = entries.get(index);
            if (fields != null && !fields.contains(keyOf(tuple))) continue;
            Node value = tuple.getValueNode();
            boolean loopbackOnly = value instanceof SequenceNode list
                ? !list.getValue().isEmpty() && list.getValue().stream().allMatch(Profile::isLoopbackDns)
                : fields == null && isLoopbackDns(value);
            if (!loopbackOnly) continue;
            entries.set(index, new NodeTuple(tuple.getKeyNode(), yaml.represent(DIRECT_DNS)));
            changed = true;
        }
        return changed;
    }

    private static boolean isLoopbackDns(Node node) {
        return node instanceof ScalarNode resolver && Tag.STR.equals(resolver.getTag())
            && LOOPBACK_DNS.matcher(resolver.getValue().trim()).find();
    }

    private static void addDefaultGroup(Yaml yaml, List<NodeTuple> fields) {
        Set<String> proxyNames = new HashSet<>();
        for (NodeTuple tuple : fields) {
            if (!keyOf(tuple).equals("proxies") || !(tuple.getValueNode() instanceof SequenceNode proxies)) continue;
            for (Node node : proxies.getValue()) {
                if (!(node instanceof MappingNode proxy)) continue;
                for (NodeTuple field : proxy.getValue()) {
                    if (field.getKeyNode() instanceof ScalarNode key && key.getValue().equals("name")
                        && field.getValueNode() instanceof ScalarNode name) proxyNames.add(name.getValue());
                }
            }
        }
        String group = "MinClash";
        for (int suffix = 2; proxyNames.contains(group); suffix++) group = "MinClash " + suffix;
        String defaults = String.format(java.util.Locale.ROOT, """
            proxy-groups:
              - name: %s
                type: select
                include-all: true
            rules:
              - MATCH,%s
            """, group, group);
        fields.addAll(((MappingNode) yaml.compose(new StringReader(defaults))).getValue());
    }

    static List<String> routingTargets(byte[] content) throws IOException {
        MappingNode root = routingRoot(content);
        Set<String> names = new LinkedHashSet<>();
        names.add("DIRECT");
        names.addAll(namedEntries(field(root, "proxy-groups")).keySet());
        return new ArrayList<>(names);
    }

    static List<String> proxyDnsTargets(byte[] content) throws IOException {
        if (content.length > MAX_BYTES) throw new TooLarge();
        return proxyDnsTargets(routingRoot(content));
    }

    private static List<String> proxyDnsTargets(MappingNode root) {
        Map<String, MappingNode> groups = namedEntries(field(root, "proxy-groups"));
        Map<String, MappingNode> nodes = namedEntries(field(root, "proxies"));
        Set<String> providers = providerNames(field(root, "proxy-providers"));
        Set<String> remoteNodes = new LinkedHashSet<>();
        Set<String> rejects = new HashSet<>(List.of("REJECT", "REJECT-DROP"));
        nodes.forEach((name, node) -> {
            if (isProxyNode(node)) remoteNodes.add(name);
            if ("reject".equalsIgnoreCase(text(field(node, "type")))) rejects.add(name);
        });
        boolean hasDirectNode = nodes.keySet().stream().anyMatch(name -> !remoteNodes.contains(name) && !rejects.contains(name));
        List<String> targets = new ArrayList<>();
        groups.forEach((name, group) -> {
            if (safeProxyDnsName(name) && routesToProxy(group, groups, remoteNodes, providers)
                && keepsProxyDnsOffDirect(group, groups, remoteNodes, rejects, providers, hasDirectNode)) {
                targets.add(name);
            }
        });
        remoteNodes.stream().filter(Profile::safeProxyDnsName).forEach(targets::add);
        return List.copyOf(targets);
    }

    // The ordinary DNS dialer accepts a group selecting DIRECT, including its default
    // COMPATIBLE fallback. Offer only groups without a known path to direct DNS.
    private static boolean keepsProxyDnsOffDirect(MappingNode group, Map<String, MappingNode> groups,
                                                  Set<String> nodes, Set<String> rejects, Set<String> providers,
                                                  boolean hasDirectNode) {
        ArrayDeque<MappingNode> pending = new ArrayDeque<>();
        Set<MappingNode> visited = new HashSet<>();
        pending.add(group);
        while (!pending.isEmpty()) {
            MappingNode current = pending.remove();
            if (!visited.add(current)) continue;
            boolean all = enabled(current, "include-all");
            boolean allNodes = all || enabled(current, "include-all-proxies");
            boolean usesProviders = !emptyField(field(current, "use"))
                || !providers.isEmpty() && (all || enabled(current, "include-all-providers"));
            String excluded = text(field(current, "exclude-type"));
            if (usesProviders || allNodes && hasDirectNode) {
                if (Arrays.stream(excluded.split("\\|")).noneMatch("Direct"::equalsIgnoreCase)) return false;
            }
            if (usesProviders || !excluded.isEmpty() || !text(field(current, "exclude-filter")).isEmpty()
                || allNodes && (nodes.isEmpty() || !text(field(current, "filter")).isEmpty())) {
                String fallback = text(field(current, "empty-fallback"));
                if (!nodes.contains(fallback) && !rejects.contains(fallback)) return false;
            }
            if (field(current, "proxies") instanceof SequenceNode members) {
                for (Node member : members.getValue()) {
                    String name = text(member);
                    if (nodes.contains(name) || rejects.contains(name)) continue;
                    MappingNode nested = groups.get(name);
                    if (nested == null) return false;
                    pending.add(nested);
                }
            }
        }
        return true;
    }

    private static boolean safeProxyDnsName(String name) {
        return safeAliasName(name) && !name.trim().isEmpty() && !name.equalsIgnoreCase("RULES") && !name.contains("=");
    }

    static List<String> protectionAliasTargets(byte[] content, String alias) throws IOException {
        if (content.length > MAX_BYTES) throw new TooLarge();
        return protectionAliasOptions(routingRoot(content), alias).targets();
    }

    static byte[] withProtectionAlias(byte[] content, String alias, String target) throws IOException {
        if (content.length > MAX_BYTES) throw new TooLarge();
        MappingNode root = routingRoot(content);
        ProtectionAliasOptions options = protectionAliasOptions(root, alias);
        if (!safeAliasName(target) || !options.targets().contains(target)) {
            throw new IOException("请选择新配置中可用的代理组作为防泄漏出口。");
        }
        Yaml yaml = new Yaml();
        Map<String, Object> selector = new LinkedHashMap<>();
        selector.put("name", alias);
        selector.put("type", "select");
        selector.put("hidden", true);
        selector.put("proxies", List.of(target));
        selector.put("empty-fallback", "REJECT");
        List<Node> groups = new ArrayList<>(options.groups().getValue());
        groups.add(yaml.represent(selector));
        setField(root, yaml, "proxy-groups", new SequenceNode(Tag.SEQ, groups, options.groups().getFlowStyle()));
        StringWriter output = new StringWriter();
        yaml.serialize(root, output);
        byte[] updated = output.toString().getBytes(StandardCharsets.UTF_8);
        if (updated.length > MAX_BYTES) throw new TooLarge();
        return updated;
    }

    private record ProtectionAliasOptions(SequenceNode groups, List<String> targets) {}

    private static ProtectionAliasOptions protectionAliasOptions(MappingNode root, String alias) throws IOException {
        if (!safeAliasName(alias)) throw new IOException("防泄漏出口名称无效。");
        if (!(field(root, "proxy-groups") instanceof SequenceNode configured)) {
            return new ProtectionAliasOptions(null, List.of());
        }
        Map<String, MappingNode> groups = namedEntries(configured);
        Map<String, MappingNode> nodes = namedEntries(field(root, "proxies"));
        Set<String> providers = providerNames(field(root, "proxy-providers"));
        if (groups.containsKey(alias) || nodes.containsKey(alias) || providers.contains(alias)
            || providerNames(field(root, "rule-providers")).contains(alias)) {
            throw new IOException("防泄漏出口名称与新配置中的现有名称冲突。");
        }
        Set<String> remoteNodes = new HashSet<>();
        nodes.forEach((name, node) -> { if (isProxyNode(node)) remoteNodes.add(name); });
        Set<String> dependents = aliasDependents(groups, alias);
        List<String> targets = new ArrayList<>();
        groups.forEach((name, group) -> {
            if (safeAliasName(name) && !dependents.contains(name) && routesToProxy(group, groups, remoteNodes, providers)) {
                targets.add(name);
            }
        });
        return new ProtectionAliasOptions(configured, List.copyOf(targets));
    }

    private static boolean safeAliasName(String name) {
        return ProtectionPolicy.safeTarget(name) && !name.equalsIgnoreCase("PASS-RULE") && !name.equalsIgnoreCase("default");
    }

    private static Set<String> providerNames(Node node) {
        Set<String> names = new HashSet<>();
        if (node instanceof MappingNode providers) {
            for (NodeTuple entry : providers.getValue()) if (entry.getKeyNode() instanceof ScalarNode key) names.add(key.getValue());
        }
        return names;
    }

    private static Set<String> aliasDependents(Map<String, MappingNode> groups, String alias) {
        Map<String, List<String>> parents = new LinkedHashMap<>();
        groups.forEach((name, group) -> {
            if (field(group, "proxies") instanceof SequenceNode members) {
                for (Node member : members.getValue()) {
                    parents.computeIfAbsent(text(member), unused -> new ArrayList<>()).add(name);
                }
            }
        });
        Set<String> dependents = new HashSet<>();
        ArrayDeque<String> pending = new ArrayDeque<>();
        pending.add(alias);
        while (!pending.isEmpty()) {
            String name = pending.remove();
            if (dependents.add(name)) pending.addAll(parents.getOrDefault(name, List.of()));
        }
        return dependents;
    }

    enum GroupUpdate { CREATED, FILLED, NONE }

    record GroupChange(String name, int nodes, int providers, GroupUpdate update, boolean addedCatchAll) {}
    record LocalYaml(byte[] source, GroupChange group) {
        String addedGroup() { return group == null || group.update() == GroupUpdate.NONE ? "" : group.name(); }
        boolean addedCatchAll() { return group != null && group.addedCatchAll(); }
    }

    private static GroupChange completeMainGroup(MappingNode root, Yaml yaml) {
        Node configuredGroups = field(root, "proxy-groups");
        if (configuredGroups != null && !Tag.NULL.equals(configuredGroups.getTag()) && !(configuredGroups instanceof SequenceNode)) return null;
        Map<String, MappingNode> groups = namedEntries(configuredGroups);
        Map.Entry<String, MappingNode> primary = groups.entrySet().stream()
            .filter(entry -> ProxyGroupSection.isProxies(entry.getKey())).findFirst().orElse(null);
        if (primary == null) {
            for (Map.Entry<String, MappingNode> entry : groups.entrySet()) {
                if (ProxyGroupSection.isGlobal(entry.getKey()) || ProxyGroupSection.of(entry.getKey()) != ProxyGroupSection.MAIN) continue;
                if (hasGroupMembers(entry.getValue())) {
                    primary = entry;
                    break;
                }
                if (primary == null) primary = entry;
            }
        }
        boolean populated = primary != null && hasGroupMembers(primary.getValue());
        if (populated && !canAddCatchAll(root, primary.getKey())) return null;

        Map<String, MappingNode> declared = namedEntries(field(root, "proxies"));
        List<String> nodes = new ArrayList<>();
        for (Map.Entry<String, MappingNode> node : declared.entrySet()) {
            if (!node.getKey().trim().isEmpty() && isProxyNode(node.getValue())) nodes.add(node.getKey());
        }
        List<String> providers = new ArrayList<>();
        if (field(root, "proxy-providers") instanceof MappingNode providerMap) {
            for (NodeTuple entry : providerMap.getValue()) {
                String key = text(entry.getKeyNode());
                if (!key.trim().isEmpty() && entry.getValueNode() instanceof MappingNode) providers.add(key);
            }
        }
        if (nodes.isEmpty() && providers.isEmpty()) return null;
        if (populated) {
            if (!routesToProxy(primary.getValue(), groups, new HashSet<>(nodes), new HashSet<>(providers))) return null;
            addCatchAll(root, yaml, primary.getKey());
            return new GroupChange(primary.getKey(), 0, 0, GroupUpdate.NONE, true);
        }

        String name = primary == null ? unusedGroupName(declared.keySet(), providers, groups.keySet()) : primary.getKey();
        List<Node> entries = configuredGroups instanceof SequenceNode list ? new ArrayList<>(list.getValue()) : new ArrayList<>();
        List<NodeTuple> settings = primary == null ? new ArrayList<>() : new ArrayList<>(primary.getValue().getValue());
        MappingNode group = new MappingNode(Tag.MAP, settings, org.yaml.snakeyaml.DumperOptions.FlowStyle.BLOCK);
        if (primary == null) setField(group, yaml, "name", yaml.represent(name));
        if (emptyField(field(group, "type"))) setField(group, yaml, "type", yaml.represent("select"));
        if (!nodes.isEmpty()) setField(group, yaml, "proxies", yaml.represent(nodes));
        if (!providers.isEmpty()) {
            setField(group, yaml, "use", yaml.represent(providers));
            String excluded = text(field(group, "exclude-type"));
            String specials = "Direct|Reject|RejectDrop|Dns|Pass|Compatible|Rematch";
            setField(group, yaml, "exclude-type", yaml.represent(excluded.isEmpty() ? specials : excluded + "|" + specials));
            if (emptyField(field(group, "empty-fallback"))) setField(group, yaml, "empty-fallback", yaml.represent("REJECT"));
        }
        if (primary == null) entries.add(group);
        else entries.set(entries.indexOf(primary.getValue()), group);
        setField(root, yaml, "proxy-groups", new SequenceNode(Tag.SEQ, entries, org.yaml.snakeyaml.DumperOptions.FlowStyle.BLOCK));

        boolean catchAll = canAddCatchAll(root, name);
        if (catchAll) addCatchAll(root, yaml, name);
        return new GroupChange(name, nodes.size(), providers.size(), primary == null ? GroupUpdate.CREATED : GroupUpdate.FILLED, catchAll);
    }

    private static boolean canAddCatchAll(MappingNode root, String name) {
        Node mode = field(root, "mode");
        return field(root, "rules") == null && name.matches("[^\\r\\n,]+")
            && (mode == null || "rule".equalsIgnoreCase(text(mode)));
    }

    private static void addCatchAll(MappingNode root, Yaml yaml, String name) {
        setField(root, yaml, "rules", yaml.represent(Collections.singletonList("MATCH," + name)));
    }

    private static boolean routesToProxy(MappingNode primary, Map<String, MappingNode> groups, Set<String> nodes, Set<String> providers) {
        ArrayDeque<MappingNode> pending = new ArrayDeque<>();
        Set<MappingNode> visited = new HashSet<>();
        pending.add(primary);
        while (!pending.isEmpty()) {
            MappingNode group = pending.remove();
            if (!visited.add(group)) continue;
            boolean all = enabled(group, "include-all");
            if (!nodes.isEmpty() && (all || enabled(group, "include-all-proxies"))) return true;
            if (!providers.isEmpty() && (all || enabled(group, "include-all-providers"))) return true;
            if (field(group, "use") instanceof SequenceNode use
                && use.getValue().stream().map(Profile::text).anyMatch(providers::contains)) return true;
            if (field(group, "proxies") instanceof SequenceNode members) {
                for (Node member : members.getValue()) {
                    String name = text(member);
                    if (nodes.contains(name)) return true;
                    MappingNode nested = groups.get(name);
                    if (nested != null) pending.add(nested);
                }
            }
        }
        return false;
    }

    private static String unusedGroupName(Set<String> nodes, List<String> providers, Set<String> groups) {
        Set<String> occupied = new HashSet<>();
        for (String node : nodes) occupied.add(node.toLowerCase(Locale.ROOT));
        for (String provider : providers) occupied.add(provider.toLowerCase(Locale.ROOT));
        for (String group : groups) occupied.add(group.toLowerCase(Locale.ROOT));
        String name = "PROXIES";
        for (int suffix = 1; occupied.contains(name.toLowerCase(Locale.ROOT)); suffix++) {
            name = suffix == 1 ? "MinClash" : "MinClash " + suffix;
        }
        return name;
    }

    private static boolean hasGroupMembers(MappingNode group) {
        return !emptyField(field(group, "proxies")) || !emptyField(field(group, "use"))
            || enabled(group, "include-all") || enabled(group, "include-all-proxies") || enabled(group, "include-all-providers");
    }

    private static boolean emptyField(Node node) {
        return node == null || Tag.NULL.equals(node.getTag()) || (node instanceof SequenceNode sequence && sequence.getValue().isEmpty());
    }

    private static void setField(MappingNode map, Yaml yaml, String key, Node value) {
        List<NodeTuple> fields = map.getValue();
        for (int i = 0; i < fields.size(); i++) {
            NodeTuple field = fields.get(i);
            if (field.getKeyNode() instanceof ScalarNode scalar && scalar.getValue().equals(key)) {
                fields.set(i, new NodeTuple(scalar, value));
                return;
            }
        }
        fields.add(new NodeTuple(yaml.represent(key), value));
    }

    private static boolean isProxyNode(MappingNode node) {
        if (node == null) return false;
        String type = text(field(node, "type")).trim().toLowerCase(Locale.ROOT);
        return !type.isEmpty() && !NON_PROXY_TYPES.contains(type);
    }

    private static String text(Node node) {
        return node instanceof ScalarNode scalar && Tag.STR.equals(scalar.getTag()) ? scalar.getValue() : "";
    }

    record PreviewGroup(Set<String> members, boolean allNodes, boolean providerNodes) {}
    record PreviewLayout(Set<String> nodes, Map<String, PreviewGroup> groups, boolean hasProviders) {}

    static PreviewLayout previewLayout(byte[] content) throws IOException {
        MappingNode root = routingRoot(content);
        Set<String> nodes = namedEntries(field(root, "proxies")).keySet();
        boolean hasProviders = field(root, "proxy-providers") instanceof MappingNode providers && !providers.getValue().isEmpty();
        Map<String, PreviewGroup> groups = new TreeMap<>();
        namedEntries(field(root, "proxy-groups")).forEach((name, group) -> {
            Set<String> members = new HashSet<>();
            if (field(group, "proxies") instanceof SequenceNode list) {
                for (Node node : list.getValue()) if (node instanceof ScalarNode member) members.add(member.getValue());
            }
            boolean all = enabled(group, "include-all");
            boolean usesProviders = field(group, "use") instanceof SequenceNode use && !use.getValue().isEmpty();
            groups.put(name, new PreviewGroup(members, all || enabled(group, "include-all-proxies"),
                usesProviders || hasProviders && (all || enabled(group, "include-all-providers"))));
        });
        return new PreviewLayout(nodes, groups, hasProviders);
    }

    private static Map<String, MappingNode> namedEntries(Node nodeList) {
        Map<String, MappingNode> entries = new LinkedHashMap<>();
        if (nodeList instanceof SequenceNode sequence) {
            for (Node node : sequence.getValue()) {
                if (node instanceof MappingNode item && field(item, "name") instanceof ScalarNode name) {
                    entries.put(name.getValue(), item);
                }
            }
        }
        return entries;
    }

    private static boolean enabled(MappingNode mapping, String key) {
        if (!(field(mapping, key) instanceof ScalarNode flag)) return false;
        return switch (flag.getValue().toLowerCase(Locale.ROOT)) {
            case "true", "yes", "on", "y" -> true;
            default -> false;
        };
    }

    // Always merge into the saved subscription source, never into an earlier merged profile.
    static byte[] withAppRules(byte[] source, Map<String, String> applications) throws IOException {
        return withPersonalRules(source, applications, java.util.Collections.emptyList());
    }

    static byte[] withPersonalRules(byte[] source, Map<String, String> applications, List<PersonalRules.Rule> personal) throws IOException {
        return withPersonalRules(source, applications, personal, Collections.emptySet());
    }

    static byte[] withPersonalRules(byte[] source, Map<String, String> applications, List<PersonalRules.Rule> personal,
                                    Set<String> bypassedApps) throws IOException {
        TunBypass.validate(bypassedApps, applications);
        byte[] base = repairDns(normalize(source));
        if (applications.isEmpty() && personal.isEmpty() && bypassedApps.isEmpty()) return base;
        Yaml yaml = new Yaml();
        MappingNode root = routingRoot(base);
        if (!bypassedApps.isEmpty()) {
            MappingNode tun = (MappingNode) field(root, "tun");
            tun.getValue().add(new NodeTuple(yaml.represent("exclude-package"), yaml.represent(new ArrayList<>(new java.util.TreeSet<>(bypassedApps)))));
            // Android's shared resolver can otherwise give excluded games unreachable Fake-IPs.
            MappingNode dns = (MappingNode) field(root, "dns");
            dns.getValue().removeIf(tuple -> keyOf(tuple).equals("enhanced-mode"));
            dns.getValue().add(new NodeTuple(yaml.represent("enhanced-mode"), yaml.represent("redir-host")));
        }
        Set<String> targets = new HashSet<>(routingTargets(base));
        List<Node> rules = new ArrayList<>();
        for (Map.Entry<String, String> entry : new TreeMap<>(applications).entrySet()) {
            if (!entry.getKey().matches("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)*")) {
                throw new IOException("应用包名无效：" + entry.getKey());
            }
            if (!targets.contains(entry.getValue())) {
                throw new MissingRoutingTarget("应用 " + entry.getKey() + " 指定的代理组「" + entry.getValue()
                    + "」已不存在。请为这份配置重新选择应用分流出口。");
            }
            rules.add(yaml.represent("PROCESS-NAME," + entry.getKey() + "," + entry.getValue()));
        }
        Set<String> unique = new HashSet<>();
        if (personal.size() > 2000) throw new IOException("自定义规则最多 2000 条。");
        for (PersonalRules.Rule saved : personal) {
            PersonalRules.Rule rule = PersonalRules.create(saved.type(), saved.value(), saved.target());
            if (!targets.contains(rule.target()) && !rule.target().equals("REJECT")) {
                throw new MissingRoutingTarget("自定义规则「" + rule.value() + "」指定的出口已不存在，请先调整自定义分流。");
            }
            if (!unique.add(rule.type() + "," + rule.value())) throw new IOException("有重复的自定义规则。");
            rules.add(yaml.represent(rule.line()));
        }
        for (NodeTuple tuple : root.getValue()) {
            if (!keyOf(tuple).equals("rules")) continue;
            if (!(tuple.getValueNode() instanceof SequenceNode imported)) {
                throw new IOException("配置中的 rules 必须是规则列表。");
            }
            rules.addAll(imported.getValue());
        }
        root.getValue().removeIf(tuple -> keyOf(tuple).equals("rules") || !applications.isEmpty() && keyOf(tuple).equals("find-process-mode"));
        if (!applications.isEmpty()) root.getValue().add(new NodeTuple(yaml.represent("find-process-mode"), yaml.represent("always")));
        root.getValue().add(new NodeTuple(yaml.represent("rules"), new SequenceNode(Tag.SEQ, rules,
            org.yaml.snakeyaml.DumperOptions.FlowStyle.BLOCK)));
        StringWriter output = new StringWriter();
        yaml.serialize(root, output);
        byte[] result = output.toString().getBytes(StandardCharsets.UTF_8);
        if (result.length > MAX_BYTES) throw new IOException("合并应用规则后，配置超过 2 MiB。");
        return result;
    }

    // Only the live copy carries session overrides; keep the saved subscription source unchanged.
    static byte[] forLiveReload(byte[] content, String mode, boolean identifyProcesses) throws IOException {
        List<String> modes = Arrays.asList("rule", "global", "direct");
        if (!modes.contains(mode)) throw new IOException("无法读取当前代理模式。");
        MappingNode root = routingRoot(content);
        // Overriding a session field must not hide an invalid persistent value (for example
        // removing the last app rule exposes the source's original find-process-mode).
        validateLiveField(root, "mode", modes);
        validateLiveField(root, "find-process-mode", Arrays.asList("strict", "always", "off"));
        Yaml yaml = new Yaml();
        root.getValue().removeIf(tuple -> keyOf(tuple).equals("mode")
            || identifyProcesses && keyOf(tuple).equals("find-process-mode"));
        root.getValue().add(new NodeTuple(yaml.represent("mode"), yaml.represent(mode)));
        if (identifyProcesses) root.getValue().add(new NodeTuple(yaml.represent("find-process-mode"), yaml.represent("always")));
        StringWriter output = new StringWriter();
        yaml.serialize(root, output);
        byte[] result = output.toString().getBytes(StandardCharsets.UTF_8);
        if (result.length > MAX_BYTES) throw new TooLarge();
        return result;
    }

    private static void validateLiveField(MappingNode root, String name, List<String> values) throws IOException {
        Node node = field(root, name);
        if (node == null || Tag.NULL.equals(node.getTag())) return;
        if (!(node instanceof ScalarNode scalar) || !Tag.STR.equals(scalar.getTag())
            || !values.contains(scalar.getValue().toLowerCase(Locale.ROOT))) {
            throw new IOException("配置中的 " + name + " 无效，请先修正源配置。");
        }
    }

    private static MappingNode routingRoot(byte[] content) throws IOException {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        options.setMergeOnCompose(true);
        options.setNestingDepthLimit(40);
        options.setCodePointLimit(MAX_BYTES);
        try {
            Node parsed = new Yaml(options).compose(new StringReader(new String(content, StandardCharsets.UTF_8)));
            if (!(parsed instanceof MappingNode root)) throw new IOException("无法读取当前配置。");
            keysOf(root);
            return root;
        } catch (YAMLException error) {
            throw new IOException("无法读取当前配置的分流规则。", error);
        }
    }

    private static Set<String> keysOf(MappingNode node) throws IOException {
        Set<String> keys = new HashSet<>();
        for (NodeTuple tuple : node.getValue()) {
            if (!(tuple.getKeyNode() instanceof ScalarNode key) || !Tag.STR.equals(key.getTag())) {
                throw new IOException("配置顶层和 dns 只接受文本键。");
            }
            if (!keys.add(key.getValue())) throw new IOException("配置键重复，检查顶层和 dns 设置。");
        }
        return keys;
    }

    private static String keyOf(NodeTuple tuple) {
        return ((ScalarNode) tuple.getKeyNode()).getValue();
    }

    private static Node field(MappingNode mapping, String name) {
        for (NodeTuple tuple : mapping.getValue()) {
            if (tuple.getKeyNode() instanceof ScalarNode key && key.getValue().equals(name)) return tuple.getValueNode();
        }
        return null;
    }

    private Profile() {}
}

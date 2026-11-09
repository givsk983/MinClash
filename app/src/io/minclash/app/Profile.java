package io.minclash.app;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
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
    private static final Pattern LOOPBACK_DNS = Pattern.compile(
        "(?i)^(?:[a-z][a-z0-9+.-]*://)?(?:localhost\\.?|127(?:\\.[0-9]{1,3}){3}|\\[::1\\])(?=[:/?#]|$)|^::1$");
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
        String source = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(content)).toString();
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
            if (!keys.contains("rules") && !keys.contains("proxy-groups")) addDefaultGroup(yaml, fields);
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
            if (normalized.length > MAX_BYTES) throw new IOException("应用 TUN 设置后，配置超过 2 MiB。");
            return normalized;
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
        return resolvers.getValue().stream().anyMatch(node -> node instanceof ScalarNode resolver
            && LOOPBACK_DNS.matcher(resolver.getValue().trim()).find());
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
        String defaults = """
            proxy-groups:
              - name: %s
                type: select
                include-all: true
            rules:
              - MATCH,%s
            """.formatted(group, group);
        fields.addAll(((MappingNode) yaml.compose(new StringReader(defaults))).getValue());
    }

    static List<String> routingTargets(byte[] content) throws IOException {
        MappingNode root = routingRoot(content);
        Set<String> names = new LinkedHashSet<>();
        names.add("DIRECT");
        for (NodeTuple tuple : root.getValue()) {
            if (!keyOf(tuple).equals("proxy-groups") || !(tuple.getValueNode() instanceof SequenceNode groups)) continue;
            for (Node group : groups.getValue()) {
                if (!(group instanceof MappingNode mapping)) continue;
                for (NodeTuple field : mapping.getValue()) {
                    if (keyOf(field).equals("name") && field.getValueNode() instanceof ScalarNode name) {
                        names.add(name.getValue());
                    }
                }
            }
        }
        return new ArrayList<>(names);
    }

    // Always merge into the saved subscription source, never into an earlier merged profile.
    static byte[] withAppRules(byte[] source, Map<String, String> applications) throws IOException {
        byte[] base = normalize(source);
        if (applications.isEmpty()) return base;
        Yaml yaml = new Yaml();
        MappingNode root = routingRoot(base);
        Set<String> targets = new HashSet<>(routingTargets(base));
        List<Node> rules = new ArrayList<>();
        for (Map.Entry<String, String> entry : new TreeMap<>(applications).entrySet()) {
            if (!entry.getKey().matches("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)*")) {
                throw new IOException("应用包名无效：" + entry.getKey());
            }
            if (!targets.contains(entry.getValue())) {
                throw new IOException("应用 " + entry.getKey() + " 指定的代理组「" + entry.getValue()
                    + "」已不存在。请先在应用分流中重新选择，再更新订阅。");
            }
            rules.add(yaml.represent("PROCESS-NAME," + entry.getKey() + "," + entry.getValue()));
        }
        for (NodeTuple tuple : root.getValue()) {
            if (!keyOf(tuple).equals("rules")) continue;
            if (!(tuple.getValueNode() instanceof SequenceNode imported)) {
                throw new IOException("配置中的 rules 必须是规则列表。");
            }
            rules.addAll(imported.getValue());
        }
        root.getValue().removeIf(tuple -> keyOf(tuple).equals("rules") || keyOf(tuple).equals("find-process-mode"));
        root.getValue().add(new NodeTuple(yaml.represent("find-process-mode"), yaml.represent("always")));
        root.getValue().add(new NodeTuple(yaml.represent("rules"), new SequenceNode(Tag.SEQ, rules,
            org.yaml.snakeyaml.DumperOptions.FlowStyle.BLOCK)));
        StringWriter output = new StringWriter();
        yaml.serialize(root, output);
        byte[] result = output.toString().getBytes(StandardCharsets.UTF_8);
        if (result.length > MAX_BYTES) throw new IOException("合并应用规则后，配置超过 2 MiB。");
        return result;
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

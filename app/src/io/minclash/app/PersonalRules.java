package io.minclash.app;

import java.io.IOException;
import java.net.IDN;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

final class PersonalRules {
    static final String[] TYPES = {"DOMAIN", "DOMAIN-SUFFIX", "IP-CIDR", "IP-CIDR6"};
    record Rule(String type, String value, String target) {
        String line() { return type + "," + value + "," + target + (type.startsWith("IP-") ? ",no-resolve" : ""); }
    }

    static Rule create(String type, String value, String target) throws IOException {
        if (!java.util.Arrays.asList(TYPES).contains(type)) throw new IOException("不支持的规则类型。");
        if (target.isEmpty() || target.length() > 4096 || !target.matches("[^\\r\\n,]+")) throw new IOException("出口名称无效。");
        value = value.trim();
        if (value.isEmpty() || value.length() > 1024) throw new IOException("请输入有效域名或 IP 网段。");
        try {
            if (type.startsWith("DOMAIN")) {
                if (value.endsWith(".")) value = value.substring(0, value.length() - 1);
                value = IDN.toASCII(value, IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);
                if (value.length() > 253 || value.isEmpty()) throw new IllegalArgumentException();
                for (String label : value.split("\\.", -1)) {
                    if (!label.matches("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?")) throw new IllegalArgumentException();
                }
            } else {
                String[] parts = value.split("/", -1);
                if (parts.length > 2 || parts.length == 2 && !parts[1].matches("[0-9]{1,3}")) throw new IllegalArgumentException();
                byte[] address;
                if (type.equals("IP-CIDR")) {
                    String[] octets = parts[0].split("\\.", -1);
                    if (octets.length != 4) throw new IllegalArgumentException();
                    address = new byte[4];
                    for (int i = 0; i < 4; i++) {
                        if (!octets[i].matches("0|[1-9][0-9]{0,2}")) throw new IllegalArgumentException();
                        int octet = Integer.parseInt(octets[i]);
                        if (octet > 255) throw new IllegalArgumentException();
                        address[i] = (byte) octet;
                    }
                } else {
                    // Only hexadecimal IPv6 literals reach getByName; never resolve a hostname.
                    if (!parts[0].contains(":") || !parts[0].matches("[0-9a-fA-F:]+")) throw new IllegalArgumentException();
                    address = InetAddress.getByName(parts[0]).getAddress();
                    if (address.length != 16) throw new IllegalArgumentException();
                }
                int prefix = parts.length == 1 ? address.length * 8 : Integer.parseInt(parts[1]);
                if (prefix > address.length * 8) throw new IllegalArgumentException();
                for (int bit = prefix; bit < address.length * 8; bit++) address[bit / 8] &= (byte) ~(1 << (7 - bit % 8));
                value = InetAddress.getByAddress(address).getHostAddress() + "/" + prefix;
            }
        } catch (Exception invalid) { throw new IOException("域名请只填写主机名；IP 请填写有效地址或 CIDR 网段。", invalid); }
        return new Rule(type, value, target);
    }

    static JSONArray json(List<Rule> rules) throws Exception {
        JSONArray result = new JSONArray();
        for (Rule rule : rules) result.put(new JSONObject().put("type", rule.type()).put("value", rule.value()).put("target", rule.target()));
        return result;
    }

    static List<Rule> parse(JSONArray array) throws Exception {
        if (array == null) return java.util.Collections.emptyList();
        if (array.length() > 2000) throw new IOException("自定义规则最多 2000 条。");
        List<Rule> rules = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < array.length(); i++) {
            JSONObject object = array.getJSONObject(i);
            Rule rule = create(object.getString("type"), object.getString("value"), object.getString("target"));
            if (!seen.add(rule.type() + "," + rule.value())) throw new IOException("有重复的自定义规则。");
            rules.add(rule);
        }
        return rules;
    }
    private PersonalRules() {}
}

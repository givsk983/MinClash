package io.minclash.app;

import java.util.Locale;
import java.util.regex.Pattern;

enum ProxyGroupSection {
    MAIN("主代理"), REGION("地区线路"), SERVICE("应用与服务"), OTHER("其他策略");

    private static final Pattern DECORATION = Pattern.compile("^[^\\p{L}\\p{N}]+|[^\\p{L}\\p{N}]+$");
    private static final Pattern REGION_NAME = Pattern.compile(
        "(?:hk|tw|mo|jp|sg|us|uk|gb|kr|ko|de|fr|ca|au|in|cn|my|th|vn|id|ph|nl|ru|tr|eu|sea|uae"
        + "|hong kong|taiwan|macao|macau|japan|singapore|united states|usa|united kingdom|korea|south korea"
        + "|germany|france|canada|australia|india|china|malaysia|thailand|vietnam|indonesia|philippines"
        + "|netherlands|russia|turkey|europe|southeast asia|香港|台湾|台灣|澳门|澳門|日本|新加坡|狮城"
        + "|美国|美國|英国|英國|韩国|韓國|德国|法國|法国|加拿大|澳洲|澳大利亚|印度|中国|中國|大陆|大陸"
        + "|马来西亚|泰国|越南|印度尼西亚|菲律宾|荷兰|俄罗斯|土耳其|欧洲|东南亚|阿联酋)"
        + "(?:[\\s_-]*(?:节点|线路|地区|自动|手动|故障转移|负载均衡|nodes?|auto|manual|fallback|url-test|[0-9]))*");
    private static final Pattern SERVICE_NAME = Pattern.compile(
        "(?<![a-z0-9])(?:apple|bahamut|bilibili|disney(?:plus)?|google|youtube|netflix|telegram|microsoft|github"
        + "|openai|chatgpt|claude|anthropic|gemini|spotify|tiktok|twitter|x|facebook|instagram|whatsapp|discord"
        + "|steam|epic|paypal|amazon|prime video|hbo|hulu|primevideo|onedrive|icloud|wechat|cloudflare|reddit)"
        + "(?![a-z0-9])|巴哈姆特|哔哩哔哩|嗶哩嗶哩|谷歌|油管|奈飞|奈飛|微软|微軟|微信|电报|迪士尼"
        + "|流媒体|串流|游戏|遊戲|社交|人工智能|\\bai\\b");

    final String label;

    ProxyGroupSection(String label) { this.label = label; }

    static boolean isProxies(String name) { return clean(name).equals("proxies"); }

    static boolean isGlobal(String name) { return clean(name).equals("global"); }

    static ProxyGroupSection of(String name) {
        String key = clean(name);
        return switch (key) {
            case "proxies", "proxy", "global", "minclash", "节点选择", "節點選擇", "代理选择", "代理選擇",
                 "手动切换", "自动选择", "auto", "url-test", "select", "proxy select" -> MAIN;
            default -> {
                if (REGION_NAME.matcher(key).matches()) yield REGION;
                if (SERVICE_NAME.matcher(key).find()) yield SERVICE;
                yield OTHER;
            }
        };
    }

    private static String clean(String name) {
        return DECORATION.matcher(name).replaceAll("").toLowerCase(Locale.ROOT);
    }
}

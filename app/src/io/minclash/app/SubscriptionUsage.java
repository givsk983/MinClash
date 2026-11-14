package io.minclash.app;

import android.content.SharedPreferences;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.json.JSONObject;

record SubscriptionUsage(long upload, long download, long total, long expire, long checkedAt) {
    SubscriptionUsage {
        upload = Math.max(-1, upload); download = Math.max(-1, download); total = Math.max(-1, total);
        if (expire <= 0 || expire > 253402300799L) expire = -1;
        checkedAt = Math.max(0, checkedAt);
    }
    static SubscriptionUsage parse(String header, long now) {
        Map<String, Long> fields = new HashMap<>();
        Set<String> seen = new HashSet<>();
        if (header != null && header.length() <= 8192) for (String field : header.split(";")) {
            String[] pair = field.trim().split("=", -1);
            if (pair.length != 2) continue;
            String key = pair[0].trim().toLowerCase(Locale.ROOT);
            String value = pair[1].trim();
            if (!seen.add(key)) { fields.put(key, -1L); continue; }
            long parsed = -1;
            try { if (value.matches("[0-9]{1,19}")) parsed = Long.parseLong(value); }
            catch (NumberFormatException invalid) { /* Unknown, never a fabricated zero. */ }
            fields.put(key, parsed);
        }
        long expiry = fields.getOrDefault("expire", -1L);
        if (expiry <= 0 || expiry > 253402300799L) expiry = -1;
        return new SubscriptionUsage(fields.getOrDefault("upload", -1L), fields.getOrDefault("download", -1L),
            fields.getOrDefault("total", -1L), expiry, now);
    }

    long used() { return upload < 0 || download < 0 || upload > Long.MAX_VALUE - download ? -1 : upload + download; }
    long remaining() { return total <= 0 || used() < 0 ? -1 : Math.max(0, total - used()); }
    double remainingRatio() { return remaining() < 0 ? -1 : (double) remaining() / total; }
    String expiryLabel(long now) {
        if (expire < 0) return "有效期未提供";
        long left = expire * 1000 - now;
        if (left <= 0) return "订阅已到期";
        if (left < 3_600_000) return "不足 1 小时到期";
        if (left < 86_400_000) return ((left + 3_599_999) / 3_600_000) + " 小时后到期";
        return ((left + 86_399_999) / 86_400_000) + " 天后到期";
    }
    String expiryDate() {
        return expire < 0 ? "服务商未提供到期时间" : new java.text.SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault())
            .format(new java.util.Date(expire * 1000)) + " 到期";
    }
    String freshness(long now) {
        if (checkedAt == 0) return "尚未读取服务商用量";
        return "服务商快照 · " + new java.text.SimpleDateFormat("MM/dd HH:mm", Locale.getDefault())
            .format(new java.util.Date(checkedAt)) + (now - checkedAt >= 86_400_000 ? "（较旧）" : "");
    }
    String summary() {
        if (checkedAt == 0) return "用量和到期时间尚未读取";
        return (remaining() < 0 ? "剩余未提供" : "剩余 " + bytes(remaining())) + (total < 0 ? " · 总额未提供" : " / " + bytes(total))
            + " · " + expiryLabel(System.currentTimeMillis());
    }
    String detail(long now) {
        String expiry = expire < 0 ? "服务商未提供" : java.text.DateFormat.getDateTimeInstance().format(new java.util.Date(expire * 1000))
            + (expire <= now / 1000 ? "（已到期）" : "");
        return summary() + "\n已用  " + bytes(used()) + "\n上传  " + bytes(upload) + "\n下载  " + bytes(download)
            + "\n剩余  " + bytes(remaining()) + (total > 0 && used() > total ? "（已超额）" : "")
            + "\n到期时间  " + expiry + "\n最近读取  "
            + (checkedAt == 0 ? "尚未读取" : java.text.DateFormat.getDateTimeInstance().format(new java.util.Date(checkedAt)))
            + "\n\n数据来自服务商 Subscription-Userinfo 响应头，是最近一次读取的快照；缺失字段不推测，不代表实时流量。总额为 0 时展示原值，不推断是否不限量或已超额。";
    }
    static String bytes(long count) {
        if (count < 0) return "未提供";
        String[] units = {"B", "KiB", "MiB", "GiB", "TiB", "PiB", "EiB"};
        double value = count;
        int unit = 0;
        while (value >= 1024 && unit < units.length - 1) { value /= 1024; unit++; }
        return unit == 0 ? count + " B" : String.format(Locale.ROOT, "%.1f %s", value, units[unit]);
    }
    private static String key(String address) { return NodeList.favoritesKey(address).replace("node_favorites_", "subscription_usage_"); }
    static SubscriptionUsage load(SharedPreferences prefs, String address) {
        try {
            JSONObject object = new JSONObject(prefs.getString(key(address), "{}"));
            return new SubscriptionUsage(object.optLong("upload", -1), object.optLong("download", -1), object.optLong("total", -1),
                object.optLong("expire", -1), object.optLong("checkedAt", 0));
        } catch (Exception invalid) { return new SubscriptionUsage(-1, -1, -1, -1, 0); }
    }
    void save(SharedPreferences prefs, String address) throws Exception {
        JSONObject object = new JSONObject().put("upload", upload).put("download", download).put("total", total)
            .put("expire", expire).put("checkedAt", checkedAt);
        if (!prefs.edit().putString(key(address), object.toString()).commit()) throw new java.io.IOException("无法保存订阅用量。");
    }

    boolean cache(SharedPreferences prefs, String address) {
        try { save(prefs, address); return true; }
        catch (Exception unavailable) { return false; }
    }
}

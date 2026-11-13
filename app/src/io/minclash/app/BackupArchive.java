package io.minclash.app;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import org.json.JSONArray;
import org.json.JSONObject;

/** Versioned, bounded portable archive. No paths, executable commands or device permissions. */
final class BackupArchive {
    static final int MAX_BYTES = 16 * 1024 * 1024;
    private static final byte[] MAGIC = "MINCLASH-BACKUP\n".getBytes(StandardCharsets.US_ASCII);
    private static final int ITERATIONS = 210_000;
    record Data(RoutingStore.State state, List<SubscriptionCatalog.Entry> subscriptions,
                Map<String, Object> preferences, long createdAt, String appVersion) {}
    static final class PasswordFailure extends IOException {
        PasswordFailure() { super("密码不正确，或备份文件已损坏。"); }
    }

    static boolean portableKey(String key) {
        return key.equals("node_sort_delay") || key.equals("node_hide_failed")
            || key.equals("auto_subscription_update") || key.equals("routing_categories")
            || key.matches("node_favorites_[0-9a-f]{64}");
    }

    static byte[] read(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = input.read(buffer)) != -1) {
            if (output.size() + count > MAX_BYTES) throw new IOException("备份文件最大为 16 MiB。");
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }

    static boolean encrypted(byte[] bytes) throws IOException {
        if (bytes.length > MAX_BYTES || bytes.length < MAGIC.length + 2
            || !Arrays.equals(MAGIC, Arrays.copyOf(bytes, MAGIC.length))) throw new IOException("请选择 MinClash 备份文件。");
        if (bytes[MAGIC.length] != 1) throw new IOException("此备份版本不受支持，请更新 MinClash。");
        int flag = bytes[MAGIC.length + 1];
        if (flag != 0 && flag != 1) throw new IOException("备份文件头已损坏。");
        return flag == 1;
    }

    static byte[] encode(Data data, char[] password) throws Exception {
        byte[] payload = json(data).toString().getBytes(StandardCharsets.UTF_8);
        if (payload.length > MAX_BYTES - 128) throw new IOException("备份内容超过 16 MiB。");
        boolean encrypted = password.length != 0;
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(MAGIC);
        output.write(1);
        output.write(encrypted ? 1 : 0);
        if (encrypted) {
            byte[] salt = new byte[16];
            byte[] nonce = new byte[12];
            SecureRandom random = new SecureRandom();
            random.nextBytes(salt);
            random.nextBytes(nonce);
            output.write(salt);
            output.write(nonce);
            byte[] key = derive(password, salt);
            try {
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
                cipher.updateAAD(output.toByteArray());
                output.write(cipher.doFinal(payload));
            } finally { Arrays.fill(key, (byte) 0); }
        } else {
            output.write(MessageDigest.getInstance("SHA-256").digest(payload));
            output.write(payload);
        }
        return output.toByteArray();
    }

    static Data decode(byte[] bytes, char[] password) throws Exception {
        boolean locked = encrypted(bytes);
        int offset = MAGIC.length + 2;
        byte[] payload;
        if (locked) {
            if (bytes.length < offset + 16 + 12 + 16) throw new IOException("备份文件不完整。");
            if (password.length == 0) throw new PasswordFailure();
            byte[] salt = Arrays.copyOfRange(bytes, offset, offset + 16);
            byte[] nonce = Arrays.copyOfRange(bytes, offset + 16, offset + 28);
            byte[] key = derive(password, salt);
            try {
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
                cipher.updateAAD(Arrays.copyOf(bytes, offset + 28));
                payload = cipher.doFinal(bytes, offset + 28, bytes.length - offset - 28);
            } catch (AEADBadTagException error) { throw new PasswordFailure(); }
            finally { Arrays.fill(key, (byte) 0); }
        } else {
            if (bytes.length < offset + 32) throw new IOException("备份文件不完整。");
            payload = Arrays.copyOfRange(bytes, offset + 32, bytes.length);
            if (!MessageDigest.isEqual(Arrays.copyOfRange(bytes, offset, offset + 32),
                MessageDigest.getInstance("SHA-256").digest(payload))) throw new IOException("备份校验失败，文件已损坏。");
        }
        try {
            String text = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(payload)).toString();
            checkDepth(text);
            JSONObject object = new JSONObject(text);
            if (!(object.get("version") instanceof Integer version) || version != 1) throw new IOException("备份数据版本不受支持。");
            String source = string(object.get("source"), Profile.MAX_BYTES);
            byte[] normalized = Profile.normalize(source.getBytes(StandardCharsets.UTF_8));
            JSONObject applications = object.getJSONObject("applications");
            if (applications.length() > 20000) throw new IOException();
            Map<String, String> rules = new TreeMap<>();
            for (java.util.Iterator<String> keys = applications.keys(); keys.hasNext();) {
                String name = keys.next();
                rules.put(name, string(applications.get(name), 4096));
            }
            Profile.withAppRules(normalized, rules);
            List<SubscriptionCatalog.Entry> subscriptions = new ArrayList<>();
            Set<String> addresses = new HashSet<>();
            JSONArray entries = object.getJSONArray("subscriptions");
            if (entries.length() > 1000) throw new IOException();
            for (int i = 0; i < entries.length(); i++) {
                JSONObject entry = entries.getJSONObject(i);
                String name = string(entry.get("name"), 256);
                String address = string(entry.get("address"), 8192);
                Subscription.host(address);
                if (name.trim().isEmpty() || !addresses.add(address)) throw new IOException();
                subscriptions.add(new SubscriptionCatalog.Entry(name, address));
            }
            String active = string(object.get("active"), 8192);
            if (!active.isEmpty() && !addresses.contains(active)) throw new IOException();
            Map<String, Object> settings = preferences(object.getJSONObject("preferences"));
            return new Data(new RoutingStore.State(normalized, rules, active), Collections.unmodifiableList(subscriptions),
                Collections.unmodifiableMap(settings), object.getLong("createdAt"), string(object.get("appVersion"), 64));
        } catch (Exception invalid) {
            throw new IOException("备份内容无效、版本不兼容或配置无法使用，未修改现有数据。", invalid);
        } finally { Arrays.fill(payload, (byte) 0); }
    }

    static JSONObject json(Data data) throws Exception {
        JSONObject settings = new JSONObject();
        for (Map.Entry<String, Object> entry : data.preferences().entrySet()) {
            Object value = entry.getValue();
            settings.put(entry.getKey(), value instanceof Set<?> set ? new JSONArray(set) : value);
        }
        JSONArray subscriptions = new JSONArray();
        for (SubscriptionCatalog.Entry entry : data.subscriptions()) {
            subscriptions.put(new JSONObject().put("name", entry.name()).put("address", entry.address()));
        }
        return new JSONObject().put("version", 1).put("createdAt", data.createdAt()).put("appVersion", data.appVersion())
            .put("source", new String(data.state().source(), StandardCharsets.UTF_8))
            .put("applications", new JSONObject(data.state().applications())).put("active", data.state().subscription())
            .put("subscriptions", subscriptions).put("preferences", settings);
    }

    private static Map<String, Object> preferences(JSONObject settings) throws Exception {
        if (settings.length() > 1000) throw new IOException();
        Map<String, Object> result = new TreeMap<>();
        for (java.util.Iterator<String> keys = settings.keys(); keys.hasNext();) {
            String key = keys.next();
            if (!portableKey(key)) throw new IOException();
            Object value = settings.get(key);
            if (key.startsWith("node_favorites_")) {
                if (!(value instanceof JSONArray array) || array.length() > 20000) throw new IOException();
                Set<String> names = new LinkedHashSet<>();
                for (int i = 0; i < array.length(); i++) names.add(string(array.get(i), 4096));
                value = Collections.unmodifiableSet(names);
            } else if (key.equals("routing_categories")) {
                String raw = string(value, MAX_BYTES);
                checkDepth(raw);
                JSONArray categories = new JSONArray(raw);
                if (categories.length() > 500) throw new IOException();
                Set<String> ids = new HashSet<>();
                for (int i = 0; i < categories.length(); i++) {
                    JSONObject category = categories.getJSONObject(i);
                    String id = string(category.get("id"), 256);
                    if (!id.startsWith(AppCategories.CUSTOM_PREFIX) || !ids.add(id)
                        || string(category.get("name"), 256).trim().isEmpty()) throw new IOException();
                    JSONArray members = category.getJSONArray("packages");
                    if (members.length() > 20000) throw new IOException();
                    for (int j = 0; j < members.length(); j++) {
                        if (!string(members.get(j), 256).matches("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)*")) throw new IOException();
                    }
                }
            } else if (!(value instanceof Boolean)) throw new IOException();
            result.put(key, value);
        }
        return result;
    }

    private static String string(Object value, int limit) throws IOException {
        if (!(value instanceof String text) || text.length() > limit) throw new IOException();
        return text;
    }

    private static void checkDepth(String text) throws IOException {
        int depth = 0;
        boolean quoted = false, escaped = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (escaped) escaped = false;
                else if (c == '\\') escaped = true;
                else if (c == '"') quoted = false;
            } else if (c == '"') quoted = true;
            else if (c == '{' || c == '[') { if (++depth > 64) throw new IOException(); }
            else if (c == '}' || c == ']') { if (--depth < 0) throw new IOException(); }
        }
        if (depth != 0 || quoted) throw new IOException();
    }

    private static byte[] derive(char[] password, byte[] salt) throws GeneralSecurityException {
        if (password.length > 256) throw new GeneralSecurityException("备份密码过长。");
        PBEKeySpec spec = new PBEKeySpec(password, salt, ITERATIONS, 256);
        try { return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded(); }
        finally { spec.clearPassword(); }
    }

    private BackupArchive() {}
}

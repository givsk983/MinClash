package io.minclash.app;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

public final class BackupArchiveCheck {
    private static final String SECRET = "private-subscription-token";
    public static void main(String[] args) throws Exception {
        BackupArchive.Data data = sample();
        char[] password = "备份密码-keep-it-private-123".toCharArray();
        byte[] plain = BackupArchive.encode(data, new char[0]);
        require(!BackupArchive.encrypted(plain), "plain archive recognized");
        roundTrip(data, BackupArchive.decode(BackupArchive.read(new ByteArrayInputStream(plain)), new char[0]));
        byte[] encrypted = BackupArchive.encode(data, password);
        require(BackupArchive.encrypted(encrypted), "encrypted archive recognized");
        roundTrip(data, BackupArchive.decode(encrypted, password));
        Map<String, Object> displaySettings = new TreeMap<>(data.preferences());
        displaySettings.put(SubscriptionPreferences.STYLE, SubscriptionPreferences.DUAL);
        displaySettings.put(SubscriptionPreferences.SHOW_PERCENT, false);
        BackupArchive.Data displayData = new BackupArchive.Data(data.state(), data.subscriptions(), displaySettings, 1, "0.2.1");
        roundTrip(displayData, BackupArchive.decode(BackupArchive.encode(displayData, new char[0]), new char[0]));
        displaySettings.put(SubscriptionPreferences.STYLE, "unknown-style");
        rejectData(new BackupArchive.Data(data.state(), data.subscriptions(), displaySettings, 1, "0.2.1"), "unknown display style");
        require(!new String(encrypted, StandardCharsets.ISO_8859_1).contains(SECRET), "encrypted archive does not expose subscription token");
        require(!Arrays.equals(encrypted, BackupArchive.encode(data, password)), "fresh salt and nonce for each export");
        try {
            BackupArchive.decode(encrypted, "incorrect".toCharArray());
            throw new AssertionError("wrong password accepted");
        } catch (BackupArchive.PasswordFailure expected) { }
        byte[] damaged = encrypted.clone();
        damaged[damaged.length - 1] ^= 1;
        final byte[] damagedCipher = damaged;
        reject(() -> BackupArchive.decode(damagedCipher, password), "tampered ciphertext");
        damaged = encrypted.clone();
        damaged[17] ^= 1; // Authenticated salt/header.
        final byte[] damagedHeader = damaged;
        reject(() -> BackupArchive.decode(damagedHeader, password), "tampered encrypted header");
        damaged = plain.clone();
        damaged[damaged.length - 1] ^= 1;
        final byte[] damagedPlain = damaged;
        reject(() -> BackupArchive.decode(damagedPlain, new char[0]), "plain checksum failure");
        reject(() -> BackupArchive.decode(Arrays.copyOf(encrypted, 25), password), "truncated encrypted archive");
        reject(() -> BackupArchive.decode("not a backup".getBytes(StandardCharsets.UTF_8), password), "foreign file");
        byte[] future = plain.clone();
        future["MINCLASH-BACKUP\n".length()] = 2;
        reject(() -> BackupArchive.decode(future, password), "future format version");
        for (String key : List.of("notification_control", "root_app_inventory", "connected", "arbitrary_path",
            SubscriptionPreferences.REMINDERS, SubscriptionPreferences.DAYS, "subscription_reminder_sent_private")) {
            Map<String, Object> settings = new TreeMap<>(data.preferences());
            settings.put(key, true);
            rejectData(new BackupArchive.Data(data.state(), data.subscriptions(), settings, 1, "0.1.7"), "nonportable setting " + key);
        }
        Map<String, Object> settings = new TreeMap<>(data.preferences());
        settings.put("node_sort_delay", "true");
        rejectData(new BackupArchive.Data(data.state(), data.subscriptions(), settings, 1, "0.1.7"), "mistyped boolean");
        settings = new TreeMap<>(data.preferences());
        settings.put("routing_categories", "[{\"id\":\"custom:one\",\"name\":\"bad\",\"packages\":[\"../invalid\"]}]");
        rejectData(new BackupArchive.Data(data.state(), data.subscriptions(), settings, 1, "0.1.7"), "invalid category package");
        rejectData(new BackupArchive.Data(new RoutingStore.State(data.state().source(), Map.of("com.example.app", "missing-group"), data.state().subscription()),
            data.subscriptions(), data.preferences(), 1, "0.1.7"), "missing policy target");
        rejectData(new BackupArchive.Data(new RoutingStore.State(data.state().source(), Map.of(), "https://unknown.example/"),
            data.subscriptions(), data.preferences(), 1, "0.1.7"), "active source absent from catalog");
        rejectData(new BackupArchive.Data(data.state(), List.of(data.subscriptions().get(0), data.subscriptions().get(0)),
            data.preferences(), 1, "0.1.7"), "duplicate subscription");
        rejectData(new BackupArchive.Data(new RoutingStore.State("invalid yaml".getBytes(StandardCharsets.UTF_8), Map.of(), ""),
            List.of(), data.preferences(), 1, "0.1.7"), "invalid source YAML");
        boundedInput();
        rollback();
        Arrays.fill(password, '\0');
        System.out.println("PASS: encrypted/plain backups, Unicode round trips, randomization, wrong passwords, tampering, bounded input, schema validation and rollback failures.");
    }

    private static BackupArchive.Data sample() throws Exception {
        byte[] source = Profile.normalize(("proxies: [{name: 美国节点, type: socks5, server: 127.0.0.1, port: 9}]\n"
            + "proxy-groups: [{name: US, type: select, proxies: [美国节点]}]\nrules: ['MATCH,US']\n").getBytes(StandardCharsets.UTF_8));
        String address = "https://one.example/" + SECRET;
        return new BackupArchive.Data(new RoutingStore.State(source, Map.of("com.example.uninstalled", "US"), address),
            List.of(new SubscriptionCatalog.Entry("订阅一", address), new SubscriptionCatalog.Entry("备用", "https://two.example/config")),
            Map.of("node_sort_delay", true, "node_hide_failed", false, "auto_subscription_update", true,
                NodeList.favoritesKey(address), Set.of("美国节点", "消失的节点"),
                "routing_categories", "[{\"id\":\"custom:one\",\"name\":\"我的分类\",\"packages\":[\"com.example.uninstalled\"]}]"),
            1794528000000L, "0.1.7");
    }

    private static void roundTrip(BackupArchive.Data before, BackupArchive.Data after) throws Exception {
        require(Arrays.equals(Profile.normalize(before.state().source()), after.state().source()), "source YAML survives normalization");
        require(before.state().applications().equals(after.state().applications()), "uninstalled app override survives");
        require(before.state().subscription().equals(after.state().subscription()), "active source survives");
        require(before.subscriptions().equals(after.subscriptions()) && before.preferences().equals(after.preferences()),
            "all subscriptions, categories, favorites and portable preferences survive");
        require(before.createdAt() == after.createdAt() && before.appVersion().equals(after.appVersion()), "preview metadata survives");
    }

    private static void rejectData(BackupArchive.Data data, String reason) throws Exception {
        byte[] bytes = BackupArchive.encode(data, new char[0]);
        reject(() -> BackupArchive.decode(bytes, new char[0]), reason);
    }

    private static void boundedInput() throws Exception {
        InputStream huge = new InputStream() {
            int remaining = BackupArchive.MAX_BYTES + 1;
            @Override public int read() { return remaining-- > 0 ? 0 : -1; }
            @Override public int read(byte[] bytes, int offset, int length) {
                if (remaining == 0) return -1;
                int count = Math.min(length, remaining);
                Arrays.fill(bytes, offset, offset + count, (byte) 0);
                remaining -= count;
                return count;
            }
        };
        reject(() -> BackupArchive.read(huge), "overlarge input stream");
    }

    private static void rollback() throws Exception {
        for (int phase = 0; phase < 2; phase++) {
            int failedPhase = phase;
            String[] state = {"old-settings", "old-config"};
            try {
                BackupStore.transaction(() -> {
                    state[0] = "new-settings";
                    if (failedPhase == 0) throw new IOException("settings disk failure");
                }, () -> {
                    state[1] = "new-config";
                    throw new IOException("profile validation or persistence failure");
                }, () -> { state[0] = "old-settings"; state[1] = "old-config"; });
                throw new AssertionError("restore failure reported as success");
            } catch (IOException expected) {
                require(expected.getMessage().contains("已还原") && state[0].equals("old-settings") && state[1].equals("old-config"),
                    "each failed phase rolls back settings and config together");
            }
        }
        try {
            BackupStore.transaction(() -> {}, () -> { throw new IOException(); }, () -> { throw new IOException(); });
            throw new AssertionError("rollback failure reported as recovered");
        } catch (IOException expected) { require(expected.getMessage().contains("未能还原"), "partial rollback is reported explicitly"); }
        int[] applied = {0};
        BackupStore.transaction(() -> applied[0]++, () -> applied[0]++, () -> { throw new AssertionError("unexpected rollback"); });
        require(applied[0] == 2, "successful transaction applies both phases");
    }

    private static void reject(BackupStore.Step operation, String reason) throws Exception {
        try { operation.run(); throw new AssertionError("accepted " + reason); }
        catch (IOException expected) { require(!String.valueOf(expected.getMessage()).contains(SECRET), "errors omit subscription secrets"); }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}

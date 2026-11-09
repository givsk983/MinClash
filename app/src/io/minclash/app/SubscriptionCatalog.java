package io.minclash.app;

import android.content.SharedPreferences;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

final class SubscriptionCatalog {
    private static final String ENTRIES = "subscriptions";
    private static final String LEGACY_ADDRESS = "subscription";

    record Entry(String name, String address) {}
    record Snapshot(List<Entry> entries, String activeAddress) {
        Entry active() {
            return entries.stream().filter(entry -> entry.address().equals(activeAddress)).findFirst().orElse(null);
        }
    }

    private final SharedPreferences preferences;
    private final RoutingStore routing;

    SubscriptionCatalog(SharedPreferences preferences, RoutingStore routing) {
        this.preferences = preferences;
        this.routing = routing;
    }

    Snapshot load() throws Exception {
        String legacy = preferences.getString(LEGACY_ADDRESS, "");
        String active = routing.savedSubscription(legacy);
        String saved = preferences.getString(ENTRIES, null);
        List<Entry> entries = new ArrayList<>();
        if (saved == null && !legacy.isEmpty()) entries.add(defaultEntry(legacy));
        if (saved != null) {
            JSONArray array = new JSONArray(saved);
            for (int i = 0; i < array.length(); i++) {
                JSONObject entry = array.getJSONObject(i);
                entries.add(new Entry(entry.getString("name"), entry.getString("address")));
            }
        }
        if (!active.isEmpty() && entries.stream().noneMatch(entry -> entry.address().equals(active))) {
            entries.add(defaultEntry(active));
        }
        return new Snapshot(Collections.unmodifiableList(entries), active);
    }

    void save(List<Entry> entries) throws Exception {
        JSONArray array = new JSONArray();
        for (Entry entry : entries) {
            array.put(new JSONObject().put("name", entry.name()).put("address", entry.address()));
        }
        if (!preferences.edit().putString(ENTRIES, array.toString()).commit()) {
            throw new IOException("无法保存订阅列表。");
        }
    }

    private static Entry defaultEntry(String address) {
        try { return new Entry(Subscription.host(address), address); }
        catch (IOException invalid) { return new Entry("已导入的订阅", address); }
    }
}

package io.minclash.app;

import android.content.SharedPreferences;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

final class AppCategories {
    static final String APPS = "apps";
    static final String ASSIGNED = "assigned";
    static final String CUSTOM_PREFIX = "custom:";
    private static final String KEY = "routing_categories";

    record Category(String id, String name, Set<String> members) {
        boolean includes(AppRouting.Entry app) {
            return switch (id) {
                case APPS -> app.userFacing();
                case ASSIGNED -> !app.target().isEmpty();
                case "all" -> true;
                case "system" -> app.system();
                case "google" -> isGoogle(app.packageName());
                default -> members.contains(app.packageName());
            };
        }
    }

    private static final List<Category> BUILT_IN = Arrays.asList(
        builtIn(APPS, "应用"), builtIn(ASSIGNED, "已指定"), builtIn("all", "全部"),
        builtIn("system", "系统"), builtIn("google", "Google"));

    static boolean isGoogle(String name) {
        return name.startsWith("com.google.") || name.equals("com.android.vending") || name.equals("com.android.chrome");
    }

    static List<Category> all(List<Category> custom) {
        List<Category> categories = new ArrayList<>(BUILT_IN);
        categories.addAll(custom);
        return categories;
    }

    private static Category builtIn(String id, String name) {
        return new Category(id, name, Collections.emptySet());
    }

    static List<Category> load(SharedPreferences preferences) {
        List<Category> categories = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        try {
            JSONArray saved = new JSONArray(preferences.getString(KEY, "[]"));
            for (int i = 0; i < saved.length(); i++) {
                JSONObject entry = saved.optJSONObject(i);
                if (entry == null) continue;
                String id = entry.optString("id");
                String name = entry.optString("name").trim();
                JSONArray packages = entry.optJSONArray("packages");
                if (!id.startsWith(CUSTOM_PREFIX) || name.isEmpty() || packages == null || !ids.add(id)) continue;
                Set<String> members = new LinkedHashSet<>();
                for (int j = 0; j < packages.length(); j++) {
                    String member = packages.optString(j);
                    if (member.matches("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)*")) members.add(member);
                }
                categories.add(new Category(id, name, members));
            }
        } catch (JSONException ignored) { }
        return categories;
    }

    static void save(SharedPreferences preferences, List<Category> categories) throws JSONException {
        JSONArray saved = new JSONArray();
        for (Category category : categories) {
            saved.put(new JSONObject().put("id", category.id()).put("name", category.name())
                .put("packages", new JSONArray(category.members())));
        }
        preferences.edit().putString(KEY, saved.toString()).apply();
    }

    private AppCategories() {}
}

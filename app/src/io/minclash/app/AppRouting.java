package io.minclash.app;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.SystemClock;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.HashSet;
import java.util.Set;
import java.io.IOException;
import org.json.JSONArray;
import org.json.JSONObject;

final class AppRouting {
    record Entry(String label, String packageName, String target, boolean installed, boolean system,
                 boolean launchable) {
        boolean userFacing() { return !system || launchable || !target.isEmpty(); }
    }

    record Screen(RoutingStore.State state, List<Entry> applications, List<String> targets,
                  Module.Status status, boolean rootInventory, boolean systemInventoryEmpty, boolean allowRoot) {}

    /** Cache inventory only: every open still reads the current subscription and personal policies. */
    static final class Loader {
        private record Inventory(List<Entry> entries, boolean root, boolean empty, boolean allowRoot,
                                 long created, int generation) {}
        private Inventory cached;
        private volatile int generation;

        void invalidate() { generation++; }

        Screen load(Context context, boolean allowRoot, Module.Status status) throws Exception {
            RoutingStore.State state = new RoutingStore(context).load();
            int current = generation;
            Inventory inventory = cached;
            if (inventory == null || inventory.generation() != current || inventory.allowRoot() != allowRoot
                || SystemClock.elapsedRealtime() - inventory.created() >= 60000) {
                inventory = readInventory(context, allowRoot, current);
                cached = inventory;
            }
            Map<String, Entry> entries = new TreeMap<>();
            for (Entry app : inventory.entries()) {
                entries.put(app.packageName(), new Entry(app.label(), app.packageName(),
                    state.applications().getOrDefault(app.packageName(), ""), true, app.system(), app.launchable()));
            }
            for (Map.Entry<String, String> rule : state.applications().entrySet()) {
                entries.putIfAbsent(rule.getKey(), new Entry(rule.getKey(), rule.getKey(), rule.getValue(), false,
                    false, false));
            }
            List<Entry> applications = new ArrayList<>(entries.values());
            Collator collator = Collator.getInstance();
            applications.sort(Comparator.comparing((Entry app) -> app.target().isEmpty())
                .thenComparing(Entry::label, collator).thenComparing(Entry::packageName));
            return new Screen(state, applications, Profile.routingTargets(state.source()),
                status == null ? Module.status() : status, inventory.root(), inventory.empty(), allowRoot);
        }

        private Inventory readInventory(Context context, boolean allowRoot, int current) throws Exception {
            PackageManager manager = context.getPackageManager();
            List<Entry> entries = new ArrayList<>();
            Set<String> launchers = new HashSet<>();
            try {
                for (ResolveInfo info : manager.queryIntentActivities(new Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_LAUNCHER), 0)) {
                    if (info.activityInfo != null) launchers.add(info.activityInfo.packageName);
                }
            } catch (SecurityException ignored) { }
            List<ApplicationInfo> installed;
            try { installed = manager.getInstalledApplications(0); }
            catch (SecurityException denied) { installed = new ArrayList<>(); }
            for (ApplicationInfo app : installed) {
                if (app.packageName.equals(context.getPackageName())) continue;
                String label = app.packageName;
                try { label = manager.getApplicationLabel(app).toString(); }
                catch (RuntimeException ignored) { }
                entries.add(new Entry(label, app.packageName, "", true,
                    (app.flags & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0,
                    launchers.contains(app.packageName)));
            }
            boolean empty = entries.isEmpty();
            boolean root = allowRoot && empty;
            if (root) {
                JSONArray apps = rootQuery(context, java.util.Collections.emptyList());
                for (int i = 0; i < apps.length(); i++) {
                    JSONObject app = apps.getJSONObject(i);
                    String name = app.getString("package");
                    if (!name.equals(context.getPackageName())) entries.add(new Entry(app.getString("label"), name,
                        "", true, app.optBoolean("system"), app.optBoolean("launchable")));
                }
                if (entries.isEmpty()) throw new IOException("系统和 Root 查询均未返回可选应用，请检查应用列表访问限制。");
            }
            return new Inventory(List.copyOf(entries), root, empty, allowRoot, SystemClock.elapsedRealtime(), current);
        }
    }

    /** Empty packages requests metadata; otherwise only these packages' icons are encoded. */
    static JSONArray rootQuery(Context context, List<String> packages) throws Exception {
        String apk = context.getApplicationInfo().sourceDir;
        String command = "CLASSPATH='" + apk.replace("'", "'\\''")
            + "' /system/bin/app_process /system/bin io.minclash.app.RootApps "
            + android.os.Process.myUid() / 100000;
        if (!packages.isEmpty()) {
            command += " icons";
            for (String name : packages) {
                if (!name.matches("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)*")) throw new IOException("无效的应用包名。");
                command += " " + name;
            }
        }
        String output = Module.runRoot(command, Module.Command.API, null, 30);
        int start = output.lastIndexOf(RootApps.MARKER);
        if (start < 0) throw new IOException("Root 读取未成功，请检查 MinClash 的 Root 授权。");
        return new JSONArray(output.substring(start + RootApps.MARKER.length()).trim());
    }

    private AppRouting() {}
}

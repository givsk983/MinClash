package io.minclash.app;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Base64;
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
                 boolean launchable, Bitmap icon, String iconIssue) {
        boolean userFacing() { return !system || launchable || !target.isEmpty(); }
    }

    record Screen(RoutingStore.State state, List<Entry> applications, List<String> targets,
                  Module.Status status, boolean rootInventory, boolean systemInventoryEmpty) {
        long missingIcons() { return applications.stream().filter(app -> app.installed() && app.icon() == null).count(); }
        boolean iconPermissionDenied() { return applications.stream().anyMatch(app -> app.iconIssue().equals("permission")); }
    }

    static Screen load(Context context, boolean allowRoot) throws Exception {
        RoutingStore.State state = new RoutingStore(context).load();
        PackageManager manager = context.getPackageManager();
        Map<String, Entry> entries = new TreeMap<>();
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
            Bitmap icon = null;
            String issue = "";
            try { icon = AppIcons.render(manager.getApplicationIcon(app), 64); }
            catch (SecurityException denied) { issue = "permission"; }
            catch (RuntimeException unavailable) { issue = "unavailable"; }
            entries.put(app.packageName, new Entry(manager.getApplicationLabel(app).toString(), app.packageName,
                state.applications().getOrDefault(app.packageName, ""), true,
                (app.flags & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0,
                launchers.contains(app.packageName), icon, issue));
        }
        boolean systemInventoryEmpty = entries.isEmpty();
        boolean rootInventory = allowRoot && (systemInventoryEmpty || entries.values().stream().anyMatch(app -> app.icon() == null));
        if (rootInventory) {
            String apk = context.getApplicationInfo().sourceDir;
            String command = "CLASSPATH='" + apk.replace("'", "'\\''")
                + "' /system/bin/app_process /system/bin io.minclash.app.RootApps "
                + android.os.Process.myUid() / 100000;
            String output = Module.runRoot(command, Module.Command.API, null, 30);
            int start = output.lastIndexOf(RootApps.MARKER);
            if (start < 0) throw new IOException("系统未返回应用列表，Root 读取也未成功。请检查 MinClash 的 Root 授权。");
            JSONArray apps = new JSONArray(output.substring(start + RootApps.MARKER.length()).trim());
            for (int i = 0; i < apps.length(); i++) {
                JSONObject app = apps.getJSONObject(i);
                String name = app.getString("package");
                if (name.equals(context.getPackageName())) continue;
                Bitmap icon = null;
                String issue = app.optString("iconIssue");
                String encoded = app.optString("icon");
                if (!encoded.isEmpty()) {
                    try {
                        byte[] png = Base64.decode(encoded, Base64.DEFAULT);
                        icon = BitmapFactory.decodeByteArray(png, 0, png.length);
                    } catch (IllegalArgumentException ignored) { }
                }
                if (icon == null && issue.isEmpty()) issue = "unavailable";
                entries.put(name, new Entry(app.getString("label"), name,
                    state.applications().getOrDefault(name, ""), true,
                    app.optBoolean("system"), app.optBoolean("launchable"), icon, issue));
            }
            if (entries.isEmpty()) throw new IOException("系统和 Root 查询均未返回可选应用，请检查应用列表访问限制。");
        }
        for (Map.Entry<String, String> rule : state.applications().entrySet()) {
            entries.putIfAbsent(rule.getKey(), new Entry(rule.getKey(), rule.getKey(), rule.getValue(), false,
                false, false, null, "not-found"));
        }
        List<Entry> applications = new ArrayList<>(entries.values());
        Collator collator = Collator.getInstance();
        applications.sort(Comparator.comparing((Entry app) -> app.target().isEmpty())
            .thenComparing(Entry::label, collator).thenComparing(Entry::packageName));
        return new Screen(state, applications, Profile.routingTargets(state.source()), Module.status(), rootInventory, systemInventoryEmpty);
    }

    private AppRouting() {}
}

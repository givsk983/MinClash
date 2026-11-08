package io.minclash.app;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Looper;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;
import java.util.HashSet;
import org.json.JSONArray;
import org.json.JSONObject;

/** Read-only package inventory helper, run with the app's existing Root authorization. */
public final class RootApps {
    static final String MARKER = "MINCLASH_APPS_V1=";

    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {
        int user = Integer.parseInt(args[0]);
        if (user < 0) throw new IllegalArgumentException("Invalid Android user");
        Looper.prepareMainLooper();
        Class<?> threadClass = Class.forName("android.app.ActivityThread");
        Object thread = threadClass.getMethod("systemMain").invoke(null);
        Context context = (Context) threadClass.getMethod("getSystemContext").invoke(thread);
        PackageManager manager = context.getPackageManager();
        Method query = manager.getClass().getMethod("getInstalledApplicationsAsUser", int.class, int.class);
        List<ApplicationInfo> apps = (List<ApplicationInfo>) query.invoke(manager, 0, user);
        Method launcherQuery = manager.getClass().getMethod("queryIntentActivitiesAsUser", Intent.class, int.class, int.class);
        List<ResolveInfo> activities = (List<ResolveInfo>) launcherQuery.invoke(manager,
            new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0, user);
        Set<String> launchers = new HashSet<>();
        for (ResolveInfo info : activities) if (info.activityInfo != null) launchers.add(info.activityInfo.packageName);
        JSONArray result = new JSONArray();
        int iconBytes = 0;
        for (ApplicationInfo app : apps) {
            String label = app.packageName;
            try { label = manager.getApplicationLabel(app).toString(); }
            catch (RuntimeException ignored) { /* Keep the package selectable if its resources are unavailable. */ }
            JSONObject item = new JSONObject();
            item.put("package", app.packageName);
            item.put("label", label);
            item.put("system", (app.flags & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0);
            item.put("launchable", launchers.contains(app.packageName));
            try {
                String icon = AppIcons.encode(manager.getApplicationIcon(app));
                if (iconBytes + icon.length() <= 4 * 1024 * 1024) {
                    item.put("icon", icon);
                    iconBytes += icon.length();
                } else item.put("iconIssue", "limit");
            } catch (SecurityException denied) { item.put("iconIssue", "permission"); }
            catch (RuntimeException unavailable) { item.put("iconIssue", "unavailable"); }
            result.put(item);
        }
        System.out.println(MARKER + result);
    }

    private RootApps() {}
}

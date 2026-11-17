package io.minclash.app;

import android.content.Context;
import android.os.SystemClock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** One batched package/UID scan, reused between status refreshes. Never caches root policy checks. */
final class ProtectionInventory {
    record App(String name, String packageName, int uid) {}
    private List<App> cached;
    private long created;
    private volatile int generation;
    private int cachedGeneration;

    void invalidate() { generation++; }

    List<App> load(Context context) throws Exception {
        int current = generation;
        if (cached != null && cachedGeneration == current && SystemClock.elapsedRealtime() - created < 60_000) return cached;
        JSONArray apps = AppRouting.rootQuery(context, List.of());
        List<App> result = new ArrayList<>();
        for (int i = 0; i < apps.length(); i++) {
            JSONObject app = apps.getJSONObject(i);
            int uid = app.getInt("uid");
            String name = app.getString("package");
            if (uid % 100000 >= 10000 && uid % 100000 < 20000 && !name.equals(context.getPackageName())) {
                result.add(new App(app.getString("label"), name, uid));
            }
        }
        result.sort(Comparator.comparing(App::name).thenComparing(App::packageName));
        cached = List.copyOf(result);
        cachedGeneration = current;
        created = SystemClock.elapsedRealtime();
        return cached;
    }
}

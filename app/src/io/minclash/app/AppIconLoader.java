package io.minclash.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.LruCache;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONArray;
import org.json.JSONObject;

/** Main-thread cache with a separate worker; visible rows share batched Root requests. */
final class AppIconLoader {
    record Result(Bitmap bitmap, String issue, boolean root) {}
    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final LruCache<String, Result> cache = new LruCache<>(4 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Result value) {
            return value.bitmap() == null ? 256 : value.bitmap().getAllocationByteCount();
        }
    };
    private final Map<String, Boolean> pending = new LinkedHashMap<>();
    private final Set<String> inFlight = new HashSet<>();
    private final Runnable dispatch = this::dispatch;
    private Runnable changed;
    private boolean running;
    private boolean closed;
    private int generation;
    private int runningGeneration;

    AppIconLoader(Context context) { this.context = context.getApplicationContext(); }

    Result get(String name) { return cache.get(name); }

    void observe(Runnable callback) { changed = callback; }

    void stopObserving() {
        changed = null;
        pending.clear();
        main.removeCallbacks(dispatch);
    }

    void invalidate() {
        generation++;
        cache.evictAll();
        pending.clear();
    }

    void request(String name, boolean allowRoot) {
        if (closed || cache.get(name) != null || (runningGeneration == generation && inFlight.contains(name))) return;
        pending.put(name, allowRoot);
        if (!running) {
            main.removeCallbacks(dispatch);
            main.postDelayed(dispatch, 24);
        }
    }

    private void dispatch() {
        if (closed || running || pending.isEmpty()) return;
        Map<String, Boolean> batch = new LinkedHashMap<>();
        for (String name : new ArrayList<>(pending.keySet())) {
            batch.put(name, pending.remove(name));
            if (batch.size() == 12) break;
        }
        int current = generation;
        runningGeneration = current;
        inFlight.addAll(batch.keySet());
        running = true;
        worker.execute(() -> {
            Map<String, Result> results = new LinkedHashMap<>();
            List<String> root = new ArrayList<>();
            for (Map.Entry<String, Boolean> entry : batch.entrySet()) {
                String name = entry.getKey();
                Result result;
                try { result = new Result(AppIcons.render(context.getPackageManager().getApplicationIcon(name), 64), "", false); }
                catch (SecurityException denied) { result = new Result(null, "permission", false); }
                catch (Exception unavailable) { result = new Result(null, "unavailable", false); }
                results.put(name, result);
                if (result.bitmap() == null && entry.getValue()) root.add(name);
            }
            if (!root.isEmpty()) {
                try {
                    JSONArray icons = AppRouting.rootQuery(context, root);
                    for (int i = 0; i < icons.length(); i++) {
                        JSONObject item = icons.getJSONObject(i);
                        String name = item.getString("package");
                        if (!root.contains(name)) continue;
                        String encoded = item.optString("icon");
                        Bitmap bitmap = null;
                        if (!encoded.isEmpty()) {
                            byte[] png = Base64.decode(encoded, Base64.DEFAULT);
                            bitmap = BitmapFactory.decodeByteArray(png, 0, png.length);
                        }
                        String issue = bitmap == null ? item.optString("iconIssue", "unavailable") : "";
                        if (bitmap == null && results.get(name).issue().equals("permission")) issue = "permission";
                        results.put(name, new Result(bitmap, issue, true));
                    }
                } catch (Exception ignored) { /* Keep local failures visible and the list usable. */ }
            }
            main.post(() -> {
                running = false;
                inFlight.clear();
                if (closed) return;
                if (current == generation) results.forEach(cache::put);
                if (changed != null) changed.run();
                if (!pending.isEmpty()) main.post(dispatch);
            });
        });
    }

    void close() {
        closed = true;
        stopObserving();
        cache.evictAll();
        worker.shutdown();
    }
}

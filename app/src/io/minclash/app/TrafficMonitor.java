package io.minclash.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONArray;
import org.json.JSONObject;

/** Shares recent core traffic samples between the two foreground views. */
final class TrafficMonitor {
    static final TrafficMonitor INSTANCE = new TrafficMonitor();
    static final long HISTORY_MS = 600_000;
    static final long MAX_SAMPLE_GAP_MS = 10_000;
    private static final long POLL_MS = 2_000;
    private static final long APP_POLL_MS = 8_000;
    private static final long FIRST_RATE_MS = 750;
    private static final long SAVE_MS = 30_000;

    record Reading(long time, String session, long uploaded, long downloaded, int connections, long memory,
                   Map<Integer, DailyTraffic.Totals> appCounters) {}
    record Rate(long time, long upload, long download) {}
    interface Observer {
        void onTraffic(Reading reading, Rate rate);
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Runnable poll = this::sample;
    private final List<Rate> history = new ArrayList<>();
    private Observer observer;
    private boolean details;
    private boolean pending;
    private boolean ready;
    private boolean summary;
    private boolean appTrafficSupported;
    private int revision;
    private Reading previous;
    private Rate latest;
    private List<DailyTraffic.Day> daily = Collections.emptyList();
    private List<AppDailyTraffic.Day> appDaily = Collections.emptyList();
    // The traffic worker owns the persisted baseline and checkpoints.
    private SharedPreferences historyPreferences;
    private DailyTraffic dailyTraffic;
    private AppDailyTraffic appDailyTraffic;
    private long lastSave;
    private long lastAppSample = -APP_POLL_MS;

    private TrafficMonitor() {}

    void observe(Context context, Observer observer, boolean details) {
        if (this.observer == observer) return;
        this.observer = observer;
        this.details = details;
        Context application = context.getApplicationContext();
        worker.execute(() -> {
            if (dailyTraffic != null) return;
            historyPreferences = application.getSharedPreferences("daily_traffic", Context.MODE_PRIVATE);
            dailyTraffic = new DailyTraffic(historyPreferences.getString("samples", "[]"));
            appDailyTraffic = new AppDailyTraffic(historyPreferences.getString("app_samples", "[]"));
        });
        long now = SystemClock.elapsedRealtime();
        prune(now);
        if (ready && latest != null && now - latest.time() < 2 * interval()) observer.onTraffic(previous, latest);
        else observer.onTraffic(null, null);
        schedule(ready ? nextDelay(details && (previous == null || previous.connections() < 0), now) : 0);
    }

    void stopObserving(Observer observer) {
        if (this.observer != observer) return;
        this.observer = null;
        main.removeCallbacks(poll);
        worker.execute(this::saveDailyTraffic);
    }

    List<Rate> history() { return history; }
    List<DailyTraffic.Day> daily() { return daily; }
    List<AppDailyTraffic.Day> appDaily() { return appDaily; }
    boolean supportsAppTraffic() { return appTrafficSupported; }

    void updateStatus(Module.Status status) {
        boolean nextReady = status != null && status.running() && status.control;
        boolean nextSummary = status != null && status.runtime.trafficSummary();
        boolean nextApps = status != null && status.runtime.appTraffic();
        if (ready == nextReady && summary == nextSummary && appTrafficSupported == nextApps) return;
        ready = nextReady;
        summary = nextSummary;
        appTrafficSupported = nextApps;
        revision++;
        if (!ready && observer != null) publish(null);
        schedule(0);
    }

    private long interval() { return summary ? POLL_MS : 2 * POLL_MS; }

    private long nextDelay(boolean needsDetails, long now) {
        if (previous == null) return 0;
        boolean needsRate = latest != null && latest.upload() < 0;
        long delay = summary && (needsRate || needsDetails) ? FIRST_RATE_MS : interval();
        return Math.max(0, delay - (now - previous.time()));
    }

    private void schedule(long delay) {
        main.removeCallbacks(poll);
        if (observer != null && (ready || details)) main.postDelayed(poll, delay);
    }

    static String formatRate(long rate) {
        return rate < 0 ? "—" : SubscriptionUsage.bytes(rate) + "/s";
    }

    private void sample() {
        if (observer == null || pending || !ready && !details) return;
        pending = true;
        boolean includeMemory = details;
        boolean useSummary = summary;
        boolean useAppCounters = appTrafficSupported;
        boolean canSample = ready;
        int started = revision;
        worker.execute(() -> {
            long elapsed = SystemClock.elapsedRealtime();
            boolean includeApps = canSample && useAppCounters && elapsed - lastAppSample >= APP_POLL_MS;
            if (includeApps) lastAppSample = elapsed;
            Reading reading = canSample ? read(useSummary, includeMemory, includeApps) : null;
            Module.Status checked = includeMemory && reading == null ? checkStatus() : null;
            List<DailyTraffic.Day> recent = null;
            List<AppDailyTraffic.Day> recentApps = null;
            if (reading != null || includeMemory) {
                long now = System.currentTimeMillis();
                ZoneId zone = ZoneId.systemDefault();
                if (reading != null) {
                    dailyTraffic.record(reading.session(), now, zone, reading.uploaded(), reading.downloaded());
                    if (reading.appCounters() != null) appDailyTraffic.record(reading.session(), now, zone, reading.appCounters());
                }
                if (includeMemory) {
                    recent = dailyTraffic.recent(now, zone);
                    recentApps = appDailyTraffic.recent(now, zone);
                }
            }
            if (SystemClock.elapsedRealtime() - lastSave >= SAVE_MS) saveDailyTraffic();
            List<DailyTraffic.Day> dailySnapshot = recent;
            List<AppDailyTraffic.Day> appSnapshot = recentApps;
            main.post(() -> {
                pending = false;
                if (observer == null) return;
                if (started != revision) {
                    schedule(0);
                    return;
                }
                if (checked != null) updateStatus(checked);
                if (dailySnapshot != null) daily = dailySnapshot;
                if (appSnapshot != null) appDaily = appSnapshot;
                publish(reading);
                if (!ready) schedule(10_000);
                else if (!canSample) schedule(0);
                else if (reading == null) schedule(interval());
                else schedule(nextDelay(details && !includeMemory, SystemClock.elapsedRealtime()));
            });
        });
    }

    private void saveDailyTraffic() {
        if (dailyTraffic == null) return;
        String state = dailyTraffic.checkpoint();
        String appState = appDailyTraffic.checkpoint();
        if (state != null || appState != null) {
            SharedPreferences.Editor edit = historyPreferences.edit();
            if (state != null) edit.putString("samples", state);
            if (appState != null) edit.putString("app_samples", appState);
            edit.apply();
        }
        lastSave = SystemClock.elapsedRealtime();
    }

    private static Module.Status checkStatus() {
        try { return Module.status(); }
        catch (Exception unavailable) { return null; }
    }

    private static Reading read(boolean summary, boolean details, boolean apps) {
        try {
            JSONObject data = summary ? Controller.trafficStatistics(details, apps) : Controller.connections();
            long time = SystemClock.elapsedRealtime();
            long up = ConnectionSnapshot.number(data, "uploadTotal");
            long down = ConnectionSnapshot.number(data, "downloadTotal");
            String session = summary ? data.getString("session") : "";
            if (up < 0 || down < 0 || summary && session.isEmpty()) return null;
            int count = details ? data.optInt("connections", -1) : -1;
            long memory = details ? ConnectionSnapshot.number(data, "memory") : -1;
            if (details && !summary) {
                JSONArray connections = data.optJSONArray("connections");
                count = connections == null ? 0 : connections.length();
                try { memory = Controller.coreMemory(); }
                catch (Exception ignored) { }
            }
            return new Reading(time, session, up, down, count, memory,
                apps ? appCounters(data.optJSONArray("apps")) : null);
        } catch (Exception unavailable) {
            return null;
        }
    }

    private static Map<Integer, DailyTraffic.Totals> appCounters(JSONArray entries) {
        if (entries == null || entries.length() > AppDailyTraffic.MAX_UIDS + 1) return null;
        Map<Integer, DailyTraffic.Totals> counters = new HashMap<>();
        for (int i = 0; i < entries.length(); i++) {
            JSONObject entry = entries.optJSONObject(i);
            if (entry == null) return null;
            boolean unknown = "-1".equals(entry.optString("uid"));
            long uid = ConnectionSnapshot.number(entry, "uid");
            long up = ConnectionSnapshot.number(entry, "uploadTotal");
            long down = ConnectionSnapshot.number(entry, "downloadTotal");
            if ((!unknown && (uid < 0 || uid > Integer.MAX_VALUE)) || up < 0 || down < 0) return null;
            int key = unknown ? AppDailyTraffic.UNKNOWN_UID : (int) uid;
            if (counters.put(key, new DailyTraffic.Totals(up, down)) != null) return null;
        }
        return counters;
    }

    private void publish(Reading reading) {
        prune(SystemClock.elapsedRealtime());
        if (reading == null) {
            latest = null;
        } else {
            long up = -1;
            long down = -1;
            if (previous != null) {
                long interval = reading.time() - previous.time();
                if (!reading.session().equals(previous.session())
                    || reading.uploaded() < previous.uploaded() || reading.downloaded() < previous.downloaded()) {
                    history.clear();
                } else if (latest != null && interval > 0 && interval < MAX_SAMPLE_GAP_MS) {
                    up = bytesPerSecond(reading.uploaded() - previous.uploaded(), interval);
                    down = bytesPerSecond(reading.downloaded() - previous.downloaded(), interval);
                }
            }
            latest = new Rate(reading.time(), up, down);
            history.add(latest);
            previous = reading;
        }
        observer.onTraffic(reading, latest);
    }

    private void prune(long now) {
        int expired = 0;
        while (expired < history.size() && now - history.get(expired).time() > HISTORY_MS) expired++;
        history.subList(0, expired).clear();
    }

    private static long bytesPerSecond(long bytes, long interval) {
        return (long) (bytes * 1_000.0 / interval);
    }
}

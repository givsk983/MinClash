package io.minclash.app;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import org.json.JSONArray;
import org.json.JSONException;

final class DailyTraffic {
    static final int DAYS = 7;
    private static final long MIDNIGHT_SAMPLE_GAP = 10_000;

    record Totals(long upload, long download) {}
    record Day(LocalDate date, Totals totals) {}
    private record Sample(String session, long time, long day, long upload, long download) {}

    private final TreeMap<Long, Totals> totals = new TreeMap<>();
    private Sample previous;
    private boolean dirty;

    DailyTraffic(String saved) {
        try {
            JSONArray state = new JSONArray(saved);
            JSONArray sample = state.optJSONArray(0);
            if (sample != null && sample.length() == 5) {
                previous = new Sample(sample.getString(0), sample.getLong(1), sample.getLong(2),
                    sample.getLong(3), sample.getLong(4));
            }
            JSONArray days = state.getJSONArray(1);
            for (int i = 0; i < Math.min(days.length(), DAYS); i++) {
                JSONArray day = days.getJSONArray(i);
                totals.put(day.getLong(0), new Totals(day.getLong(1), day.getLong(2)));
            }
        } catch (JSONException invalid) {
            previous = null;
            totals.clear();
        }
    }

    void record(String session, long time, ZoneId zone, long upload, long download) {
        if (session.isEmpty() || upload < 0 || download < 0) return;
        long today = date(time, zone).toEpochDay();
        totals.headMap(today - DAYS + 1, false).clear();
        totals.tailMap(today, false).clear();
        if (previous != null && previous.session().equals(session) && time >= previous.time()
            && upload >= previous.upload() && download >= previous.download()) {
            boolean sameDay = previous.day() == today;
            boolean nearMidnight = previous.day() == today - 1 && time - previous.time() < MIDNIGHT_SAMPLE_GAP
                && date(previous.time(), zone).toEpochDay() == previous.day();
            if (sameDay || nearMidnight) {
                Totals current = totals.getOrDefault(today, new Totals(0, 0));
                totals.put(today, new Totals(add(current.upload(), upload - previous.upload()),
                    add(current.download(), download - previous.download())));
            }
        }
        previous = new Sample(session, time, today, upload, download);
        dirty = true;
    }

    private static long add(long current, long delta) {
        return current > Long.MAX_VALUE - delta ? Long.MAX_VALUE : current + delta;
    }

    private static LocalDate date(long time, ZoneId zone) {
        return Instant.ofEpochMilli(time).atZone(zone).toLocalDate();
    }

    List<Day> recent(long now, ZoneId zone) {
        long today = date(now, zone).toEpochDay();
        List<Day> result = new ArrayList<>(DAYS);
        for (long day = today; day > today - DAYS; day--) {
            result.add(new Day(LocalDate.ofEpochDay(day), totals.get(day)));
        }
        return result;
    }

    String checkpoint() {
        if (!dirty) return null;
        dirty = false;
        JSONArray sample = new JSONArray().put(previous.session()).put(previous.time()).put(previous.day())
            .put(previous.upload()).put(previous.download());
        JSONArray days = new JSONArray();
        totals.forEach((day, count) -> days.put(new JSONArray().put(day).put(count.upload()).put(count.download())));
        return new JSONArray().put(sample).put(days).toString();
    }
}

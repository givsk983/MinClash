package io.minclash.app;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.json.JSONArray;
import org.json.JSONException;

final class AppDailyTraffic {
    static final int UNKNOWN_UID = -1;
    static final int MAX_UIDS = 512;
    private static final int MAX_SESSION_LENGTH = 128;
    private static final int MAX_STATE_LENGTH = 256 * 1024;
    private static final long MIDNIGHT_SAMPLE_GAP = 10_000;
    private static final int TIME_ZONE_SHIFT_DAYS = 2;
    private static final DailyTraffic.Totals ZERO = new DailyTraffic.Totals(0, 0);

    record Day(LocalDate date, Map<Integer, DailyTraffic.Totals> totals) {}
    private record Sample(String session, long time, long day, Map<Integer, DailyTraffic.Totals> counters) {}

    private final TreeMap<Long, TreeMap<Integer, DailyTraffic.Totals>> history = new TreeMap<>();
    private Sample previous;
    private boolean dirty;

    AppDailyTraffic(String saved) {
        if (saved == null || saved.length() > MAX_STATE_LENGTH) return;
        JSONArray state;
        try {
            state = new JSONArray(saved);
        } catch (JSONException invalid) {
            return;
        }
        previous = readSample(state.optJSONArray(0));
        JSONArray days = state.optJSONArray(1);
        if (days == null || days.length() > DailyTraffic.DAYS) return;
        for (int i = 0; i < days.length(); i++) {
            try {
                JSONArray entry = days.getJSONArray(i);
                long day = integer(entry, 0);
                if (entry.length() == 2 && validDay(day)) {
                    history.putIfAbsent(day, readCounters(entry.getJSONArray(1)));
                }
            } catch (JSONException invalid) {
                // History and the sampling baseline can recover independently.
            }
        }
    }

    void record(String session, long time, ZoneId zone, Map<Integer, DailyTraffic.Totals> counters) {
        if (!validSession(session) || time < 0 || !validCounters(counters)) return;
        long today = day(time, zone);
        TreeMap<Integer, DailyTraffic.Totals> snapshot = new TreeMap<>(counters);
        boolean sameSession = previous != null && previous.session().equals(session);
        boolean countersChanged = sameSession && !snapshot.equals(previous.counters());
        if (sameSession) {
            // A complete snapshot retains every bucket already seen in this core session.
            if (time <= previous.time() || !snapshot.keySet().containsAll(previous.counters().keySet())) return;
            if (countersChanged && day(previous.time(), zone) == previous.day()
                && (previous.day() == today || previous.day() == today - 1
                    && time - previous.time() < MIDNIGHT_SAMPLE_GAP)) {
                snapshot.forEach((uid, counter) -> {
                    DailyTraffic.Totals baseline = previous.counters().getOrDefault(uid, ZERO);
                    if (counter.upload() < baseline.upload() || counter.download() < baseline.download()) return;
                    long upload = counter.upload() - baseline.upload();
                    long download = counter.download() - baseline.download();
                    if (upload != 0 || download != 0) addTraffic(today, uid, new DailyTraffic.Totals(upload, download));
                });
            }
        }
        int daysBeforeTrim = history.size();
        history.headMap(today - DailyTraffic.DAYS + 1, false).clear();
        // Previously recorded days can briefly be ahead of the phone after a time zone change.
        history.tailMap(today + TIME_ZONE_SHIFT_DAYS, false).clear();
        while (history.size() > DailyTraffic.DAYS) history.pollFirstEntry();
        // Idle samples still advance the in-memory timestamp for midnight accounting.
        dirty |= !sameSession || countersChanged || previous.day() != today || history.size() != daysBeforeTrim;
        previous = new Sample(session, time, today, snapshot);
    }

    List<Day> recent(long now, ZoneId zone) {
        long today = day(now, zone);
        List<Day> result = new ArrayList<>(DailyTraffic.DAYS);
        for (long day = today; day > today - DailyTraffic.DAYS; day--) {
            Map<Integer, DailyTraffic.Totals> counts = history.get(day);
            result.add(new Day(LocalDate.ofEpochDay(day), counts == null ? Collections.emptyMap()
                : Collections.unmodifiableMap(new TreeMap<>(counts))));
        }
        return Collections.unmodifiableList(result);
    }

    String checkpoint() {
        if (!dirty) return null;
        dirty = false;
        JSONArray sample = new JSONArray().put(previous.session()).put(previous.time()).put(previous.day())
            .put(writeCounters(previous.counters()));
        JSONArray days = new JSONArray();
        history.forEach((day, counts) -> days.put(new JSONArray().put(day).put(writeCounters(counts))));
        return new JSONArray().put(sample).put(days).toString();
    }

    private void addTraffic(long day, int uid, DailyTraffic.Totals delta) {
        TreeMap<Integer, DailyTraffic.Totals> counts = history.computeIfAbsent(day, value -> new TreeMap<>());
        int known = counts.size() - (counts.containsKey(UNKNOWN_UID) ? 1 : 0);
        if (uid != UNKNOWN_UID && known == MAX_UIDS && !counts.containsKey(uid)) uid = UNKNOWN_UID;
        counts.merge(uid, delta, (current, increment) -> new DailyTraffic.Totals(
            add(current.upload(), increment.upload()), add(current.download(), increment.download())));
    }

    private static long add(long current, long delta) {
        return current > Long.MAX_VALUE - delta ? Long.MAX_VALUE : current + delta;
    }

    private static long day(long time, ZoneId zone) {
        return Instant.ofEpochMilli(time).atZone(zone).toLocalDate().toEpochDay();
    }

    private static boolean validDay(long day) {
        return day >= LocalDate.MIN.toEpochDay() && day <= LocalDate.MAX.toEpochDay();
    }

    private static boolean validSession(String session) {
        return session != null && !session.isEmpty() && session.length() <= MAX_SESSION_LENGTH;
    }

    private static boolean validCounters(Map<Integer, DailyTraffic.Totals> counters) {
        if (counters == null || counters.size() > MAX_UIDS + 1
            || counters.size() == MAX_UIDS + 1 && !counters.containsKey(UNKNOWN_UID)) return false;
        for (Map.Entry<Integer, DailyTraffic.Totals> entry : counters.entrySet()) {
            Integer uid = entry.getKey();
            DailyTraffic.Totals count = entry.getValue();
            if (uid == null || uid < UNKNOWN_UID || count == null || count.upload() < 0 || count.download() < 0) return false;
        }
        return true;
    }

    private static Sample readSample(JSONArray row) {
        if (row == null || row.length() != 4) return null;
        try {
            Object session = row.get(0);
            long time = integer(row, 1);
            long day = integer(row, 2);
            if (session instanceof String id && validSession(id) && time >= 0 && validDay(day)) {
                return new Sample(id, time, day, readCounters(row.getJSONArray(3)));
            }
        } catch (JSONException invalid) {
            // Ignore an unusable baseline so the next complete sample can replace it.
        }
        return null;
    }

    private static TreeMap<Integer, DailyTraffic.Totals> readCounters(JSONArray rows) throws JSONException {
        if (rows.length() > MAX_UIDS + 1) throw new JSONException("Too many traffic rows");
        TreeMap<Integer, DailyTraffic.Totals> counts = new TreeMap<>();
        for (int i = 0; i < rows.length(); i++) {
            JSONArray row = rows.getJSONArray(i);
            long uid = integer(row, 0);
            long upload = integer(row, 1);
            long download = integer(row, 2);
            if (row.length() != 3 || uid < UNKNOWN_UID || uid > Integer.MAX_VALUE || upload < 0 || download < 0
                || counts.putIfAbsent((int) uid, new DailyTraffic.Totals(upload, download)) != null) {
                throw new JSONException("Invalid traffic row");
            }
        }
        if (counts.size() == MAX_UIDS + 1 && !counts.containsKey(UNKNOWN_UID)) {
            throw new JSONException("Too many application UIDs");
        }
        return counts;
    }

    private static JSONArray writeCounters(Map<Integer, DailyTraffic.Totals> counts) {
        JSONArray rows = new JSONArray();
        counts.forEach((uid, count) -> rows.put(new JSONArray().put(uid).put(count.upload()).put(count.download())));
        return rows;
    }

    private static long integer(JSONArray row, int index) throws JSONException {
        Object value = row.get(index);
        if (value instanceof Long || value instanceof Integer) return ((Number) value).longValue();
        throw new JSONException("Expected integer");
    }
}

package io.minclash.app;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public final class AppDailyTrafficCheck {
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final ZoneId UTC = ZoneId.of("UTC");
    private static final long MIDNIGHT = Instant.parse("2026-11-22T16:00:00Z").toEpochMilli();
    private static final long DAY = 86_400_000;
    private static final int FIRST = 10_001;
    private static final int SECOND = 10_002;
    private static final int UNKNOWN = AppDailyTraffic.UNKNOWN_UID;

    public static void main(String[] args) {
        samplesAndPersistence();
        dayBoundariesAndCoreRestarts();
        clockZoneChanges();
        boundedTotalsAndSaturation();
        damagedHistory();
        System.out.println("PASS: app traffic baselines, complete snapshots, day boundaries, core resets, retention and persistence.");
    }

    private static void samplesAndPersistence() {
        AppDailyTraffic traffic = new AppDailyTraffic("");
        traffic.record("core", MIDNIGHT, SHANGHAI, Map.of(FIRST, count(10, 30)));
        require(today(traffic, MIDNIGHT).isEmpty(), "install starts from a baseline");
        traffic.record("core", MIDNIGHT + 10_000, SHANGHAI,
            Map.of(FIRST, count(14, 35), SECOND, count(5, 8), UNKNOWN, count(1, 12)));
        Map<Integer, DailyTraffic.Totals> initial = Map.of(FIRST, count(4, 5), SECOND, count(5, 8), UNKNOWN, count(1, 12));
        require(today(traffic, MIDNIGHT).equals(initial), "count a new bucket from zero within a core session");
        require(new ArrayList<>(today(traffic, MIDNIGHT).keySet()).equals(List.of(UNKNOWN, FIRST, SECOND)), "stable UID order");
        traffic = new AppDailyTraffic(traffic.checkpoint());
        require(traffic.checkpoint() == null && today(traffic, MIDNIGHT).equals(initial), "restore saved day and baseline");

        traffic.record("core", MIDNIGHT + 8_000, SHANGHAI, Map.of(FIRST, count(11, 31)));
        traffic.record("core", MIDNIGHT + 20_000, SHANGHAI, Map.of(FIRST, count(16, 36)));
        require(traffic.checkpoint() == null, "late and incomplete snapshots do not change the baseline");
        traffic.record("core", MIDNIGHT + 30_000, SHANGHAI,
            Map.of(FIRST, count(20, 41), SECOND, count(7, 10), UNKNOWN, count(3, 16)));
        require(today(traffic, MIDNIGHT).equals(Map.of(FIRST, count(10, 11), SECOND, count(7, 10), UNKNOWN, count(3, 16))),
            "same-day network gaps count each available difference once");
    }

    private static void dayBoundariesAndCoreRestarts() {
        AppDailyTraffic traffic = new AppDailyTraffic(null);
        traffic.record("core", MIDNIGHT - 60_000, SHANGHAI, Map.of(FIRST, count(100, 1_000)));
        traffic.record("core", MIDNIGHT - 45_000, SHANGHAI, Map.of(FIRST, count(110, 1_050)));
        traffic.checkpoint();
        traffic.record("core", MIDNIGHT - 2_000, SHANGHAI, Map.of(FIRST, count(110, 1_050)));
        require(traffic.checkpoint() == null, "idle samples do not write the same totals again");
        traffic.record("core", MIDNIGHT + 2_000, SHANGHAI, Map.of(FIRST, count(114, 1_065)));
        require(today(traffic, MIDNIGHT - 2_000).equals(Map.of(FIRST, count(10, 50)))
            && today(traffic, MIDNIGHT).equals(Map.of(FIRST, count(4, 15))), "near midnight records only once on the new day");

        traffic.record("new", MIDNIGHT + 12_000, SHANGHAI, Map.of(FIRST, count(50_000, 100_000)));
        traffic.record("new", MIDNIGHT + 22_000, SHANGHAI, Map.of(FIRST, count(50_003, 100_004), SECOND, count(1, 2)));
        traffic.record("new", MIDNIGHT + 32_000, SHANGHAI, Map.of(FIRST, count(0, 1), SECOND, count(2, 4)));
        require(today(traffic, MIDNIGHT).equals(Map.of(FIRST, count(7, 19), SECOND, count(2, 4))),
            "fresh core has a baseline and one counter rollback preserves other UID totals");

        traffic.record("new", MIDNIGHT + DAY, SHANGHAI, Map.of(FIRST, count(100, 200), SECOND, count(8, 10)));
        require(today(traffic, MIDNIGHT + DAY).isEmpty(), "do not guess dates after a long cross-day gap");
        traffic.record("new", MIDNIGHT + 8 * DAY, SHANGHAI, Map.of(FIRST, count(200, 400), SECOND, count(20, 50)));
        traffic = new AppDailyTraffic(traffic.checkpoint());
        List<AppDailyTraffic.Day> recent = traffic.recent(MIDNIGHT + 8 * DAY, SHANGHAI);
        require(recent.size() == 7 && recent.stream().allMatch(day -> day.totals().isEmpty()), "expire days outside retention");
        require(recent.get(0).date().equals(LocalDate.of(2026, 12, 1)), "most recent local date first");
    }

    private static void clockZoneChanges() {
        AppDailyTraffic traffic = new AppDailyTraffic(null);
        traffic.record("core", MIDNIGHT + 2_000, SHANGHAI, Map.of(FIRST, count(5, 9)));
        traffic.record("core", MIDNIGHT + 4_000, SHANGHAI, Map.of(FIRST, count(6, 11)));
        traffic.record("core", MIDNIGHT + 6_000, UTC, Map.of(FIRST, count(50, 50)));
        require(traffic.recent(MIDNIGHT + 6_000, UTC).get(0).totals().isEmpty(), "do not infer a day after a zone change");
        require(today(traffic, MIDNIGHT + 6_000).equals(Map.of(FIRST, count(1, 2))), "keep traffic assigned in its original zone");
        traffic.record("core", MIDNIGHT + 8_000, UTC, Map.of(FIRST, count(54, 55)));
        require(traffic.recent(MIDNIGHT + 8_000, UTC).get(0).totals().equals(Map.of(FIRST, count(4, 5))),
            "normal counting resumes in the new zone");
    }

    private static void boundedTotalsAndSaturation() {
        AppDailyTraffic traffic = new AppDailyTraffic(null);
        TreeMap<Integer, DailyTraffic.Totals> full = new TreeMap<>();
        for (int i = 0; i < AppDailyTraffic.MAX_UIDS; i++) full.put(FIRST + i, count(1, 3));
        full.put(FIRST, count(Long.MAX_VALUE, Long.MAX_VALUE));
        full.put(UNKNOWN, count(4, 7));
        traffic.record("core", MIDNIGHT, SHANGHAI, Map.of());
        traffic.record("core", MIDNIGHT + 10_000, SHANGHAI, full);
        require(today(traffic, MIDNIGHT).size() == AppDailyTraffic.MAX_UIDS + 1, "capacity is 512 known UIDs plus unknown");
        int overflowUid = FIRST + AppDailyTraffic.MAX_UIDS;
        full.remove(UNKNOWN);
        full.put(overflowUid, count(3, 3));
        traffic.checkpoint();
        traffic.record("invalid", MIDNIGHT + 20_000, SHANGHAI, full);
        require(traffic.checkpoint() == null, "too many known UIDs reject the snapshot");

        traffic.record("new", MIDNIGHT + 20_000, SHANGHAI, Map.of(FIRST, count(5, 6)));
        traffic.record("new", MIDNIGHT + 30_000, SHANGHAI, Map.of(FIRST, count(10, 12), overflowUid, count(6, 8)));
        traffic = new AppDailyTraffic(traffic.checkpoint());
        require(today(traffic, MIDNIGHT).get(FIRST).equals(count(Long.MAX_VALUE, Long.MAX_VALUE)), "saturate rather than wrap");
        require(today(traffic, MIDNIGHT).get(UNKNOWN).equals(count(10, 15)) && !today(traffic, MIDNIGHT).containsKey(overflowUid),
            "UID churn beyond daily capacity goes to the unknown total");
    }

    private static void damagedHistory() {
        long day = LocalDate.of(2026, 11, 23).toEpochDay();
        String saved = "[[\"old\",\"bad timestamp\",0,[]],[[" + day + ",[[10001,3,4]]],[" + (day - 1) + ",[[10001,-1,1]]]]]";
        AppDailyTraffic traffic = new AppDailyTraffic(saved);
        require(today(traffic, MIDNIGHT).equals(Map.of(FIRST, count(3, 4))) && today(traffic, MIDNIGHT - DAY).isEmpty(),
            "recover valid history independently of damaged baseline and other rows");
        traffic.record("old", MIDNIGHT, SHANGHAI, Map.of(FIRST, count(100, 300)));
        require(today(traffic, MIDNIGHT).equals(Map.of(FIRST, count(3, 4))), "unusable baseline does not pull earlier counters into history");
    }

    private static Map<Integer, DailyTraffic.Totals> today(AppDailyTraffic traffic, long now) {
        return traffic.recent(now, SHANGHAI).get(0).totals();
    }

    private static DailyTraffic.Totals count(long upload, long download) {
        return new DailyTraffic.Totals(upload, download);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}

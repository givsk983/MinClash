package io.minclash.app;

import java.time.Instant;
import java.time.ZoneId;

public final class DailyTrafficCheck {
    public static void main(String[] args) {
        ZoneId local = ZoneId.of("Asia/Shanghai");
        long midnight = Instant.parse("2026-11-17T16:00:00Z").toEpochMilli();
        long day = 86_400_000;
        DailyTraffic traffic = new DailyTraffic("bad data");
        traffic.record("first", midnight - 4_000, local, 1_000, 8_000);
        require(traffic.recent(midnight - 4_000, local).get(0).totals() == null, "first reading is a baseline");
        traffic.record("first", midnight - 2_000, local, 1_100, 8_500);
        traffic = new DailyTraffic(traffic.checkpoint());
        require(traffic.recent(midnight - 2_000, local).get(0).totals().equals(new DailyTraffic.Totals(100, 500)),
            "daily totals survive reopening the app");
        traffic.record("first", midnight, local, 1_150, 9_000);
        require(traffic.recent(midnight, local).get(0).totals().equals(new DailyTraffic.Totals(50, 500)),
            "consecutive samples near midnight count once");
        traffic.record("second", midnight + 2_000, local, 90_000, 200_000);
        traffic.record("second", midnight + 4_000, local, 90_200, 200_800);
        require(traffic.recent(midnight + 4_000, local).get(0).totals().equals(new DailyTraffic.Totals(250, 1_300)),
            "restarted core starts a fresh baseline");
        traffic.record("second", midnight + day, local, 95_000, 280_000);
        require(traffic.recent(midnight + day, local).get(0).totals() == null, "long cross-day gap stays unassigned");
        traffic.record("second", midnight + day + 2_000, local, 95_001, 280_002);
        traffic.record("second", midnight + day + 4_000, local, 0, 0);
        require(traffic.recent(midnight + day + 4_000, local).get(0).totals().equals(new DailyTraffic.Totals(1, 2)),
            "counter rollback does not subtract traffic");
        traffic.record("second", midnight + 8 * day, local, 12, 54);
        traffic = new DailyTraffic(traffic.checkpoint());
        require(traffic.recent(midnight + 8 * day, local).size() == 7
            && traffic.recent(midnight + 8 * day, local).stream().allMatch(item -> item.totals() == null),
            "only the seven most recent dates are kept");
        System.out.println("PASS: daily traffic baselines, persistence, midnight, counter resets, and retention.");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}

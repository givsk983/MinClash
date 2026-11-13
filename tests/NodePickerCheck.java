package io.minclash.app;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class NodePickerCheck {
    public static void main(String[] args) throws Exception {
        try {
            selectionIdentity();
            boundedBatch();
            cancelledResults();
            individualDirect();
            System.out.println("PASS: node identity after sorting/filtering, subscription-scoped favorites, bounded probes, cancellation and stale results.");
        } finally {
            RoutingStore.WORKER.shutdownNow();
        }
    }

    private static void selectionIdentity() {
        List<String> names = List.of("slow", "fast", "failed", "unknown", "favorite", "DIRECT", "REJECT");
        Set<String> favorites = Set.of("favorite", "vanished");
        Map<String, Integer> delays = Map.of("slow", 800, "fast", 20, "failed", 0, "favorite", 400);
        List<Integer> sorted = NodeList.order(names, favorites, delays, true, false);
        require(sorted.equals(List.of(4, 1, 0, 3, 5, 6, 2)), "favorites first, successful RTTs, untested then failed");
        require(names.get(sorted.get(1)).equals("fast"), "click selects original fast node after reorder");
        require(NodeList.order(names, favorites, delays, false, false).equals(List.of(4, 0, 1, 2, 3, 5, 6)),
            "original order can be restored while preserving favorite pinning");
        List<Integer> filtered = NodeList.order(names, favorites, delays, true, true);
        require(!filtered.contains(2) && filtered.contains(3) && filtered.contains(5) && filtered.contains(6),
            "failure filtering retains untested and built-in outlets");
        require(names.get(filtered.get(1)).equals("fast"), "filtering does not shift selection identity");
        require(NodeList.order(List.of("a", "b"), Set.of(), Map.of("a", 0, "b", 0), true, true).isEmpty(),
            "all failures may be hidden without an invalid selectable row");
        String first = NodeList.favoritesKey("https://one.example/secret-token");
        require(first.equals(NodeList.favoritesKey("https://one.example/secret-token")), "same subscription retains favorites after refresh");
        require(!first.equals(NodeList.favoritesKey("https://two.example/secret-token"))
            && !first.equals(NodeList.favoritesKey("")) && !first.contains("secret-token"),
            "subscriptions and local profiles have separate favorite keys without exposing URLs");
    }

    private static void boundedBatch() throws Exception {
        RecordingPool pool = new RecordingPool();
        CountDownLatch entered = new CountDownLatch(3);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maximum = new AtomicInteger();
        AtomicInteger calls = new AtomicInteger();
        NodeLatencyBatch[] current = new NodeLatencyBatch[1];
        current[0] = new NodeLatencyBatch(pool, name -> {
            calls.incrementAndGet();
            int count = active.incrementAndGet();
            maximum.accumulateAndGet(count, Math::max);
            entered.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("probe release timed out");
                if (name.equals("bad")) throw new IOException("unreachable");
                return 30;
            } finally { active.decrementAndGet(); }
        }, () -> {
            NodeLatencyBatch.Snapshot state = current[0].snapshot();
            if (!state.running() && state.completed() == 10) finished.countDown();
        });
        NodeLatencyBatch batch = current[0];
        try {
            List<String> names = new ArrayList<>(List.of("bad", "DIRECT", "REJECT", "REJECT-DROP", "PASS", "COMPATIBLE"));
            for (int i = 0; i < 9; i++) names.add("node-" + i);
            names.add("node-0");
            require(batch.start(names), "batch starts");
            require(entered.await(2, TimeUnit.SECONDS), "three probes can run concurrently");
            require(maximum.get() == 3 && pool.getQueue().isEmpty(), "bounded concurrency without per-node queued tasks");
            require(!batch.start(List.of("duplicate")), "repeated taps cannot start overlapping batches");
            require(batch.snapshot().total() == 10, "duplicate and built-in outlets excluded");
            require(RoutingStore.WORKER.submit(() -> "control-ready").get(1, TimeUnit.SECONDS).equals("control-ready"),
                "blocked latency probes leave the control queue available");
            release.countDown();
            require(finished.await(2, TimeUnit.SECONDS), "one probe failure does not stop the rest");
            require(calls.get() == 10 && maximum.get() <= 3, "each eligible node measured exactly once");
            require(batch.snapshot().delays().get("bad") == 0 && batch.snapshot().pending().isEmpty(),
                "failure is recorded and pending count drains");
        } finally {
            release.countDown();
            batch.close();
            require(pool.awaitTermination(2, TimeUnit.SECONDS), "closed picker releases its probe threads");
        }
        require(!batch.start(List.of("after-close")), "dismissed picker cannot restart probes");
    }

    private static void cancelledResults() throws Exception {
        RecordingPool pool = new RecordingPool();
        CountDownLatch oldEntered = new CountDownLatch(1);
        CountDownLatch releaseOld = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        NodeLatencyBatch batch = new NodeLatencyBatch(pool, name -> {
            if (calls.incrementAndGet() == 1) {
                oldEntered.countDown();
                // Simulate a native/socket call that returns despite cancellation.
                boolean released = false;
                while (!released) {
                    try { released = releaseOld.await(3, TimeUnit.SECONDS); }
                    catch (InterruptedException ignored) { }
                }
                return 900;
            }
            return 25;
        }, () -> {});
        try {
            batch.start(List.of("same"));
            require(oldEntered.await(2, TimeUnit.SECONDS), "old probe entered");
            batch.cancel();
            require(!batch.snapshot().running() && batch.snapshot().pending().isEmpty(), "stop clears pending state immediately");
            require(batch.start(List.of("same")), "a new measurement can start after stop");
            require(pool.finished.poll(2, TimeUnit.SECONDS) != null, "new probe completes while old call is blocked");
            require(batch.snapshot().delays().get("same") == 25, "new result is visible");
            releaseOld.countDown();
            require(pool.finished.poll(2, TimeUnit.SECONDS) != null, "old cancelled worker exits");
            require(batch.snapshot().completed() == 1 && batch.snapshot().delays().get("same") == 25,
                "late old result cannot overwrite or advance the new batch");
            require(!batch.start(List.of("DIRECT", "REJECT")), "built-in-only groups cannot begin an empty batch");
            require(batch.snapshot().delays().get("same") == 25, "empty batch preserves completed measurements");
        } finally {
            releaseOld.countDown();
            batch.close();
            require(pool.awaitTermination(2, TimeUnit.SECONDS), "cancelled worker is released");
        }
    }

    private static final class RecordingPool extends ThreadPoolExecutor {
        final BlockingQueue<Runnable> finished = new LinkedBlockingQueue<>();
        RecordingPool() { super(3, 3, 0, TimeUnit.SECONDS, new LinkedBlockingQueue<>()); }
        @Override protected void afterExecute(Runnable runnable, Throwable failure) { finished.add(runnable); }
    }

    private static void individualDirect() throws Exception {
        RecordingPool pool = new RecordingPool();
        try (NodeLatencyBatch batch = new NodeLatencyBatch(pool, name -> 18, () -> {})) {
            require(batch.startOne("DIRECT"), "existing individual DIRECT latency test remains available");
            require(pool.finished.poll(2, TimeUnit.SECONDS) != null && batch.snapshot().delays().get("DIRECT") == 18,
                "individual DIRECT result is recorded");
            require(!batch.startOne("REJECT"), "reject outlets do not issue latency probes");
        }
        require(pool.awaitTermination(2, TimeUnit.SECONDS), "individual test releases threads");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}

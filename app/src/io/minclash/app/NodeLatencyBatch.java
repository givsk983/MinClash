package io.minclash.app;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** A picker owns its probes; cancellation invalidates even results from uninterruptible calls. */
final class NodeLatencyBatch implements AutoCloseable {
    interface Probe { int measure(String name) throws Exception; }
    record Snapshot(Map<String, Integer> delays, Set<String> pending, int completed, int total, boolean running) {}
    private static final int CONCURRENCY = 3;
    private final ExecutorService probes;
    private final Probe probe;
    private final Runnable changed;
    private final Map<String, Integer> delays = new HashMap<>();
    private final Set<String> pending = new HashSet<>();
    private final List<Future<?>> workers = new ArrayList<>();
    private List<String> names = Collections.emptyList();
    private int next;
    private int completed;
    private int generation;
    private boolean running;
    private boolean closed;

    NodeLatencyBatch(Probe probe, Runnable changed) {
        this(Executors.newFixedThreadPool(CONCURRENCY), probe, changed);
    }

    NodeLatencyBatch(ExecutorService probes, Probe probe, Runnable changed) {
        this.probes = probes;
        this.probe = probe;
        this.changed = changed;
    }

    synchronized Snapshot snapshot() {
        return new Snapshot(Collections.unmodifiableMap(new HashMap<>(delays)),
            Collections.unmodifiableSet(new HashSet<>(pending)), completed, names.size(), running);
    }

    synchronized boolean start(List<String> requested) {
        return start(requested, false);
    }

    synchronized boolean startOne(String name) {
        return start(Collections.singletonList(name), true);
    }

    private boolean start(List<String> requested, boolean individual) {
        if (closed || running) return false;
        Set<String> unique = new LinkedHashSet<>();
        for (String name : requested) if (NodeList.testable(name) || individual && name.equals("DIRECT")) unique.add(name);
        if (unique.isEmpty()) return false;
        names = new ArrayList<>(unique);
        int current = ++generation;
        next = 0;
        completed = 0;
        running = true;
        pending.clear();
        pending.addAll(names);
        names.forEach(delays::remove);
        workers.clear();
        for (int i = 0; i < Math.min(CONCURRENCY, names.size()); i++) {
            workers.add(probes.submit(() -> work(current)));
        }
        changed.run();
        return true;
    }

    private void work(int current) {
        while (true) {
            String name;
            synchronized (this) {
                if (closed || current != generation || !running || next >= names.size()) return;
                name = names.get(next++);
            }
            int delay;
            try { delay = Math.max(0, probe.measure(name)); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return; }
            catch (Exception failed) { delay = 0; }
            synchronized (this) {
                if (closed || current != generation) return;
                delays.put(name, delay);
                pending.remove(name);
                completed++;
                if (completed == names.size()) running = false;
            }
            changed.run();
        }
    }

    synchronized void cancel() {
        ++generation;
        running = false;
        pending.clear();
        for (Future<?> worker : workers) worker.cancel(true);
        workers.clear();
        changed.run();
    }

    @Override public synchronized void close() {
        closed = true;
        cancel();
        probes.shutdownNow();
    }
}

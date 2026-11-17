package io.minclash.app;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public final class BackgroundWorkCheck {
    public static void main(String[] args) throws Exception {
        try {
            retainedResults();
            isolatedDownload();
            coalescedRefresh();
            cancelledGenerations();
            System.out.println("PASS: detached/recreated observers, retained results/errors, nonblocking control and coalesced refresh.");
        } finally {
            BackgroundWork.DOWNLOADS.shutdownNow();
            RoutingStore.WORKER.shutdownNow();
        }
    }

    private static void retainedResults() {
        Queue<Runnable> io = new ArrayDeque<>();
        Queue<Runnable> ui = new ArrayDeque<>();
        RetainedTask<String> task = new RetainedTask<>();
        int[] oldView = {0};
        int[] newView = {0};
        task.observe(ui::add, () -> oldView[0]++);
        require(task.start(io::add, () -> "preview"), "first task accepted");
        require(!task.start(io::add, () -> "duplicate"), "in-flight task cannot be duplicated");
        task.stopObserving();
        io.remove().run();
        while (!ui.isEmpty()) ui.remove().run();
        require(oldView[0] == 0, "detached Activity must not receive queued callbacks");
        require(!task.running() && task.outcome().value().equals("preview"), "completion while detached is retained");
        task.observe(ui::add, () -> newView[0]++);
        while (!ui.isEmpty()) ui.remove().run();
        require(newView[0] > 0 && oldView[0] == 0, "replacement Activity observes retained completion");
        require(!task.start(io::add, () -> "overwrite"), "visible result survives until dismissed");
        task.stopObserving();
        task.observe(ui::add, () -> newView[0]++);
        require(task.outcome().value().equals("preview"), "another recreation retains displayed result");
        task.clearResult();
        require(task.start(io::add, () -> { throw new IOException("offline"); }), "dismissal allows retry");
        io.remove().run();
        require(task.outcome().error() instanceof IOException, "failure is retained without losing its category");
        task.clearResult();
        require(task.start(Runnable::run, () -> null), "null success accepted");
        require(task.outcome() != null && task.outcome().error() == null, "no update is distinct from no result");
        task.clearResult();
        task.start(command -> { throw new java.util.concurrent.RejectedExecutionException(); }, () -> "unused");
        require(!task.running() && task.outcome().error() != null, "submission failure must not leave task busy");
        task.stopObserving();
    }

    private static void isolatedDownload() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        RetainedTask<String> slow = new RetainedTask<>();
        slow.observe(Runnable::run, () -> { if (slow.outcome() != null) finished.countDown(); });
        slow.start(BackgroundWork.DOWNLOADS, () -> {
            started.countDown();
            if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("test timed out");
            return "download";
        });
        try {
            require(started.await(2, TimeUnit.SECONDS), "download starts");
            String result = RoutingStore.WORKER.submit(() -> "control-ready").get(2, TimeUnit.SECONDS);
            require(result.equals("control-ready") && slow.running(), "control queue remains usable during blocked download");
        } finally {
            release.countDown();
        }
        require(finished.await(2, TimeUnit.SECONDS), "download completes independently");
        slow.stopObserving();
    }

    private static void coalescedRefresh() {
        RefreshGate gate = new RefreshGate();
        require(gate.request(), "first refresh starts");
        for (int i = 0; i < 1000; i++) require(!gate.request(), "burst must not enqueue another query");
        require(gate.finish(), "burst requests exactly one follow-up");
        require(gate.request(), "follow-up starts after first query completes");
        require(!gate.finish(), "no further query without a new request");
        require(gate.request() && !gate.finish(), "ordinary periodic queries still work");
    }

    private static void cancelledGenerations() throws Exception {
        Queue<Runnable> queued = new ArrayDeque<>();
        RetainedTask<String> task = new RetainedTask<>();
        int[] calls = {0};
        task.start(queued::add, () -> { calls[0]++; return "cancelled"; }, () -> true);
        require(task.cancellable() && task.cancel(), "queued request cancels immediately");
        task.start(queued::add, () -> "replacement");
        queued.remove().run(); queued.remove().run();
        require(calls[0] == 0 && task.outcome().value().equals("replacement"), "cancelled queued work has no effects");
        task.clearResult();
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1), done = new CountDownLatch(1);
        task.start(command -> new Thread(() -> { command.run(); done.countDown(); }).start(), () -> {
            started.countDown(); release.await(); return "late old result";
        }, () -> true);
        require(started.await(2, TimeUnit.SECONDS) && task.cancel(), "running request can cancel");
        task.start(Runnable::run, () -> "new result");
        release.countDown(); require(done.await(2, TimeUnit.SECONDS), "old request drains");
        require(task.outcome().value().equals("new result"), "late completion cannot replace newer result");
        task.clearResult();
        task.start(queued::add, () -> "committed", () -> false);
        require(!task.cancel() && task.running(), "write phase refuses a cancellation promise");
        queued.remove().run();
        require(task.outcome().value().equals("committed"), "write phase publishes normally");
        task.clearResult();
        task.start(Runnable::run, () -> { throw new java.util.concurrent.ExecutionException(new DownloadRequest.Expired()); });
        require(task.outcome().error() instanceof DownloadRequest.Expired, "serialized task failure keeps its public error category");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}

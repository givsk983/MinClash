package io.minclash.app;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.RejectedExecutionException;

/** Per-request deadline and cancellation, independent of an Activity or an Android job instance. */
final class DownloadRequest implements AutoCloseable {
    static final long SUBSCRIPTION_TIMEOUT_MS = 60_000;
    private static final ScheduledExecutorService CLOCK = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "minclash-download-deadline");
        thread.setDaemon(true);
        return thread;
    });
    private static final ExecutorService CLOSER = Executors.newFixedThreadPool(2, r -> {
        Thread thread = new Thread(r, "minclash-download-close");
        thread.setDaemon(true);
        return thread;
    });
    // A platform stream can hold its read lock while disconnect waits. Keep those
    // bounded socket operations off the shared download queue; never grow without a limit.
    private static final ExecutorService NETWORK = new ThreadPoolExecutor(0, 4, 30, TimeUnit.SECONDS,
        new SynchronousQueue<>(), r -> {
            Thread thread = new Thread(r, "minclash-subscription-http");
            thread.setDaemon(true);
            return thread;
        });

    static final class Cancelled extends IOException {
        Cancelled() { super("下载已取消。"); }
    }
    static final class Expired extends IOException {
        Expired() { super("订阅请求已超过总时限，请重试。"); }
    }

    private final long deadline;
    private HttpURLConnection connection;
    private ScheduledFuture<?> alarm;
    private CompletableFuture<?> pending;
    private boolean cancelled, expired, sealed;

    DownloadRequest() { this(SUBSCRIPTION_TIMEOUT_MS); }
    DownloadRequest(long timeoutMillis) {
        if (timeoutMillis <= 0) throw new IllegalArgumentException("timeout must be positive");
        deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
    }

    synchronized void check() throws IOException {
        if (cancelled) throw new Cancelled();
        if (expired || !sealed && System.nanoTime() - deadline >= 0) throw new Expired();
    }

    synchronized int socketTimeout() throws IOException {
        check();
        long remaining = Math.max(1, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()));
        return (int) Math.min(12_000, remaining);
    }

    synchronized void attach(HttpURLConnection current) throws IOException {
        check();
        connection = current;
        if (alarm == null) alarm = CLOCK.schedule(this::expire, Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
    }

    <T> T call(Callable<T> operation) throws IOException {
        CompletableFuture<T> result = new CompletableFuture<>();
        synchronized (this) {
            check();
            pending = result;
            if (alarm == null) alarm = CLOCK.schedule(this::expire, Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
        }
        try {
            NETWORK.execute(() -> {
                try { check(); result.complete(operation.call()); }
                catch (Exception error) { result.completeExceptionally(error); }
            });
            return result.get();
        } catch (RejectedExecutionException busy) {
            throw new IOException("同时进行的订阅请求较多，请稍后重试。");
        } catch (InterruptedException interrupted) {
            cancel();
            Thread.currentThread().interrupt();
            throw new Cancelled();
        } catch (ExecutionException failed) {
            if (failed.getCause() instanceof IOException error) throw error;
            if (failed.getCause() instanceof RuntimeException error) throw error;
            throw new IOException("无法读取订阅，请重试。");
        } finally {
            synchronized (this) { if (pending == result) pending = null; }
        }
    }

    synchronized void detach(HttpURLConnection current) {
        if (connection == current) connection = null;
    }

    /** Once the serialized write starts, the UI must wait for its result instead of promising cancellation. */
    synchronized void seal() throws IOException {
        check();
        sealed = true;
        if (alarm != null) alarm.cancel(false);
    }

    boolean cancel() {
        HttpURLConnection current;
        synchronized (this) {
            if (sealed) return false;
            cancelled = true;
            if (alarm != null) alarm.cancel(false);
            if (pending != null) pending.completeExceptionally(new Cancelled());
            current = connection;
        }
        disconnect(current);
        return true;
    }

    private void expire() {
        HttpURLConnection current;
        synchronized (this) {
            if (sealed || cancelled) return;
            expired = true;
            if (pending != null) pending.completeExceptionally(new Expired());
            current = connection;
        }
        disconnect(current);
    }

    private static void disconnect(HttpURLConnection current) {
        if (current != null) CLOSER.execute(current::disconnect);
    }

    @Override public synchronized void close() {
        if (alarm != null) alarm.cancel(false);
        connection = null;
    }
}

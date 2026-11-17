package io.minclash.app;

import java.util.concurrent.Callable;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;

/** An Activity-independent task whose result stays available until the user dismisses it. */
final class RetainedTask<T> {
    record Outcome<T>(T value, Exception error) {}
    private boolean running;
    private Outcome<T> outcome;
    private Executor dispatcher;
    private Runnable observer;
    private BooleanSupplier cancellation;
    private int generation;

    synchronized boolean running() { return running; }
    synchronized Outcome<T> outcome() { return outcome; }

    boolean start(Executor executor, Callable<T> operation) {
        return start(executor, operation, null);
    }

    boolean start(Executor executor, Callable<T> operation, BooleanSupplier cancel) {
        final int current;
        synchronized (this) {
            if (running || outcome != null) return false;
            running = true;
            cancellation = cancel;
            current = ++generation;
        }
        notifyObserver();
        try {
            executor.execute(() -> {
                synchronized (this) { if (current != generation) return; }
                Outcome<T> completed;
                try { completed = new Outcome<>(operation.call(), null); }
                catch (Exception error) {
                    while (error instanceof java.util.concurrent.ExecutionException && error.getCause() instanceof Exception cause) error = cause;
                    completed = new Outcome<>(null, error);
                }
                synchronized (this) {
                    if (current != generation) return;
                    outcome = completed;
                    running = false;
                    cancellation = null;
                }
                notifyObserver();
            });
        } catch (RuntimeException error) {
            synchronized (this) {
                if (current != generation) return true;
                outcome = new Outcome<>(null, error);
                running = false;
                cancellation = null;
            }
            notifyObserver();
        }
        return true;
    }

    synchronized boolean cancellable() { return running && cancellation != null; }

    boolean cancel() {
        synchronized (this) {
            if (!running || cancellation == null || !cancellation.getAsBoolean()) return false;
            generation++;
            running = false;
            outcome = null;
            cancellation = null;
        }
        notifyObserver();
        return true;
    }

    synchronized void clearResult() { outcome = null; }

    void observe(Executor executor, Runnable callback) {
        synchronized (this) {
            dispatcher = executor;
            observer = callback;
        }
        notifyObserver();
    }

    synchronized void stopObserving() {
        observer = null;
        dispatcher = null;
    }

    private void notifyObserver() {
        Executor executor;
        synchronized (this) { executor = dispatcher; }
        if (executor == null) return;
        executor.execute(() -> {
            Runnable callback;
            synchronized (this) { callback = observer; }
            if (callback != null) callback.run();
        });
    }
}

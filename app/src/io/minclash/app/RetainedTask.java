package io.minclash.app;

import java.util.concurrent.Callable;
import java.util.concurrent.Executor;

/** An Activity-independent task whose result stays available until the user dismisses it. */
final class RetainedTask<T> {
    record Outcome<T>(T value, Exception error) {}
    private boolean running;
    private Outcome<T> outcome;
    private Executor dispatcher;
    private Runnable observer;

    synchronized boolean running() { return running; }
    synchronized Outcome<T> outcome() { return outcome; }

    boolean start(Executor executor, Callable<T> operation) {
        synchronized (this) {
            if (running || outcome != null) return false;
            running = true;
        }
        notifyObserver();
        try {
            executor.execute(() -> {
                Outcome<T> completed;
                try { completed = new Outcome<>(operation.call(), null); }
                catch (Exception error) { completed = new Outcome<>(null, error); }
                synchronized (this) {
                    outcome = completed;
                    running = false;
                }
                notifyObserver();
            });
        } catch (RuntimeException error) {
            synchronized (this) {
                outcome = new Outcome<>(null, error);
                running = false;
            }
            notifyObserver();
        }
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

package io.minclash.app;

/** Main-thread gate: one query in flight, with at most one requested follow-up. */
final class RefreshGate {
    private boolean running;
    private boolean pending;

    boolean request() {
        if (running) {
            pending = true;
            return false;
        }
        running = true;
        return true;
    }

    boolean finish() {
        running = false;
        boolean followUp = pending;
        pending = false;
        return followUp;
    }
}

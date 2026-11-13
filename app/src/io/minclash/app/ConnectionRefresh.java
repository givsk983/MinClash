package io.minclash.app;

/** Main-thread coordination: coalesce refreshes and invalidate reads predating an action. */
final class ConnectionRefresh {
    record Finished(boolean publish, boolean followUp) {}
    private boolean reading, changing, pending;
    private int revision;
    boolean beginRead() {
        if (reading || changing) { pending = true; return false; }
        reading = true; pending = false; return true;
    }
    int revision() { return revision; }
    Finished finishRead(int started) {
        reading = false;
        return new Finished(started == revision && !changing, pending && !changing);
    }
    boolean beginChange() {
        if (changing) return false;
        changing = true; pending = true; revision++; return true;
    }
    boolean finishChange() { changing = false; return !reading; }
    boolean reading() { return reading; }
    boolean changing() { return changing; }
}

package io.minclash.app;

import android.content.Context;
import android.os.SystemClock;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileLock;
import java.util.concurrent.locks.ReentrantLock;

/** A short-lived display snapshot shared by the overview, tile, and notification process. */
final class RuntimeStatusCache {
    private static final long FRESH_MS = 1250;
    private static final long MAX_SIZE = 16384;
    private static final ReentrantLock LOCAL = new ReentrantLock();
    private static boolean notificationSupported;

    static Module.Status read(Context context) throws Exception {
        return read(context, false);
    }

    static Module.Status notification(Context context) throws Exception {
        return read(context, true);
    }

    private static Module.Status read(Context context, boolean notification) throws Exception {
        LOCAL.lockInterruptibly();
        try { return readShared(context, notification); }
        finally { LOCAL.unlock(); }
    }

    private static Module.Status readShared(Context context, boolean notification) throws Exception {
        boolean fetch = false;
        try (RandomAccessFile file = new RandomAccessFile(path(context), "rw");
             FileLock lock = file.getChannel().tryLock()) {
            if (lock != null) {
                Module.Status status = cached(file);
                if (status != null) {
                    if (!notification) return status;
                    notificationSupported = status.runtime.notificationRecovery();
                    if (!notificationSupported) return status;
                    if (status.runtime.notificationHeartbeat()) {
                        try {
                            Module.run(Module.Command.NOTIFICATION_HEARTBEAT, null);
                            return status;
                        } catch (Exception ignored) { }
                    }
                }
                fetch = true;
                return fresh(file, notification);
            }
        } catch (IOException error) { if (fetch) throw error; }
        return fresh(null, notification);
    }

    private static Module.Status cached(RandomAccessFile file) {
        try {
            if (file.length() < Long.BYTES * 2 || file.length() > MAX_SIZE) return null;
            long elapsed = SystemClock.elapsedRealtime() - file.readLong();
            long wall = System.currentTimeMillis() - file.readLong();
            if (elapsed < 0 || elapsed >= FRESH_MS || wall < 0 || wall >= FRESH_MS) return null;
            return Module.parseStatus(file.readUTF());
        } catch (IOException ignored) {
            return null;
        }
    }

    private static Module.Status fresh(RandomAccessFile file, boolean notification) throws Exception {
        String output = Module.run(notification && notificationSupported
            ? Module.Command.STATUS_NOTIFICATION : Module.Command.STATUS, null);
        Module.Status status = Module.parseStatus(output);
        if (notification) notificationSupported = status.runtime.notificationRecovery();
        if (file != null) {
            try {
                file.seek(0);
                file.setLength(0);
                file.writeLong(SystemClock.elapsedRealtime());
                file.writeLong(System.currentTimeMillis());
                file.writeUTF(output);
            } catch (IOException ignored) { }
        }
        return status;
    }

    static void invalidate(Context context) {
        if (!LOCAL.tryLock()) return;
        try (RandomAccessFile file = new RandomAccessFile(path(context), "rw");
             FileLock lock = file.getChannel().tryLock()) {
            if (lock != null) file.setLength(0);
        } catch (IOException | RuntimeException ignored) { }
        finally { LOCAL.unlock(); }
    }

    private static File path(Context context) { return new File(context.getCacheDir(), "runtime-status"); }
    private RuntimeStatusCache() {}
}

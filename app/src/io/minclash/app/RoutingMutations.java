package io.minclash.app;

import android.content.Context;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.channels.FileLock;
import java.util.concurrent.Callable;
import java.util.concurrent.locks.ReentrantLock;

/** Keep complete configuration and selection changes ordered across the App's processes. */
final class RoutingMutations {
    private static final ReentrantLock LOCAL = new ReentrantLock(true);

    static <T> T run(Context context, Callable<T> work) throws Exception {
        boolean outermost = !LOCAL.isHeldByCurrentThread();
        return run(new File(context.getFilesDir(), "routing-mutations.lock"), () -> {
            try { return work.call(); }
            finally { if (outermost) RuntimeStatusCache.invalidate(context); }
        });
    }

    static <T> T run(File path, Callable<T> work) throws Exception {
        if (LOCAL.isHeldByCurrentThread()) return work.call();
        LOCAL.lockInterruptibly();
        try (FileOutputStream file = new FileOutputStream(path, true);
             FileLock lock = file.getChannel().lock()) {
            return work.call();
        } finally {
            LOCAL.unlock();
        }
    }

    static boolean recoverPending(Context context) throws Exception {
        return run(context, () -> {
            boolean backup = new BackupStore(context).recoverPending();
            boolean routing = new RoutingStore(context).recoverPending();
            return backup || routing;
        });
    }

    private RoutingMutations() {}
}

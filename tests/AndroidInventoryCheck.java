package io.minclash.app;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import java.io.File;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** app_process regression check; writes only to the supplied temporary directory. */
public final class AndroidInventoryCheck {
    public static void main(String[] args) throws Exception {
        Looper.prepareMainLooper();
        Class<?> thread = Class.forName("android.app.ActivityThread");
        Object instance = thread.getMethod("systemMain").invoke(null);
        Context system = (Context) thread.getMethod("getSystemContext").invoke(instance);
        File directory = new File(args[0]);
        require(directory.isDirectory() || directory.mkdirs(), "test directory");
        int[] scans = {0};
        Context context = new ContextWrapper(system) {
            @Override public File getFilesDir() { return directory; }
            @Override public String getPackageName() { return "io.minclash.app"; }
            @Override public Context getApplicationContext() { return this; }
            @Override public PackageManager getPackageManager() { scans[0]++; return system.getPackageManager(); }
        };
        Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            try {
                checkInventory(context, scans);
                checkIcons(context, main);
                System.out.println("PASS: inventory reuse, fresh policies/targets, invalidation, async icons, failure caching and retry.");
                System.exit(0);
            } catch (Throwable error) {
                error.printStackTrace();
                System.exit(1);
            }
        }, "inventory-check").start();
        Looper.loop();
    }

    private static void checkInventory(Context context, int[] scans) throws Exception {
        byte[] source = Profile.normalize(("proxies: [{name: test, type: socks5, server: 127.0.0.1, port: 9}]\n"
            + "proxy-groups: [{name: US, type: select, proxies: [test]}]\nrules: ['MATCH,DIRECT']\n")
            .getBytes(StandardCharsets.UTF_8));
        RoutingStore store = new RoutingStore(context);
        Method save = RoutingStore.class.getDeclaredMethod("save", RoutingStore.State.class);
        save.setAccessible(true);
        save.invoke(store, new RoutingStore.State(source, Map.of()));
        Module.Status status = Module.parseStatus("version=0.0.2\nstate=stopped\nprofile=1\nenabled=1\ncontrol=1\n");
        AppRouting.Loader loader = new AppRouting.Loader();
        long start = SystemClock.elapsedRealtime();
        AppRouting.Screen first = loader.load(context, false, status);
        long cold = SystemClock.elapsedRealtime() - start;
        require(!first.systemInventoryEmpty() && first.applications().size() > 1, "real Android inventory");
        require(scans[0] == 1 && first.status() == status, "one scan, reuse known status without su");
        String name = first.applications().get(0).packageName();
        byte[] updated = new String(source, StandardCharsets.UTF_8).replace("US", "HK").getBytes(StandardCharsets.UTF_8);
        save.invoke(store, new RoutingStore.State(updated, Map.of(name, "HK", "io.minclash.inventory.missing", "DIRECT")));
        start = SystemClock.elapsedRealtime();
        AppRouting.Screen second = loader.load(context, false, status);
        long warm = SystemClock.elapsedRealtime() - start;
        require(scans[0] == 1, "repeat open/save does not scan packages again");
        require(second.targets().contains("HK") && !second.targets().contains("US"), "targets never cached with inventory");
        require(second.applications().stream().anyMatch(app -> app.packageName().equals(name) && app.target().equals("HK")),
            "new personal policies reflected in cached inventory");
        require(second.applications().stream().anyMatch(app -> app.packageName().equals("io.minclash.inventory.missing")
            && !app.installed() && app.target().equals("DIRECT")), "missing packages remain editable");
        loader.invalidate();
        loader.load(context, false, status);
        require(scans[0] == 2, "permission/settings invalidation rescans packages");
        loader.load(context, true, status);
        require(scans[0] == 3, "Root preference changes invalidate inventory");
        System.out.println("Inventory: packages=" + first.applications().size() + ", coldMs=" + cold + ", cachedMs=" + warm);
    }

    private static void checkIcons(Context context, Handler main) throws Exception {
        AtomicReference<AppIconLoader> reference = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch ready = new CountDownLatch(1);
        main.post(() -> {
            try {
                AppIconLoader loader = new AppIconLoader(context);
                reference.set(loader);
                require(loader.get("android") == null, "no eager icon loading");
                loader.observe(() -> {
                    try {
                        AppIconLoader.Result icon = loader.get("android");
                        AppIconLoader.Result missing = loader.get("io.minclash.inventory.missing");
                        if (icon == null || missing == null) return;
                        require(icon.bitmap() != null, "visible icon loaded asynchronously");
                        require(missing.bitmap() == null && !missing.issue().isEmpty(), "failure cached with reason");
                    } catch (Throwable error) { failure.set(error); }
                    ready.countDown();
                });
                loader.request("android", false);
                loader.request("android", false);
                loader.request("io.minclash.inventory.missing", false);
                require(loader.get("android") == null, "request returns before decoding");
            } catch (Throwable error) { failure.set(error); ready.countDown(); }
        });
        require(ready.await(15, TimeUnit.SECONDS), "async icon timeout");
        if (failure.get() != null) throw new AssertionError(failure.get());
        CountDownLatch reloaded = new CountDownLatch(1);
        main.post(() -> {
            try {
                AppIconLoader loader = reference.get();
                loader.invalidate();
                require(loader.get("android") == null && loader.get("io.minclash.inventory.missing") == null,
                    "permission change invalidates success and failure caches");
                loader.observe(() -> {
                    if (loader.get("android") == null) return;
                    try { require(loader.get("android").bitmap() != null, "reload after invalidation"); }
                    catch (Throwable error) { failure.set(error); }
                    loader.close();
                    reloaded.countDown();
                });
                loader.request("android", false);
            } catch (Throwable error) { failure.set(error); reloaded.countDown(); }
        });
        require(reloaded.await(15, TimeUnit.SECONDS), "reload timeout");
        if (failure.get() != null) throw new AssertionError(failure.get());
    }

    private static void require(boolean condition, String name) {
        if (!condition) throw new AssertionError(name);
    }
}

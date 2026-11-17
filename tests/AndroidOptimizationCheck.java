package io.minclash.app;

import android.app.Activity;
import android.app.Application;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;

/** Same-certificate instrumentation. Network fixtures and preferences are isolated from user data. */
public final class AndroidOptimizationCheck extends Instrumentation {
    private final List<String> passed = new ArrayList<>();
    private Activity editor;
    private boolean diagnosticOnly;

    @Override public void onCreate(Bundle args) {
        super.onCreate(args);
        diagnosticOnly = args != null && "true".equals(args.getString("diagnostic-only"));
        start();
    }

    @Override public void onStart() {
        Bundle result = new Bundle();
        int code = 0;
        try {
            if (!diagnosticOnly) {
                inventory();
                snapshots();
                downloads();
                failedDraft();
            }
            var pool = Executors.newFixedThreadPool(5);
            try {
                DiagnosticReport report = DiagnosticReport.collect("0.2.6", 45, android.os.Build.VERSION.SDK_INT, null, pool);
                require(report.text().contains("MC-D3") && report.text().contains("BACKGROUND_NOT_CHECKED")
                    && report.text().contains("IPv6 目标"), "new diagnostic sections");
                if (diagnosticOnly) require(report.text().contains("APP_REJECT_RULE_FOUND"), "restored per-app firewall rule detected");
                result.putString("diagnostic", report.text());
                passed.add("live-diagnostic");
            } finally { pool.shutdownNow(); }
            result.putString("passed", String.join(",", passed));
        } catch (Throwable failure) {
            result.putString("error", failure.toString());
            result.putString("passed", String.join(",", passed));
            code = 1;
        } finally {
            if (editor != null) ui(() -> editor.finish());
        }
        finish(code, result);
    }

    private void ui(Runnable operation) {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        runOnMainSync(() -> { try { operation.run(); } catch (Throwable error) { failure.set(error); } });
        if (failure.get() != null) throw new AssertionError("UI check failed", failure.get());
    }

    private void inventory() throws Exception {
        ProtectionInventory inventory = new ProtectionInventory();
        List<ProtectionInventory.App> first = inventory.load(getTargetContext());
        require(!first.isEmpty() && inventory.load(getTargetContext()) == first, "one batched scan reused");
        inventory.invalidate();
        require(inventory.load(getTargetContext()) != first, "package change invalidates inventory");
        passed.add("batched-inventory-cache");
    }

    private void snapshots() throws Exception {
        String name = "OptimizationDeviceCheck-" + System.nanoTime();
        Context context = getTargetContext();
        try {
            var prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE);
            String address = "https://example.invalid/device-check";
            SubscriptionUsage newer = new SubscriptionUsage(1, 2, 100, 1893456000, 2000);
            SubscriptionUsage older = new SubscriptionUsage(2, 3, 100, 1794528000, 1000);
            newer.save(prefs, address);
            require(older.saveLatest(prefs, address).equals(newer)
                && SubscriptionUsage.load(prefs, address).equals(newer), "Android preferences keep newest snapshot");
            passed.add("usage-ordering-on-disk");
        } finally { context.deleteSharedPreferences(name); }
    }

    private void downloads() throws Exception {
        var executor = Executors.newSingleThreadExecutor();
        try (LocalHttp server = new LocalHttp()) {
            try (DownloadRequest request = new DownloadRequest(250)) {
                try { Subscription.download(server.url("/drip"), request); throw new AssertionError("deadline not enforced"); }
                catch (DownloadRequest.Expired expected) { }
            }
            try (DownloadRequest request = new DownloadRequest()) {
                RetainedTask<Subscription.Download> task = new RetainedTask<>();
                task.start(executor, () -> Subscription.download(server.url("/blocked"), request), request::cancel);
                require(server.blocked.await(3, TimeUnit.SECONDS), "body read started");
                require(task.cancel() && !task.running(), "cancel releases retained task");
                require(executor.submit(() -> Subscription.download(server.url("/ok")).source().length > 0)
                    .get(3, TimeUnit.SECONDS), "shared worker accepts next download");
                Thread.sleep(150);
                require(task.outcome() == null, "late cancelled result not published");
            }
            passed.add("android-download-deadline-cancel");
        } finally { executor.shutdownNow(); }
    }

    private void failedDraft() throws Exception {
        String before = new JSONObject(Module.run(Module.Command.PROTECTION_GET, null)).getString("digest");
        editor = startActivitySync(new Intent(getTargetContext(), ProtectionActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        awaitEditor();
        final String missing = "io.minclash.nonexistent.selfcheck";
        ui(() -> {
            try {
                Object model = field(editor, "model");
                ProtectionPolicy original = (ProtectionPolicy) field(model, "draft");
                ProtectionPolicy draft = ProtectionPolicy.parse(original.json());
                @SuppressWarnings("unchecked") List<String> targets = (List<String>) field(model, "targets");
                require(!targets.isEmpty(), "test needs an existing proxy group");
                draft.apps.add(new ProtectionPolicy.App(missing, 19998, targets.get(0)));
                ProtectionEdits edits = (ProtectionEdits) model;
                edits.draft = draft; edits.dirty = true;
                var apply = ProtectionActivity.class.getDeclaredMethod("apply", boolean.class);
                apply.setAccessible(true); apply.invoke(editor, false);
            } catch (Exception error) { throw new RuntimeException(error); }
        });
        awaitEditor();
        assertDraft(missing);
        require(new JSONObject(Module.run(Module.Command.PROTECTION_GET, null)).getString("digest").equals(before),
            "missing package rejected before saved root policy changes");

        Application application = (Application) getTargetContext().getApplicationContext();
        CountDownLatch recreated = new CountDownLatch(1);
        AtomicReference<Activity> replacement = new AtomicReference<>();
        Application.ActivityLifecycleCallbacks callbacks = new Application.ActivityLifecycleCallbacks() {
            public void onActivityCreated(Activity activity, Bundle saved) {
                if (activity instanceof ProtectionActivity && activity != editor) { replacement.set(activity); recreated.countDown(); }
            }
            public void onActivityStarted(Activity a) {}
            public void onActivityResumed(Activity a) {}
            public void onActivityPaused(Activity a) {}
            public void onActivityStopped(Activity a) {}
            public void onActivitySaveInstanceState(Activity a, Bundle b) {}
            public void onActivityDestroyed(Activity a) {}
        };
        application.registerActivityLifecycleCallbacks(callbacks);
        try {
            ui(() -> editor.recreate());
            require(recreated.await(8, TimeUnit.SECONDS), "editor recreated");
            editor = replacement.get(); awaitEditor(); assertDraft(missing);
            passed.add("failed-save-and-recreation-preserve-draft");
        } finally { application.unregisterActivityLifecycleCallbacks(callbacks); }
        ui(() -> editor.finish()); editor = null;
    }

    private void awaitEditor() throws Exception {
        long deadline = android.os.SystemClock.elapsedRealtime() + 25_000;
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            boolean[] ready = {false};
            ui(() -> {
                try {
                    Object model = field(editor, "model");
                    ready[0] = !(boolean) field(model, "busy") && ((ProtectionEdits) model).draft != null;
                } catch (Exception error) { throw new RuntimeException(error); }
            });
            if (ready[0]) return;
            Thread.sleep(100);
        }
        throw new AssertionError("protection editor did not finish reading");
    }

    private void assertDraft(String missing) {
        ui(() -> {
            try {
                ProtectionEdits edits = (ProtectionEdits) field(editor, "model");
                require(edits.dirty && edits.draft.apps.stream().anyMatch(app -> app.packageName().equals(missing)),
                    "failed save must keep actual Activity draft");
            } catch (Exception error) { throw new RuntimeException(error); }
        });
    }

    private static Object field(Object object, String name) throws Exception {
        for (Class<?> type = object.getClass(); type != null; type = type.getSuperclass()) {
            try { var field = type.getDeclaredField(name); field.setAccessible(true); return field.get(object); }
            catch (NoSuchFieldException next) { }
        }
        throw new NoSuchFieldException(name);
    }

    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }

    private static final class LocalHttp implements AutoCloseable {
        final ServerSocket listener;
        final CountDownLatch blocked = new CountDownLatch(1);
        final List<Socket> sockets = new ArrayList<>();
        volatile boolean closed;
        LocalHttp() throws IOException {
            listener = new ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"));
            Thread accept = new Thread(() -> {
                while (!closed) try {
                    Socket socket = listener.accept();
                    synchronized (sockets) { sockets.add(socket); }
                    Thread handler = new Thread(() -> serve(socket)); handler.setDaemon(true); handler.start();
                } catch (IOException stop) { break; }
            });
            accept.setDaemon(true); accept.start();
        }
        String url(String path) { return "http://127.0.0.1:" + listener.getLocalPort() + path; }
        void serve(Socket socket) {
            try (socket) {
                socket.setSoTimeout(3000);
                BufferedReader input = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
                String line = input.readLine();
                if (line == null) return;
                String header;
                while ((header = input.readLine()) != null && !header.isEmpty()) { }
                byte[] body = "rules: ['MATCH,DIRECT']\n".getBytes(StandardCharsets.UTF_8);
                boolean slow = line.contains("/drip") || line.contains("/blocked");
                socket.getOutputStream().write(("HTTP/1.0 200 OK\r\nContent-Length: " + (slow ? 100 : body.length) + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                if (line.contains("/blocked")) {
                    socket.getOutputStream().write('#'); socket.getOutputStream().flush(); blocked.countDown(); Thread.sleep(5000);
                } else if (slow) {
                    for (int i = 0; i < 100; i++) { socket.getOutputStream().write('#'); socket.getOutputStream().flush(); Thread.sleep(40); }
                } else socket.getOutputStream().write(body);
            } catch (IOException | InterruptedException done) { }
        }
        public void close() throws IOException {
            closed = true; listener.close();
            synchronized (sockets) { for (Socket socket : sockets) socket.close(); }
        }
    }
}

package io.minclash.app;

import android.content.Context;
import android.os.SystemClock;
import android.util.AtomicFile;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONObject;

/** Private subscription source and personal policies, independent of the generated core config. */
final class RoutingStore {
    // Serialize configuration changes across Activities and scheduled subscription updates.
    static final ExecutorService WORKER = Executors.newSingleThreadExecutor();
    private static volatile int generation;

    static int generation() { return generation; }

    static final class SelectionRestoreFailure extends IOException {
        SelectionRestoreFailure(Exception cause) {
            super("配置已保存，TUN 已恢复，但部分节点选择未能恢复。请检查代理组。", cause);
        }
    }

    record State(byte[] source, Map<String, String> applications, String subscription) {
        State(byte[] source, Map<String, String> applications) { this(source, applications, null); }

        State {
            source = source.clone();
            applications = Collections.unmodifiableMap(new TreeMap<>(applications));
        }
    }

    private final AtomicFile file;

    RoutingStore(Context context) {
        file = new AtomicFile(new File(context.getFilesDir(), "app-routing.json"));
    }

    State load() throws Exception {
        State saved = read();
        return saved == null ? new State(Profile.normalize(Module.readProfile()), Collections.emptyMap()) : saved;
    }

    void importProfile(byte[] source, String subscription) throws Exception {
        State saved = read();
        apply(new State(source, saved == null ? Collections.emptyMap() : saved.applications(), subscription));
    }

    boolean repairDns() throws Exception {
        byte[] installed = Module.readProfile();
        byte[] repaired = Profile.repairDns(installed);
        if (installed == repaired) return false;
        apply(repaired, null);
        return true;
    }

    String savedSubscription(String legacyAddress) throws Exception {
        JSONObject object = readObject();
        return object == null ? legacyAddress : object.optString("subscription", legacyAddress);
    }

    private State read() throws Exception {
        JSONObject object = readObject();
        if (object == null) return null;
        JSONObject rules = object.getJSONObject("applications");
        Map<String, String> applications = new TreeMap<>();
        for (Iterator<String> keys = rules.keys(); keys.hasNext();) {
            String name = keys.next();
            applications.put(name, rules.getString(name));
        }
        return new State(Profile.normalize(object.getString("source").getBytes(StandardCharsets.UTF_8)), applications,
            object.optString("subscription", null));
    }

    private JSONObject readObject() throws Exception {
        InputStream input;
        try { input = file.openRead(); }
        catch (FileNotFoundException missing) { return null; }
        try (input) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (bytes.size() + count > Profile.MAX_BYTES * 8) throw new IOException("应用分流设置过大。");
                bytes.write(buffer, 0, count);
            }
            JSONObject object = new JSONObject(bytes.toString(StandardCharsets.UTF_8.name()));
            if (object.getInt("version") != 1) throw new IOException("应用分流设置版本不受支持。");
            return object;
        }
    }

    private void save(State state) throws Exception {
        JSONObject object = new JSONObject();
        object.put("version", 1);
        object.put("source", new String(state.source(), StandardCharsets.UTF_8));
        object.put("applications", new JSONObject(state.applications()));
        object.put("subscription", state.subscription());
        byte[] bytes = object.toString().getBytes(StandardCharsets.UTF_8);
        FileOutputStream output = null;
        try {
            output = file.startWrite();
            output.write(bytes);
            file.finishWrite(output);
            generation++;
        } catch (Exception error) {
            if (output != null) file.failWrite(output);
            throw error;
        }
    }

    void apply(State state) throws Exception {
        apply(Profile.withAppRules(state.source(), state.applications()), state);
    }

    private void apply(byte[] merged, State state) throws Exception {
        Module.Status before = Module.status();
        byte[] previous = before.profile ? Module.readProfile() : null;
        if (before.enabled && Arrays.equals(merged, previous)) {
            if (state != null) save(state);
            return;
        }
        boolean reconnect = before.installed() && !before.state.equals("stopped");
        List<Controller.Group> selections = before.state.equals("running") && before.control
            ? Controller.groups().values() : Collections.emptyList();
        if (reconnect && before.validate) Module.run(Module.Command.VALIDATE, merged);
        if (reconnect) Module.run(Module.Command.STOP, null);
        boolean imported = false;
        try {
            Module.run(Module.Command.IMPORT, merged);
            imported = true;
            if (reconnect) restart(before.control);
            if (state != null) save(state);
        } catch (Exception error) {
            try {
                if (imported && reconnect) Module.run(Module.Command.STOP, null);
                if (imported && previous != null) Module.run(Module.Command.IMPORT, previous);
                if (reconnect && previous != null) {
                    restart(before.control);
                    Controller.restoreSelections(selections);
                }
            } catch (Exception recovery) {
                throw new IOException("应用配置失败，恢复也未完成，请检查内核日志。\n" + error.getMessage()
                    + "\n" + recovery.getMessage(), error);
            }
            if (imported && reconnect && previous != null) {
                throw new IOException("应用新配置失败，已恢复原配置和连接。\n" + error.getMessage(), error);
            }
            throw error;
        }
        if (reconnect) {
            try { Controller.restoreSelections(selections); }
            catch (Exception error) {
                throw new SelectionRestoreFailure(error);
            }
        }
    }

    private void restart(boolean control) throws Exception {
        Module.run(Module.Command.START, null);
        long deadline = SystemClock.elapsedRealtime() + 45_000;
        Exception last = null;
        do {
            Module.Status current = Module.status();
            if (current.state.equals("running")) {
                if (!control) return;
                try {
                    Controller.mode();
                    return;
                } catch (Exception error) {
                    last = error;
                }
            } else if (current.state.equals("retrying") || current.state.equals("stopped")
                || current.state.equals("orphaned") || !current.enabled) {
                break;
            }
            Thread.sleep(300);
        } while (SystemClock.elapsedRealtime() < deadline);
        throw new IOException("TUN 或内核控制接口未就绪。", last);
    }
}

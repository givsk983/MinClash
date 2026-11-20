package io.minclash.app;

import android.content.Context;
import android.content.res.Resources;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.NavigableMap;
import java.util.TreeMap;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Update notes are packaged with the App and keyed by module functional revision. */
final class ModuleReleaseNotes {
    private record Release(String version, String changes) {}
    static final ModuleReleaseNotes EMPTY = new ModuleReleaseNotes(new TreeMap<>());
    private final NavigableMap<Integer, Release> releases;

    private ModuleReleaseNotes(NavigableMap<Integer, Release> releases) {
        this.releases = Collections.unmodifiableNavigableMap(releases);
    }

    static ModuleReleaseNotes load(Context context) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
            context.getResources().openRawResource(R.raw.module_release_notes), StandardCharsets.UTF_8))) {
            StringBuilder json = new StringBuilder();
            for (String line; (line = reader.readLine()) != null;) json.append(line);
            return parse(json.toString());
        } catch (IOException | JSONException | Resources.NotFoundException error) {
            return EMPTY;
        }
    }

    static ModuleReleaseNotes parse(String json) throws JSONException {
        JSONArray entries = new JSONArray(json);
        NavigableMap<Integer, Release> releases = new TreeMap<>();
        for (int i = 0; i < entries.length(); i++) {
            JSONObject entry = entries.getJSONObject(i);
            int revision = entry.getInt("revision");
            String version = entry.getString("version").trim();
            JSONArray changes = entry.getJSONArray("changes");
            StringBuilder bullets = new StringBuilder();
            for (int j = 0; j < changes.length(); j++) {
                String change = changes.getString(j).trim();
                if (change.isEmpty()) continue;
                if (bullets.length() > 0) bullets.append('\n');
                bullets.append("• ").append(change);
            }
            if (revision <= 0 || version.isEmpty() || bullets.length() == 0
                || releases.putIfAbsent(revision, new Release(version, bullets.toString())) != null) {
                throw new JSONException("Invalid module release notes");
            }
        }
        return new ModuleReleaseNotes(releases);
    }

    String message(BundledModule.Info bundled, Module.Status status) {
        if (!bundled.valid()) return "内置模块版本信息暂不可用。";
        int target = bundled.revision();
        boolean pending = status != null && status.pendingFunctionalRevision() > 0;
        int current = status == null ? 0 : pending ? status.pendingFunctionalRevision() : status.functionalRevision();
        boolean update = status != null && (pending ? status.pendingUpdateAvailable(target) : status.updateAvailable(target));
        StringBuilder message = new StringBuilder("内置模块 " + bundled.version());
        if (update && current > 0) {
            if (pending) message.insert(0, "待生效模块 " + status.pendingVersion + " → ");
            else if (!status.version.isEmpty()) message.insert(0, "当前模块 " + status.version + " → ");
        }
        Release latest = releases.get(target);
        if (latest == null) return message + "\n\n此版本的更新内容暂不可用。";
        message.append("\n\n").append(latest.changes());
        int after = update && current > 0 ? current : target - 1;
        for (Release release : releases.subMap(after, false, target, false).descendingMap().values()) {
            message.append("\n\n").append(release.version()).append('\n').append(release.changes());
        }
        return message.toString();
    }
}

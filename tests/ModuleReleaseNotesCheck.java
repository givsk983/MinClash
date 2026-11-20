package io.minclash.app;

import java.nio.file.Files;
import java.nio.file.Path;

public final class ModuleReleaseNotesCheck {
    public static void main(String[] args) throws Exception {
        ModuleReleaseNotes notes = ModuleReleaseNotes.parse(
            Files.readString(Path.of("app/res/raw/module_release_notes.json")));
        BundledModule.Info bundled = new BundledModule.Info("0.1.6", 25, 15);
        String recent = notes.message(bundled, status("0.1.5", 14));
        require(recent.contains("当前模块 0.1.5 → 内置模块 0.1.6"), "shows upgrade versions");
        require(recent.contains("WebRTC") && !recent.contains("备用路由"), "one revision shows only its additions");

        String skipped = notes.message(bundled, status("0.1.2", 11));
        require(skipped.indexOf("WebRTC") < skipped.indexOf("备用路由")
            && skipped.indexOf("备用路由") < skipped.indexOf("0.1.4")
            && skipped.indexOf("0.1.4") < skipped.indexOf("0.1.3"), "skipped revisions shown newest first");
        require(!skipped.contains("轻量流量摘要"), "already installed changes excluded");

        String legacy = notes.message(bundled, status("0.0.2", 0));
        require(legacy.contains("地理规则数据") && !legacy.contains("提供 Android Root TUN"),
            "legacy metadata resolves to revision one");
        require(notes.message(bundled, null).equals(notes.message(bundled, status("0.1.6", 15))),
            "first install and current module show bundled release");
        require(notes.message(bundled, status("0.1.7", 16)).equals(notes.message(bundled, null)),
            "newer installed module does not show a downgrade as an update");
        require(notes.message(bundled, status("0.0.2", 0)).equals(notes.message(bundled,
            Module.parseStatus("state=stopped\nversion=0.0.2\ncontrol=1\npending=0.1.7\n"))),
            "notes always describe the bundled package, independent of a separately staged package");
        String scoped = notes.message(new BundledModule.Info("0.1.7", 26, 16), status("0.1.6", 15));
        require(scoped.contains("按应用规则") && !scoped.contains("规则、直连和全局模式"),
            "scoped WebRTC upgrade shows the change from the previous global option");
        String pending = notes.message(new BundledModule.Info("0.1.7", 26, 16),
            Module.parseStatus("state=missing\npending=0.1.6\npending_revision=15\n"));
        require(pending.contains("待生效模块 0.1.6 → 内置模块 0.1.7") && pending.contains("按应用规则"),
            "pre-reboot upgrade notes use the staged module, even on first install");
        require(notes.message(new BundledModule.Info("0.1.9", 28, 18), null).contains("暂不可用"),
            "unknown revisions do not show the preceding release's changes");
        System.out.println("PASS: module release notes, skipped revisions, legacy equivalence and fallback.");
    }

    private static Module.Status status(String version, int revision) throws Exception {
        return Module.parseStatus("state=stopped\ncontrol=1\nversion=" + version + "\nrevision=" + revision + "\n");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}

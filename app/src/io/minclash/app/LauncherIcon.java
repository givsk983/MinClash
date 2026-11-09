package io.minclash.app;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import java.util.ArrayList;
import java.util.List;

enum LauncherIcon {
    GRAPHITE("石墨灰", "LauncherGraphite", "ic_launcher"),
    BLACK("纯黑", "LauncherBlack", "ic_launcher_black"),
    GREEN("深墨绿", "LauncherGreen", "ic_launcher_green");

    final String label;
    final String drawable;
    private final String alias;

    LauncherIcon(String label, String alias, String drawable) {
        this.label = label;
        this.alias = alias;
        this.drawable = drawable;
    }

    private ComponentName component(Context context) {
        return new ComponentName(context.getPackageName(), context.getPackageName() + "." + alias);
    }

    static LauncherIcon current(Context context) {
        PackageManager packages = context.getPackageManager();
        for (LauncherIcon icon : values()) {
            if (packages.getComponentEnabledSetting(icon.component(context))
                == PackageManager.COMPONENT_ENABLED_STATE_ENABLED) return icon;
        }
        return GRAPHITE;
    }

    void select(Context context) {
        PackageManager packages = context.getPackageManager();
        int flags = PackageManager.DONT_KILL_APP;
        int enabled = PackageManager.COMPONENT_ENABLED_STATE_ENABLED;
        int disabled = PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
        if (Build.VERSION.SDK_INT >= 33) {
            List<PackageManager.ComponentEnabledSetting> changes = new ArrayList<>();
            for (LauncherIcon icon : values()) {
                changes.add(new PackageManager.ComponentEnabledSetting(
                    icon.component(context), icon == this ? enabled : disabled, flags));
            }
            packages.setComponentEnabledSettings(changes);
            return;
        }

        // Keep a launcher entry available if the older sequential update is interrupted.
        packages.setComponentEnabledSetting(component(context), enabled, flags);
        for (LauncherIcon icon : values()) {
            if (icon != this) packages.setComponentEnabledSetting(icon.component(context), disabled, flags);
        }
    }
}

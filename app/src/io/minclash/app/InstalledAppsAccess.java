package io.minclash.app;

import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.PermissionInfo;

final class InstalledAppsAccess {
    static final String RUNTIME_PERMISSION = "com.android.permission.GET_INSTALLED_APPS";

    static boolean supportsRequest(Context context) {
        try {
            PermissionInfo info = context.getPackageManager().getPermissionInfo(RUNTIME_PERMISSION, 0);
            return (info.protectionLevel & PermissionInfo.PROTECTION_MASK_BASE) == PermissionInfo.PROTECTION_DANGEROUS;
        } catch (PackageManager.NameNotFoundException unsupported) { return false; }
    }

    static boolean needsRequest(Context context) {
        return supportsRequest(context) && context.checkSelfPermission(RUNTIME_PERMISSION) != PackageManager.PERMISSION_GRANTED;
    }

    private InstalledAppsAccess() {}
}

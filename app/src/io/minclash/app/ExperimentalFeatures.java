package io.minclash.app;

import android.content.Context;
import java.io.IOException;
import java.util.Set;

/** Local opt-in only; hiding entry points never rewrites an existing network policy. */
final class ExperimentalFeatures {
    static final String KEY = "experimental_features";

    static boolean enabled(Context context) {
        return context.getSharedPreferences("MainActivity", Context.MODE_PRIVATE).getBoolean(KEY, false);
    }

    static void checkBypass(boolean enabled, Set<String> previous, Set<String> next) throws IOException {
        if (!enabled && !previous.containsAll(next)) {
            throw new IOException("绕过 TUN 属于开发中功能，请先在「设置 → 开发中功能」开启尝鲜，再应用此设置。");
        }
    }

    private ExperimentalFeatures() {}
}

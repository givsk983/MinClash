package io.minclash.app;

import android.content.ContextWrapper;
import java.io.File;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Collections;

/** Runs with app_process; uses only its dedicated temporary directory, never the active profile. */
public final class AndroidRoutingCheck {
    public static void main(String[] args) throws Exception {
        File directory = new File(args[0]);
        if (!directory.isDirectory() && !directory.mkdirs()) throw new AssertionError("test directory");
        ContextWrapper context = new ContextWrapper(null) {
            @Override public File getFilesDir() { return directory; }
        };
        byte[] source = Profile.normalize(("proxies: [{name: test, type: socks5, server: 127.0.0.1, port: 9}]\n"
            + "proxy-groups: [{name: US, type: select, proxies: [test]}]\n"
            + "rules: ['DOMAIN-SUFFIX,claude.ai,DIRECT', 'MATCH,DIRECT']\n").getBytes(StandardCharsets.UTF_8));
        RoutingStore store = new RoutingStore(context);
        Method save = RoutingStore.class.getDeclaredMethod("save", RoutingStore.State.class);
        save.setAccessible(true);
        save.invoke(store, new RoutingStore.State(source, Collections.singletonMap("com.anthropic.claude", "US")));
        RoutingStore.State read = new RoutingStore(context).load();
        require(read.applications().equals(Collections.singletonMap("com.anthropic.claude", "US")), "Android JSON/AtomicFile round trip");
        require(new String(read.source(), StandardCharsets.UTF_8).contains("DOMAIN-SUFFIX,claude.ai,DIRECT"), "base config survives independently");
        String effective = new String(Profile.withAppRules(read.source(), read.applications()), StandardCharsets.UTF_8);
        require(effective.indexOf("PROCESS-NAME,com.anthropic.claude,US") < effective.indexOf("DOMAIN-SUFFIX,claude.ai,DIRECT"), "Android priority");
        save.invoke(store, new RoutingStore.State(source, Collections.emptyMap()));
        RoutingStore.State cleared = new RoutingStore(context).load();
        require(cleared.applications().isEmpty(), "removing overrides survives reload");
        require(!new String(Profile.withAppRules(cleared.source(), cleared.applications()), StandardCharsets.UTF_8)
            .contains("PROCESS-NAME"), "removed override absent from generated config");
        System.out.println("PASS: Android persistence, independent source, personal priority and removal.");
    }

    private static void require(boolean condition, String name) {
        if (!condition) throw new AssertionError(name);
    }
}

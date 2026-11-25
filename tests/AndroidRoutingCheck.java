package io.minclash.app;

import android.content.ContextWrapper;
import android.content.SharedPreferences;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;

/** Runs with app_process; uses only its dedicated temporary directory, never the active profile. */
public final class AndroidRoutingCheck {
    private static final String LEGACY_SUBSCRIPTION = "https://first.example/config";

    public static void main(String[] args) throws Exception {
        File directory = new File(args[0]);
        if (!directory.isDirectory() && !directory.mkdirs()) throw new AssertionError("test directory");
        ContextWrapper context = new ContextWrapper(null) {
            @Override public File getFilesDir() { return directory; }
            @Override public File getCacheDir() { return directory; }
            @Override public SharedPreferences getSharedPreferences(String name, int mode) {
                return (SharedPreferences) Proxy.newProxyInstance(SharedPreferences.class.getClassLoader(),
                    new Class<?>[] {SharedPreferences.class}, (proxy, method, values) -> {
                        if (method.getName().equals("getString") && values[0].equals("subscription")) return LEGACY_SUBSCRIPTION;
                        if (method.getName().equals("getBoolean")) return values[1];
                        throw new UnsupportedOperationException(method.getName());
                    });
            }
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

        store.restoreSnapshot(RoutingStore.encodeState(new RoutingStore.State(source,
            Collections.singletonMap("com.anthropic.claude", "US"))).put("version", 1).toString().getBytes(StandardCharsets.UTF_8));
        RoutingStore.State original = store.forSubscription(LEGACY_SUBSCRIPTION);
        require(original != null && original.applications().get("com.anthropic.claude").equals("US"), "legacy rules assigned to the active subscription");
        String other = "https://second.example/config";
        require(store.forSubscription(other) == null, "unused subscription starts without copied rules");
        save.invoke(store, new RoutingStore.State(source, Collections.emptyMap(), other));
        require(store.load().applications().isEmpty() && store.forSubscription(LEGACY_SUBSCRIPTION).applications().equals(original.applications()),
            "previous rules remain scoped to their subscription");

        byte[] savedSubscriptions = store.snapshot();
        RoutingStore.State local = new RoutingStore.State(source, Collections.singletonMap("com.anthropic.claude", "DIRECT"), "",
            Collections.emptyList(), Collections.emptySet(), "first-local", "First YAML");
        save.invoke(store, local);
        save.invoke(store, new RoutingStore.State(source, Collections.emptyMap(), "",
            Collections.emptyList(), Collections.emptySet(), "second-local", "Second YAML"));
        require(store.localProfileEntries().size() == 2 && store.localProfileEntries().get(0).active(), "both local configurations are listed");
        require(store.forLocalProfile("first-local").applications().equals(local.applications()) && store.load().applications().isEmpty(),
            "local rules remain isolated from other YAML files");
        RoutingStore.SavedProfile firstLocal = store.savedProfile("first-local", false);
        RoutingStore.SavedProfile firstSubscription = store.savedProfile(LEGACY_SUBSCRIPTION, true);
        require(!firstLocal.active() && !firstSubscription.active(), "rule editor can address either saved profile while it is inactive");
        store.saveAppRouting(firstLocal, Map.of("com.anthropic.claude", new AppRouting.Choice("US", false)), false);
        store.saveAppRouting(firstSubscription, Map.of("com.example.browser", new AppRouting.Choice("DIRECT", false)), false);
        require(store.forLocalProfile("first-local").applications().equals(Map.of("com.anthropic.claude", "US"))
            && store.forSubscription(LEGACY_SUBSCRIPTION).applications().equals(Map.of("com.anthropic.claude", "US", "com.example.browser", "DIRECT"))
            && store.load().localId().equals("second-local") && store.load().applications().isEmpty(),
            "saving an inactive profile merges app rules in that profile and leaves the active profile alone");
        try {
            store.saveAppRouting(firstLocal, Map.of("com.anthropic.claude", new AppRouting.Choice("missing", false)), false);
            throw new AssertionError("an inactive profile cannot save a missing target");
        } catch (Profile.MissingRoutingTarget expected) {
            require(store.forLocalProfile("first-local").applications().equals(Map.of("com.anthropic.claude", "US")),
                "failed validation leaves the saved profile intact");
        }
        store.saveAppRouting(firstSubscription, Map.of("com.example.browser", new AppRouting.Choice("", false)), false);
        save.invoke(store, store.forSubscription(LEGACY_SUBSCRIPTION));
        require(store.load().applications().equals(original.applications()) && store.localProfileEntries().stream().noneMatch(RoutingStore.LocalProfile::active),
            "switching back restores rules and archives the active local configuration");
        try {
            store.saveAppRouting(firstSubscription, Map.of("com.example.browser", new AppRouting.Choice("DIRECT", false)), false);
            throw new AssertionError("an inactive edit must be reopened when the target becomes active");
        } catch (IOException expected) {
            require(expected.getMessage().contains("配置已变化"), "reject stale editor before applying to the live profile");
        }
        store.restoreSnapshot(savedSubscriptions);
        require(store.load().subscription().equals(other) && store.localProfileEntries().isEmpty()
            && store.forSubscription(LEGACY_SUBSCRIPTION).applications().equals(original.applications()), "routing snapshot restores active and inactive configurations together");
        System.out.println("PASS: Android profile migration, rule isolation, switching and snapshot recovery.");
    }

    private static void require(boolean condition, String name) {
        if (!condition) throw new AssertionError(name);
    }
}

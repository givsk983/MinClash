package io.minclash.app;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.yaml.snakeyaml.Yaml;

public final class LiveRoutingCheck {
    public static void main(String[] args) throws Exception {
        byte[] source = Profile.normalize("mode: rule\nrules: ['MATCH,DIRECT']\n".getBytes(StandardCharsets.UTF_8));
        RoutingStore.State previous = new RoutingStore.State(source, Map.of(), "subscription");
        RoutingStore.State changed = previous.withApplications(Map.of("com.example.app", "DIRECT"));
        byte[] installed = Profile.withPersonalRules(source, Map.of(), List.of());
        Module.Status ready = Module.parseStatus("state=running\nenabled=1\ncontrol=1\nvalidate=1\nhealth=1\ncore_running=1\ntun4=1\ncontroller_ready=1\n");
        require(RoutingStore.canReload(ready, previous, changed, installed), "same source and new app rules use live reload");
        require(RoutingStore.canReload(ready, previous, previous.withRules(List.of(PersonalRules.create("DOMAIN", "example.com", "DIRECT"))), installed), "personal rules also qualify");
        require(!RoutingStore.canReload(ready, previous, changed.withSource(source, "another"), installed), "subscription switch uses full apply");
        require(!RoutingStore.canReload(ready, previous, changed.withSource("rules: ['MATCH,REJECT']".getBytes(StandardCharsets.UTF_8), "subscription"), installed), "different source never uses fast path");
        require(!RoutingStore.canReload(ready, previous, changed, new byte[] {1}), "external edits never overwritten through fast path");
        require(!RoutingStore.canReload(ready, null, changed, installed), "legacy unsaved source uses full apply");
        for (String status : List.of("state=running\nenabled=1\ncontrol=1\nvalidate=1\n", "state=stopped\nenabled=1\n", "state=running\nenabled=1\nhealth=1\n"))
            require(!RoutingStore.canReload(Module.parseStatus(status), previous, changed, installed), "unready/legacy core uses old flow");

        byte[] merged = Profile.withAppRules(source, changed.applications());
        for (String mode : List.of("global", "direct", "rule")) {
            byte[] live = Profile.forLiveReload(merged, mode, true);
            Map<?, ?> parsed = new Yaml().load(new String(live, StandardCharsets.UTF_8));
            require(parsed.get("mode").equals(mode) && parsed.get("find-process-mode").equals("always"), "session mode and identification preserved");
            require(((List<?>) parsed.get("rules")).get(0).equals("PROCESS-NAME,com.example.app,DIRECT"), "new personal rule is first");
            Map<?, ?> persistent = new Yaml().load(new String(merged, StandardCharsets.UTF_8));
            require(persistent.get("mode").equals("rule"), "session override never changes saved source");
        }
        Map<?, ?> removing = new Yaml().load(new String(Profile.forLiveReload(source, "global", true), StandardCharsets.UTF_8));
        require(removing.get("find-process-mode").equals("always"), "temporary identification survives removing app rules");
        Map<?, ?> forced = new Yaml().load(new String(Profile.forLiveReload(merged, "rule", false), StandardCharsets.UTF_8));
        require(forced.get("find-process-mode").equals("always"), "app rules still force identification");
        try { Profile.forLiveReload(merged, "unknown", false); throw new AssertionError("invalid mode accepted"); }
        catch (IOException expected) { }
        for (String bad : List.of("mode: unknown\nrules: ['MATCH,DIRECT']\n", "find-process-mode: invalid\nrules: ['MATCH,DIRECT']\n")) {
            try { Profile.forLiveReload(Profile.normalize(bad.getBytes(StandardCharsets.UTF_8)), "rule", true);
                throw new AssertionError("session override concealed invalid persistent field"); }
            catch (IOException expected) { }
        }
        byte[] live = Profile.forLiveReload(merged, "global", true);
        byte[] packet = LiveProfile.packet(merged, live, installed);
        for (byte[] invalidSize : List.of(new byte[0], new byte[Profile.MAX_BYTES + 1])) {
            try { LiveProfile.packet(invalidSize, live, installed); throw new AssertionError("invalid frame size accepted"); }
            catch (Profile.TooLarge expected) { }
        }
        int end = 0;
        for (int line = 0; line < 3; line++) { while (packet[end++] != '\n') {} }
        require(Arrays.equals(Arrays.copyOfRange(packet, end, end + merged.length), merged)
            && Arrays.equals(Arrays.copyOfRange(packet, end + merged.length, packet.length), live), "packet frames UTF-8 YAML exactly");
        Files.createDirectories(Path.of("build/checks-0.2.3"));
        Files.writeString(Path.of("build/checks-0.2.3/live-script.sh"), LiveProfile.SCRIPT);

        Fake success = new Fake(""); RoutingStore.applyLive(success);
        require(success.steps.equals(List.of("reload", "verify", "selections", "reconnect", "commit")), "no stop/start in successful fast path; nodes before reconnect");
        for (String failure : List.of("reload", "verify", "reconnect", "commit")) {
            Fake change = new Fake(failure);
            try { RoutingStore.applyLive(change); throw new AssertionError("failure ignored"); }
            catch (IOException expected) { require(expected.getMessage().contains("已恢复"), "honest recovery status"); }
            require(change.steps.get(change.steps.size() - 1).equals("rollback"), "uncertain reload and commit failures recover");
            if (!failure.equals("commit")) require(!change.steps.contains("commit"), "never persist preference after failed apply");
        }
        Fake conflict = new Fake("unchanged");
        try { RoutingStore.applyLive(conflict); throw new AssertionError("conflict accepted"); }
        catch (LiveProfile.UnchangedFailure expected) { require(conflict.steps.equals(List.of("reload")), "CAS conflict leaves external state intact"); }
        Fake selection = new Fake("selections");
        try { RoutingStore.applyLive(selection); throw new AssertionError("selection failure hidden"); }
        catch (RoutingStore.SelectionRestoreFailure expected) { require(selection.steps.contains("commit") && !selection.steps.contains("rollback"), "saved config remains and selection warning is surfaced"); }
        Fake recovery = new Fake("rollback");
        try { RoutingStore.applyLive(recovery); throw new AssertionError("recovery failure hidden"); }
        catch (IOException expected) { require(expected.getMessage().contains("恢复也未完成"), "failed recovery is never called success"); }
        System.out.println("PASS: rules-only eligibility, legacy/external edit isolation, runtime mode/identification, framed payloads, apply/commit rollback and selection warnings.");
    }

    private static final class Fake implements RoutingStore.LiveChange {
        final String failure; final List<String> steps = new ArrayList<>();
        Fake(String failure) { this.failure = failure; }
        void step(String value) throws Exception {
            steps.add(value);
            if (failure.equals("unchanged") && value.equals("reload")) throw new LiveProfile.UnchangedFailure();
            if (failure.equals(value) || failure.equals("rollback") && value.equals("verify")) throw new IOException(value);
        }
        public void reload() throws Exception { step("reload"); }
        public void verify() throws Exception { step("verify"); }
        public void reconnect() throws Exception { step("reconnect"); }
        public void commit() throws Exception { step("commit"); }
        public void rollback() throws Exception { step("rollback"); }
        public void restoreSelections() throws Exception { step("selections"); }
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}

package io.minclash.app;

import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

public final class ProtectionPolicyCheck {
    public static void main(String[] args) throws Exception {
        ProtectionPolicy p = ProtectionPolicy.parse(new JSONObject("{\"version\":1,\"apps\":null}"));
        require(!p.enabled(), "default off");
        p.apps.add(new ProtectionPolicy.App("org.example.app", 10123, "Safe"));
        p.strictDns = true; p.dnsProxy = "Safe";
        ProtectionPolicy copy = ProtectionPolicy.parse(p.json());
        require(copy.apps.equals(p.apps) && copy.strictDns && !copy.bootstrap, "round trip");
        JSONObject request = new JSONObject(new String(p.request("expected"), StandardCharsets.UTF_8));
        require(request.getString("expected").equals("expected") && request.getJSONObject("policy").getJSONArray("apps").length()==1, "framed CAS request");
        for(String target : new String[]{"DIRECT","direct","GLOBAL","MINCLASH-STRICT-DNS","A#DIRECT","A\nB","PASS","DNS"})
            require(!ProtectionPolicy.safeTarget(target), "unsafe target: " + target);
        require(ProtectionPolicy.safeTarget("香港 自动选择"), "normal group");
        p.strictDns=false;p.bootstrap=true;
        require(!p.json().getBoolean("bootstrap"), "no implicit bootstrap exception");
        ProtectionPolicy shared = new ProtectionPolicy();
        shared.putApp(new ProtectionPolicy.App("org.example.first", 10123, "Safe"), java.util.List.of("Safe", "Other"));
        shared.putApp(new ProtectionPolicy.App("org.example.second", 10123, "Safe"), java.util.List.of("Safe", "Other"));
        reject(() -> shared.putApp(new ProtectionPolicy.App("org.example.second", 10123, "Other"), java.util.List.of("Safe", "Other")), "shared UID conflict");
        require(shared.apps.get(1).target().equals("Safe"), "rejected edit leaves prior draft intact");
        reject(() -> shared.validate(java.util.List.of("Other")), "removed proxy");
        require(!ProtectionPolicy.safeTarget("港".repeat(86)), "target limit counts UTF-8 bytes");
        ProtectionPolicy many = new ProtectionPolicy();
        for (int i = 0; i < ProtectionPolicy.MAX_APPS; i++) many.apps.add(new ProtectionPolicy.App("org.example.app" + i, 10100 + i, "Safe"));
        many.validate(java.util.List.of("Safe"));
        reject(() -> many.putApp(new ProtectionPolicy.App("org.example.extra", 10999, "Safe"), java.util.List.of("Safe")), "129th application");
        require(many.apps.size() == 128, "limit rejection preserves draft");
        ProtectionEdits edits = new ProtectionEdits();
        edits.draft = shared; edits.dirty = true;
        edits.finishApply(false); edits.acceptSnapshot(new ProtectionPolicy(), false);
        require(edits.dirty && edits.draft == shared, "failed root write and following refresh preserve edits");
        edits.finishApply(true); ProtectionPolicy saved = new ProtectionPolicy(); edits.acceptSnapshot(saved, true);
        require(!edits.dirty && edits.draft == saved, "confirmed save accepts fresh root snapshot");
        edits.draft = shared; edits.dirty = true;
        edits.finishApply(false, ProtectionPolicy.parse(shared.json()));
        require(edits.acceptSnapshot(ProtectionPolicy.parse(shared.json()), false) && !edits.dirty,
            "partial command failure recognizes an actually persisted policy without claiming runtime readiness");
        edits.draft = shared; edits.dirty = true;
        edits.finishApply(false, ProtectionPolicy.parse(shared.json()));
        shared.apps.add(new ProtectionPolicy.App("org.example.third", 10124, "Safe"));
        require(!edits.acceptSnapshot(saved, false) && edits.dirty && edits.draft == shared,
            "later edits survive reconciliation of earlier failed request");
        System.out.println("PASS: protection defaults, policy round trip, CAS framing, unsafe targets and explicit bootstrap.");
    }
    private interface Check { void run() throws Exception; }
    private static void reject(Check check, String message) throws Exception {
        try { check.run(); throw new AssertionError("accepted " + message); }
        catch (java.io.IOException expected) { }
    }
    private static void require(boolean value, String message) { if(!value)throw new AssertionError(message); }
}

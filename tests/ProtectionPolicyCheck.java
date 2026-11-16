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
        System.out.println("PASS: protection defaults, policy round trip, CAS framing, unsafe targets and explicit bootstrap.");
    }
    private static void require(boolean value, String message) { if(!value)throw new AssertionError(message); }
}

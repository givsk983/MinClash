package io.minclash.app;

import java.net.URI;

public final class ServiceProbeCheck {
    public static void main(String[] args) {
        expect(200, null, null, ServiceProbe.State.REACHABLE);
        expect(200, "challenge", null, ServiceProbe.State.CHALLENGE);
        expect(403, "challenge", null, ServiceProbe.State.CHALLENGE);
        expect(403, null, null, ServiceProbe.State.RESTRICTED);
        expect(451, null, null, ServiceProbe.State.RESTRICTED);
        expect(429, null, null, ServiceProbe.State.RATE_LIMITED);
        expect(401, null, null, ServiceProbe.State.SIGN_IN);
        expect(302, null, "https://accounts.google.com/signin", ServiceProbe.State.SIGN_IN);
        expect(302, null, "https://unrelated.example/", ServiceProbe.State.UNCONFIRMED);
        expect(302, null, "https://accounts.google.com.unrelated.example/", ServiceProbe.State.UNCONFIRMED);
        expect(404, null, null, ServiceProbe.State.UNCONFIRMED);
        expect(503, null, null, ServiceProbe.State.UNCONFIRMED);
        expect(-1, null, null, ServiceProbe.State.CONNECTION_FAILED);
        ServiceProbe.Site site = ServiceProbe.Site.CHATGPT;
        require(ServiceProbe.canFollow(site, ServiceProbe.redirectedTo(site.endpoint, "/try")), "same-site path");
        require(ServiceProbe.canFollow(site, URI.create("https://www.chatgpt.com/")), "www alias");
        require(!ServiceProbe.canFollow(site, URI.create("http://chatgpt.com/")), "HTTPS only");
        require(!ServiceProbe.canFollow(site, URI.create("https://chatgpt.com:8080/")), "default HTTPS port only");
        require(!ServiceProbe.canFollow(site, URI.create("https://other.example/")), "foreign origin rejected");
        require(!ServiceProbe.canFollow(site, URI.create("https://other.example@chatgpt.com/")), "userinfo rejected");
        require(ServiceProbe.redirectedTo(site.endpoint, "https://[invalid") == null, "invalid Location ignored");
        System.out.println("PASS: service status evidence, challenge handling and bounded same-site redirect policy.");
    }

    private static void expect(int status, String challenge, String location, ServiceProbe.State state) {
        URI redirect = location == null ? null : URI.create(location);
        require(ServiceProbe.classify(status, challenge, redirect).state() == state, "HTTP " + status + " maps to " + state);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}

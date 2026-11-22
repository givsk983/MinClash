package io.minclash.app;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.zip.GZIPOutputStream;

public final class SubscriptionUsageCheck {
    public static void main(String[] args) throws Exception {
        long now = 1794528000000L;
        SubscriptionUsage full = SubscriptionUsage.parse(" upload = 1024; DOWNLOAD=2048; total=4096; expire=1893456000 ", now);
        require(full.used() == 3072 && full.remaining() == 1024 && full.expire() == 1893456000, "bytes, seconds and case-insensitive fields");
        require(SubscriptionUsage.parse(null, now).used() == -1 && SubscriptionUsage.parse("", now).total() == -1, "absent metadata is unknown");
        require(SubscriptionUsage.parse("upload=0; download=0; total=0; expire=0", now).used() == 0, "explicit zero usage remains zero");
        require(SubscriptionUsage.parse("expire=0", now).expire() == -1, "zero expiry not shown as 1970");
        require(SubscriptionUsage.parse("upload=100;download=200;total=0", now).remaining() == -1,
            "ambiguous zero quota does not claim exhausted or unlimited");
        require(SubscriptionUsage.parse("upload=1; total=100", now).used() == -1, "missing download not fabricated");
        require(SubscriptionUsage.parse("upload=1; download=99; total=20", now).remaining() == 0, "over-quota remaining is clamped");
        require(SubscriptionUsage.parse("upload=-1;download=nan;total=1e6;expire=9999999999999999999", now).used() == -1, "malformed numeric values rejected");
        require(SubscriptionUsage.parse("upload=9223372036854775807;download=1", now).used() == -1, "overflow cannot become negative or zero usage");
        require(SubscriptionUsage.parse("upload=1;upload=2;download=0", now).used() == -1, "ambiguous duplicate fields rejected");
        require(SubscriptionUsage.parse("expire=253402300800", now).expire() == -1, "unformattable expiry rejected");
        require(full.detail(now).contains("3.0 KiB") && full.detail(now).contains("快照"), "display uses binary units and freshness explanation");
        require(SubscriptionUsage.parse("expire=1", now).detail(now).contains("已到期"), "expired plan clearly identified");
        android.content.SharedPreferences broken = (android.content.SharedPreferences) java.lang.reflect.Proxy.newProxyInstance(
            SubscriptionUsageCheck.class.getClassLoader(), new Class<?>[] {android.content.SharedPreferences.class},
            (proxy, method, arguments) -> { throw new IllegalStateException("preference storage unavailable"); });
        require(!full.cache(broken, "https://example.test/private"), "optional usage caching never blocks configuration import");
        checkNames();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        byte[] yaml = "rules: ['MATCH,DIRECT']\n".getBytes(StandardCharsets.UTF_8);
        server.createContext("/redirect", exchange -> {
            exchange.getResponseHeaders().add("Location", "/final");
            exchange.getResponseHeaders().add("Subscription-Userinfo", "upload=99;download=99;total=999");
            exchange.getResponseHeaders().add("Profile-Title", "Redirect title");
            exchange.sendResponseHeaders(302, -1); exchange.close();
        });
        server.createContext("/final", exchange -> {
            exchange.getResponseHeaders().add("subscription-userinfo", "upload=1024;download=2048;total=4096;expire=1893456000");
            exchange.getResponseHeaders().add("content-disposition", "attachment; filename*=UTF-8''%E4%BA%91%E8%88%AA.yaml");
            exchange.sendResponseHeaders(200, yaml.length); exchange.getResponseBody().write(yaml); exchange.close();
        });
        server.createContext("/missing", exchange -> {
            exchange.sendResponseHeaders(200, yaml.length); exchange.getResponseBody().write(yaml); exchange.close();
        });
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(compressed)) { gzip.write(yaml); }
        byte[] zipped = compressed.toByteArray();
        server.createContext("/gzip", exchange -> {
            exchange.getResponseHeaders().add("Content-Encoding", "gzip");
            exchange.sendResponseHeaders(200, zipped.length); exchange.getResponseBody().write(zipped); exchange.close();
        });
        server.createContext("/headers-only", exchange -> {
            exchange.getResponseHeaders().add("Subscription-Userinfo", "upload=1;download=2;total=10");
            exchange.getResponseHeaders().add("Profile-Title", "Header title");
            byte[] body = "this is not YAML".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body); exchange.close();
        });
        server.createContext("/loop", exchange -> {
            exchange.getResponseHeaders().add("Location", "/loop"); exchange.sendResponseHeaders(302, -1); exchange.close();
        });
        server.createContext("/no-location", exchange -> { exchange.sendResponseHeaders(302, -1); exchange.close(); });
        byte[] bomb;
        compressed.reset();
        try (GZIPOutputStream gzip = new GZIPOutputStream(compressed)) { gzip.write(new byte[Profile.MAX_BYTES + 1]); }
        bomb = compressed.toByteArray();
        server.createContext("/bomb", exchange -> {
            exchange.getResponseHeaders().add("Content-Encoding", "gzip"); exchange.sendResponseHeaders(200, bomb.length);
            exchange.getResponseBody().write(bomb); exchange.close();
        });
        server.start();
        try {
            String address = "http://127.0.0.1:" + server.getAddress().getPort();
            Subscription.Download downloaded = Subscription.download(address + "/redirect");
            require(downloaded.usage().used() == 3072 && downloaded.usage().total() == 4096, "only final response metadata is used");
            require(downloaded.name().equals("云航"), "only the final response supplies the subscription name");
            require(new String(downloaded.source(), StandardCharsets.UTF_8).contains("MATCH,DIRECT"), "normal configuration download preserved");
            require(Subscription.download(address + "/missing").usage().checkedAt() > 0
                && Subscription.download(address + "/missing").usage().used() == -1, "missing final header yields fresh unavailable result");
            require(new String(Subscription.download(address + "/gzip").source(), StandardCharsets.UTF_8).contains("MATCH,DIRECT"), "gzip config decompressed before validation");
            require(Subscription.usage(address + "/headers-only").used() == 3, "usage refresh independent of YAML validity");
            require(Subscription.lookupName(address + "/headers-only", "127.0.0.1").equals("Header title"),
                "save-only name lookup does not validate the body");
            require(Subscription.lookupName(address + "/missing?token=secret", "127.0.0.1").equals("127.0.0.1"),
                "missing metadata falls back to the host");
            require(Subscription.lookupName(address + "/no-location", "127.0.0.1").equals("127.0.0.1"),
                "failed lookup still gives a name for offline saving");
            for (String path : new String[] {"/loop", "/no-location", "/bomb"}) {
                try { Subscription.download(address + path); throw new AssertionError("accepted " + path); }
                catch (java.io.IOException expected) { }
            }
            byte[] bom = new byte[yaml.length + 3]; bom[0] = (byte) 0xef; bom[1] = (byte) 0xbb; bom[2] = (byte) 0xbf;
            System.arraycopy(yaml, 0, bom, 3, yaml.length);
            require(new String(Profile.normalize(bom), StandardCharsets.UTF_8).contains("MATCH,DIRECT"), "UTF-8 BOM configuration");
            require(new SubscriptionUsage(-2, -3, -4, Long.MAX_VALUE, -1).expire() == -1, "corrupt cached expiration clamped");
        } finally { server.stop(0); }
        System.out.println("PASS: subscription usage, title and filename parsing, host fallback, and final response metadata.");
    }

    private static void checkNames() {
        String host = "sub.example.test";
        String title = "云航 ✈";
        String encodedTitle = "base64:" + Base64.getEncoder().encodeToString(title.getBytes(StandardCharsets.UTF_8));
        require(SubscriptionName.fromHeaders(encodedTitle, "attachment; filename=Other.yaml", host).equals(title),
            "UTF-8 title takes precedence over filename");
        require(SubscriptionName.fromHeaders("  Provider title  ", null, host).equals("Provider title"), "plain title");
        require(SubscriptionName.fromHeaders("base64:@@", "attachment; filename=Other.yml; filename*=UTF-8'zh'%E4%BA%91+A.yaml", host)
            .equals("云+A"), "extended filename takes precedence and preserves literal plus");
        require(SubscriptionName.fromHeaders(null, "attachment; filename*=UTF-8''%FF; filename=\"Air; Line.YAML\"", host)
            .equals("Air; Line"), "malformed UTF-8 falls back to the quoted filename");
        require(SubscriptionName.fromHeaders(null, "attachment; note=\"ignored; filename=Other.yaml\"; filename=config.yaml", host)
            .equals(host), "nested parameters and generic filenames do not become titles");
        require(SubscriptionName.fromHeaders("base64:QQpC", "attachment; filename*=UTF-8''folder%2FAir.yaml", host).equals(host),
            "multiline titles and paths fall back to the host");
    }

    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}

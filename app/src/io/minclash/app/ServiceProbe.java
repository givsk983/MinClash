package io.minclash.app;

import java.io.IOException;
import java.net.Proxy;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.UnknownHostException;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLException;

/** A credential-free check of a service's public entry point via Android's active routing. */
final class ServiceProbe {
    enum Site {
        CHATGPT("ChatGPT Web", "https://chatgpt.com/"),
        CLAUDE("Claude", "https://claude.ai/"),
        GEMINI("Gemini", "https://gemini.google.com/app"),
        MUSE("Muse AI", "https://muse.ai/"),
        BILIBILI("哔哩哔哩", "https://www.bilibili.com/"),
        BAHAMUT("巴哈姆特动画疯", "https://ani.gamer.com.tw/"),
        NETFLIX("Netflix", "https://www.netflix.com/"),
        DISNEY("Disney+", "https://www.disneyplus.com/"),
        PRIME_VIDEO("Prime Video", "https://www.primevideo.com/"),
        YOUTUBE_PREMIUM("YouTube Premium", "https://www.youtube.com/premium"),
        SPOTIFY("Spotify", "https://open.spotify.com/"),
        TIKTOK("TikTok", "https://www.tiktok.com/");

        final String label;
        final URI endpoint;

        Site(String label, String endpoint) {
            this.label = label;
            this.endpoint = URI.create(endpoint);
        }
    }

    enum State {
        REACHABLE("入口可达"), RESTRICTED("访问受限"), SIGN_IN("需要登录"),
        CHALLENGE("需要浏览器验证"), RATE_LIMITED("访问限流"),
        UNCONFIRMED("暂无法确认"), CONNECTION_FAILED("连接失败");

        final String label;
        State(String label) { this.label = label; }
    }

    record Result(State state, String detail) {
        String summary() { return state.label + " · " + detail; }
    }

    private static final int TIMEOUT_MS = 4000;
    private static final int MAX_REDIRECTS = 2;

    static Result run(Site site) {
        URI endpoint = site.endpoint;
        try {
            for (int redirects = 0; ; redirects++) {
                if (Thread.currentThread().isInterrupted()) return failed("测试已停止");
                // The module owns routing. Skip the Java HTTP proxy selector so a system proxy
                // cannot silently substitute another route for the app's normal TUN path.
                HttpsURLConnection connection = (HttpsURLConnection) endpoint.toURL().openConnection(Proxy.NO_PROXY);
                try {
                    connection.setConnectTimeout(TIMEOUT_MS);
                    connection.setReadTimeout(TIMEOUT_MS);
                    connection.setUseCaches(false);
                    connection.setInstanceFollowRedirects(false);
                    connection.setRequestProperty("User-Agent", "MinClash");
                    connection.setRequestProperty("Connection", "close");
                    int code = connection.getResponseCode();
                    String mitigation = connection.getHeaderField("cf-mitigated");
                    URI redirect = redirectedTo(endpoint, connection.getHeaderField("Location"));
                    Result result = classify(code, mitigation, redirect);
                    if (result.state() != State.UNCONFIRMED || !isRedirect(code)
                        || redirects >= MAX_REDIRECTS || !canFollow(site, redirect)) return result;
                    endpoint = redirect;
                } finally {
                    connection.disconnect();
                }
            }
        } catch (UnknownHostException error) { return failed("DNS 解析失败"); }
        catch (SocketTimeoutException error) { return failed("请求超时"); }
        catch (SSLException error) { return failed("TLS 握手失败"); }
        catch (IOException | SecurityException error) { return failed("未收到 HTTPS 响应"); }
    }

    static Result classify(int status, String mitigation, URI redirect) {
        if (status < 100) return failed("未收到 HTTPS 响应");
        String reason = "HTTP " + status;
        State state;
        if ("challenge".equalsIgnoreCase(mitigation)) state = State.CHALLENGE;
        else if (status >= 200 && status < 300) state = State.REACHABLE;
        else if (status == 401 || isRedirect(status) && signInHost(redirect)) state = State.SIGN_IN;
        else if (status == 403 || status == 451) state = State.RESTRICTED;
        else if (status == 429) state = State.RATE_LIMITED;
        else state = State.UNCONFIRMED;
        return new Result(state, reason);
    }

    static URI redirectedTo(URI endpoint, String location) {
        if (location == null || location.trim().isEmpty()) return null;
        try { return endpoint.resolve(location.trim()); }
        catch (IllegalArgumentException error) { return null; }
    }

    static boolean canFollow(Site site, URI redirect) {
        if (!isHttps(redirect) || redirect.getHost() == null) return false;
        // Limit follow-up requests to the tested service's origin and its www alias.
        String host = site.endpoint.getHost();
        String bare = host.startsWith("www.") ? host.substring(4) : host;
        return host.equalsIgnoreCase(redirect.getHost())
            || bare.equalsIgnoreCase(redirect.getHost())
            || ("www." + bare).equalsIgnoreCase(redirect.getHost());
    }

    private static boolean isHttps(URI uri) {
        return uri != null && "https".equalsIgnoreCase(uri.getScheme()) && uri.getUserInfo() == null
            && (uri.getPort() == -1 || uri.getPort() == 443);
    }

    private static boolean signInHost(URI uri) {
        if (!isHttps(uri) || uri.getHost() == null) return false;
        return "accounts.google.com".equalsIgnoreCase(uri.getHost())
            || "auth.openai.com".equalsIgnoreCase(uri.getHost());
    }

    private static boolean isRedirect(int status) {
        return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
    }

    private static Result failed(String detail) {
        return new Result(State.CONNECTION_FAILED, detail);
    }

    private ServiceProbe() {}
}

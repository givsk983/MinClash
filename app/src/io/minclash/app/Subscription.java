package io.minclash.app;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.UnknownHostException;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import javax.net.ssl.SSLException;

final class Subscription {
    static final String PROXY_HINT = "如果该订阅需要代理，请先连接可用代理再重试。也可以下载 Clash / Mihomo YAML 后从本地导入。";

    static final class NetworkFailure extends IOException {
        enum Kind { DNS, TIMEOUT, CONNECT }
        final Kind kind;

        private NetworkFailure(Kind kind, String reason) {
            super(reason + "\n\n" + PROXY_HINT);
            this.kind = kind;
        }
    }

    static String host(String address) throws IOException { return parse(null, address).getHost(); }

    static byte[] fetch(String address) throws IOException {
        return download(address).source();
    }

    record Download(byte[] source, SubscriptionUsage usage) {}

    static Download download(String address) throws IOException {
        return response(address, true);
    }

    static SubscriptionUsage usage(String address) throws IOException {
        return response(address, false).usage();
    }

    private static Download response(String address, boolean includeConfig) throws IOException {
        URL url = parse(null, address);
        Set<String> visited = new HashSet<>();
        for (int redirects = 0; redirects <= 5; redirects++) {
            if (!visited.add(url.toExternalForm())) throw new IOException("订阅服务器出现循环重定向，请检查服务商链接。");
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            try {
                connection.setConnectTimeout(12000);
                connection.setReadTimeout(12000);
                connection.setInstanceFollowRedirects(false);
                connection.setRequestProperty("User-Agent", "clash.meta/1.19.33");
                connection.setRequestProperty("Accept-Encoding", "gzip, identity");
                int status;
                try {
                    status = connection.getResponseCode();
                } catch (IOException error) {
                    throw downloadError(error);
                }
                if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                    URL next;
                    try { next = parse(url, connection.getHeaderField("Location")); }
                    catch (IOException invalid) { throw new IOException("订阅服务器返回了无效的重定向地址。"); }
                    if (url.getProtocol().equals("https") && next.getProtocol().equals("http")) {
                        throw new IOException("订阅从 HTTPS 重定向到了 HTTP，请使用服务商提供的 HTTPS 链接。");
                    }
                    url = next;
                    continue;
                }
                if (status != 200) throw new IOException("订阅服务器返回 HTTP " + status + "，请检查链接是否有效。");
                SubscriptionUsage usage = SubscriptionUsage.parse(connection.getHeaderField("Subscription-Userinfo"), System.currentTimeMillis());
                if (!includeConfig) return new Download(null, usage);
                if (connection.getContentLengthLong() > Profile.MAX_BYTES) {
                    throw new IOException("订阅配置最大为 2 MiB。");
                }
                byte[] content;
                String encoding = connection.getContentEncoding();
                if (encoding != null && !encoding.equalsIgnoreCase("identity") && !encoding.equalsIgnoreCase("gzip")) {
                    throw new IOException("订阅使用了不支持的压缩格式，请使用 Clash / Mihomo 订阅链接。");
                }
                try (InputStream raw = connection.getInputStream();
                     InputStream input = "gzip".equalsIgnoreCase(encoding) ? new GZIPInputStream(raw) : raw) {
                    content = Profile.readLimited(input);
                } catch (Profile.TooLarge error) {
                    throw error;
                } catch (IOException error) {
                    throw downloadError(error);
                }
                try {
                    return new Download(Profile.normalize(content), usage);
                } catch (IOException error) {
                    throw new IOException("无法导入订阅，请使用机场提供的 Clash / Mihomo YAML 链接。\n"
                        + error.getMessage());
                }
            } finally {
                connection.disconnect();
            }
        }
        throw new IOException("订阅链接重定向次数过多。");
    }

    private static URL parse(URL base, String address) throws IOException {
        if (address != null && !address.trim().isEmpty()) {
            try {
                URL url = new URL(base, address.trim());
                if ((url.getProtocol().equals("https") || url.getProtocol().equals("http"))
                    && !url.getHost().isEmpty() && url.getUserInfo() == null) return url;
            } catch (MalformedURLException ignored) {
                // Keep subscription tokens out of error messages.
            }
        }
        throw new IOException("请输入有效的 HTTP 或 HTTPS 订阅链接。");
    }

    private static IOException downloadError(IOException error) {
        if (error instanceof SSLException) {
            return new IOException("订阅服务器的 TLS 连接失败。请检查手机时间、服务器证书和订阅链接。");
        }
        if (error instanceof UnknownHostException) return new NetworkFailure(NetworkFailure.Kind.DNS, "无法解析订阅服务器域名。请检查网络和 DNS。");
        if (error instanceof SocketTimeoutException) return new NetworkFailure(NetworkFailure.Kind.TIMEOUT, "下载订阅超时。请检查当前网络。");
        if (error instanceof SocketException) return new NetworkFailure(NetworkFailure.Kind.CONNECT, "无法连接订阅服务器或连接已中断。请检查当前网络。");
        return new IOException("无法读取订阅。请检查网络和订阅链接。");
    }

    private Subscription() {}
}

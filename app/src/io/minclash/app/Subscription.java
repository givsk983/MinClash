package io.minclash.app;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.UnknownHostException;
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
        URL url = parse(null, address);
        for (int redirects = 0; redirects <= 5; redirects++) {
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            try {
                connection.setConnectTimeout(12000);
                connection.setReadTimeout(12000);
                connection.setInstanceFollowRedirects(false);
                connection.setRequestProperty("User-Agent", "clash.meta/1.19.33");
                int status;
                try {
                    status = connection.getResponseCode();
                } catch (IOException error) {
                    throw downloadError(error);
                }
                if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                    URL next = parse(url, connection.getHeaderField("Location"));
                    if (url.getProtocol().equals("https") && next.getProtocol().equals("http")) {
                        throw new IOException("订阅从 HTTPS 重定向到了 HTTP，请使用服务商提供的 HTTPS 链接。");
                    }
                    url = next;
                    continue;
                }
                if (status != 200) throw new IOException("订阅服务器返回 HTTP " + status + "，请检查链接是否有效。");
                if (connection.getContentLengthLong() > Profile.MAX_BYTES) {
                    throw new IOException("订阅配置最大为 2 MiB。");
                }
                byte[] content;
                try (InputStream input = connection.getInputStream()) {
                    content = Profile.readLimited(input);
                } catch (Profile.TooLarge error) {
                    throw error;
                } catch (IOException error) {
                    throw downloadError(error);
                }
                try {
                    return Profile.normalize(content);
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

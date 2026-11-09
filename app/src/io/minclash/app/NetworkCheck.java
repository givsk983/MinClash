package io.minclash.app;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.HttpsURLConnection;

enum NetworkCheck {
    GOOGLE("Google", "https://www.google.com/generate_204", "GET", 204),
    BAIDU("百度", "https://www.baidu.com/", "HEAD", 200),
    CLOUDFLARE("Cloudflare", "https://www.cloudflare.com/cdn-cgi/trace", "GET", 200);

    enum State { REACHABLE, TIMEOUT, UNREACHABLE, HTTP_ERROR }
    private record Sample(State state, long latencyMs, int responseCode) {}
    record Result(State state, long medianMs, int responseCode) {}

    private static final int SAMPLE_COUNT = 3;
    private static final int SOCKET_TIMEOUT_MS = 5000;
    final String label;
    private final URI endpoint;
    private final String method;
    private final int expectedStatus;

    NetworkCheck(String label, String endpoint, String method, int expectedStatus) {
        this.label = label;
        this.endpoint = URI.create(endpoint);
        this.method = method;
        this.expectedStatus = expectedStatus;
    }

    Result run() {
        long[] latencies = new long[SAMPLE_COUNT];
        int samples = 0;
        for (int attempt = 0; attempt < SAMPLE_COUNT; attempt++) {
            Sample sample = sample();
            if (sample.state() != State.REACHABLE) {
                if (samples == 0) return new Result(sample.state(), 0, sample.responseCode());
                break;
            }
            latencies[samples++] = sample.latencyMs();
        }
        Arrays.sort(latencies, 0, samples);
        long median = (latencies[(samples - 1) / 2] + latencies[samples / 2]) / 2;
        return new Result(State.REACHABLE, median, expectedStatus);
    }

    private Sample sample() {
        HttpsURLConnection connection = null;
        try {
            connection = (HttpsURLConnection) endpoint.toURL().openConnection();
            connection.setRequestMethod(method);
            connection.setConnectTimeout(SOCKET_TIMEOUT_MS);
            connection.setReadTimeout(SOCKET_TIMEOUT_MS);
            connection.setUseCaches(false);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("User-Agent", "MinClash");
            connection.setRequestProperty("Connection", "close");
            long started = System.nanoTime();
            int code = connection.getResponseCode();
            if (code == expectedStatus) {
                long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
                return new Sample(State.REACHABLE, Math.max(1, elapsed), code);
            }
            return new Sample(code > 0 ? State.HTTP_ERROR : State.UNREACHABLE, 0, code);
        } catch (SocketTimeoutException error) {
            return new Sample(State.TIMEOUT, 0, 0);
        } catch (IOException error) {
            return new Sample(State.UNREACHABLE, 0, 0);
        } finally {
            if (connection != null) connection.disconnect();
        }
    }
}

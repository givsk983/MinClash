package io.minclash.app;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/** Manual, paired destination-family checks. The current TUN/rules still decide the actual egress. */
final class IpFamilyCheck {
    enum Stage { CONNECT, TLS, HTTP }
    enum State { REACHABLE, CONNECT_FAILED, CONNECT_TIMEOUT, TLS_FAILED, TLS_TIMEOUT, HTTP_FAILED, HTTP_TIMEOUT }
    record Result(State state, long latencyMs) {}

    static Result run(boolean ipv6) {
        Stage stage = Stage.CONNECT;
        long started = System.nanoTime();
        long deadline = started + TimeUnit.SECONDS.toNanos(10);
        String address = ipv6 ? "2606:4700:4700::1111" : "1.1.1.1";
        try (Socket raw = new Socket()) {
            raw.connect(new InetSocketAddress(InetAddress.getByName(address), 443), 4000);
            raw.setSoTimeout(4000);
            stage = Stage.TLS;
            try (SSLSocket tls = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault())
                    .createSocket(raw, "one.one.one.one", 443, true)) {
                tls.setSoTimeout(4000);
                SSLParameters parameters = tls.getSSLParameters();
                parameters.setEndpointIdentificationAlgorithm("HTTPS");
                tls.setSSLParameters(parameters);
                tls.startHandshake();
                stage = Stage.HTTP;
                tls.getOutputStream().write(("GET /cdn-cgi/trace HTTP/1.1\r\nHost: one.one.one.one\r\n"
                    + "Connection: close\r\nUser-Agent: MinClash\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                InputStream input = tls.getInputStream();
                StringBuilder status = new StringBuilder();
                for (int i = 0; i < 1024; i++) {
                    if (System.nanoTime() - deadline >= 0) throw new SocketTimeoutException();
                    int next = input.read();
                    if (next == -1 || next == '\n') break;
                    if (next != '\r') status.append((char) next);
                }
                if (!status.toString().matches("HTTP/1\\.[01] 200(?: .*)?")) return new Result(State.HTTP_FAILED, 0);
                return new Result(State.REACHABLE, Math.max(1, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)));
            }
        } catch (IOException failure) {
            return new Result(failure(stage, failure), 0);
        }
    }

    static State failure(Stage stage, IOException error) {
        boolean timeout = error instanceof SocketTimeoutException;
        return switch (stage) {
            case CONNECT -> timeout ? State.CONNECT_TIMEOUT : State.CONNECT_FAILED;
            case TLS -> timeout ? State.TLS_TIMEOUT : State.TLS_FAILED;
            case HTTP -> timeout ? State.HTTP_TIMEOUT : error instanceof SSLException ? State.TLS_FAILED : State.HTTP_FAILED;
        };
    }

    private IpFamilyCheck() {}
}

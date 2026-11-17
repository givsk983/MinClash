package io.minclash.app;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class DownloadRequestCheck {
    public static void main(String[] args) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var handlers = Executors.newCachedThreadPool(); server.setExecutor(handlers);
        CountDownLatch blocked = new CountDownLatch(1);
        server.createContext("/drip", exchange -> {
            try (exchange) {
                exchange.sendResponseHeaders(200, 0);
                for (int i = 0; i < 100; i++) {
                    exchange.getResponseBody().write('#'); exchange.getResponseBody().flush();
                    try { Thread.sleep(30); } catch (InterruptedException stop) { break; }
                }
            } catch (IOException closed) { }
        });
        server.createContext("/blocked", exchange -> {
            try (exchange) {
                exchange.sendResponseHeaders(200, 0);
                exchange.getResponseBody().write('#'); exchange.getResponseBody().flush(); blocked.countDown();
                try { Thread.sleep(5000); } catch (InterruptedException stop) { }
            } catch (IOException closed) { }
        });
        server.createContext("/redirect", exchange -> {
            try (exchange) {
                try { Thread.sleep(150); } catch (InterruptedException stop) { }
                exchange.getResponseHeaders().set("Location", "/drip"); exchange.sendResponseHeaders(302, -1);
            }
        });
        server.createContext("/valid", exchange -> {
            byte[] yaml = "rules: ['MATCH,DIRECT']\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            try (exchange) { exchange.sendResponseHeaders(200, yaml.length); exchange.getResponseBody().write(yaml); }
        });
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        var worker = Executors.newSingleThreadExecutor();
        try {
            slowHeaders();
            for (String path : new String[]{"/drip", "/redirect"}) {
                long started = System.nanoTime();
                try (DownloadRequest request = new DownloadRequest(250)) {
                    try { Subscription.download(base + path, request); throw new AssertionError("deadline ignored"); }
                    catch (DownloadRequest.Expired expected) { }
                }
                require(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 2000, "whole request bounded across reads/redirects");
            }
            try (DownloadRequest request = new DownloadRequest()) {
                var result = worker.submit(() -> {
                    try { Subscription.download(base + "/blocked", request); return false; }
                    catch (DownloadRequest.Cancelled expected) { return true; }
                });
                require(blocked.await(2, TimeUnit.SECONDS), "blocked body started");
                require(request.cancel(), "cancel accepted");
                require(result.get(2, TimeUnit.SECONDS), "blocked socket finishes as cancellation");
                require(worker.submit(() -> Subscription.download(base + "/valid").source().length > 0).get(2, TimeUnit.SECONDS),
                    "cancel frees shared download worker for a new request");
                try { request.seal(); throw new AssertionError("cancelled result reached write phase"); }
                catch (DownloadRequest.Cancelled expected) { }
            }
            try (DownloadRequest request = new DownloadRequest()) {
                request.seal(); require(!request.cancel(), "cannot cancel once write phase starts");
            }
            System.out.println("PASS: drip-fed body deadline, redirects, blocked-read cancellation, late write rejection and commit boundary.");
        } finally { server.stop(0); handlers.shutdownNow(); worker.shutdownNow(); }
    }
    private static void slowHeaders() throws Exception {
        try (java.net.ServerSocket listener = new java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))) {
            Thread server = new Thread(() -> {
                try (java.net.Socket socket = listener.accept()) {
                    for (byte value : "HTTP/1.1 200 OK\r\nContent-Length: 1\r\n\r\nx".getBytes(java.nio.charset.StandardCharsets.US_ASCII)) {
                        socket.getOutputStream().write(value); socket.getOutputStream().flush(); Thread.sleep(40);
                    }
                } catch (IOException | InterruptedException done) { }
            });
            server.setDaemon(true); server.start();
            long started = System.nanoTime();
            try (DownloadRequest request = new DownloadRequest(250)) {
                try { Subscription.download("http://127.0.0.1:" + listener.getLocalPort(), request); throw new AssertionError("slow headers escaped deadline"); }
                catch (DownloadRequest.Expired expected) { }
            } finally { server.interrupt(); server.join(1000); }
            require(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 1500, "deadline includes drip-fed response headers");
        }
    }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
}

package tech.maicraft.ampautocert;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

class JdkHttpTransportTest {
    @Test void rejectsOversizedResponseBody() throws Exception {
        byte[] oversized = new byte[1024 * 1024 + 1];
        withServer("/large", exchange -> {
            exchange.sendResponseHeaders(200, oversized.length);
            try (var body = exchange.getResponseBody()) { body.write(oversized); }
        }, uri -> {
            JdkHttpTransport transport = new JdkHttpTransport(Duration.ofSeconds(1), Duration.ofSeconds(2));
            assertThrows(IOException.class, () -> transport.send("GET", uri, Map.of(), ""));
        });
    }

    @Test void requestTimeoutAlsoBoundsStalledResponseBody() throws Exception {
        withServer("/slow", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            try (var body = exchange.getResponseBody()) {
                body.write('x');
                body.flush();
                try {
                    Thread.sleep(2_000);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        }, uri -> {
            JdkHttpTransport transport = new JdkHttpTransport(Duration.ofSeconds(1), Duration.ofMillis(150));
            long start = System.nanoTime();
            assertThrows(IOException.class, () -> transport.send("GET", uri, Map.of(), ""));
            assertTrue(Duration.ofNanos(System.nanoTime() - start).compareTo(Duration.ofSeconds(2)) < 0);
        });
    }

    private static void withServer(String path, com.sun.net.httpserver.HttpHandler handler, CheckedConsumer test)
            throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(path, handler);
        server.setExecutor(Executors.newCachedThreadPool(r -> {
            Thread thread = new Thread(r, "amp-autocert-http-test");
            thread.setDaemon(true);
            return thread;
        }));
        server.start();
        try {
            test.accept(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path));
        } finally {
            server.stop(0);
        }
    }

    @FunctionalInterface
    private interface CheckedConsumer {
        void accept(URI uri) throws Exception;
    }
}

package io.attune;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class AttuneClientTest {

    @Test
    void dynamicTokenSupplierIsReReadPerRequest() throws Exception {
        AtomicReference<String> lastAuthHeader = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/v1/events", exchange -> {
            lastAuthHeader.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] body = "{\"data\":{\"id\":1}}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        try {
            int port = server.getAddress().getPort();
            AtomicReference<String> token = new AtomicReference<>("token-1");
            AttuneClient client = new AttuneClient(
                    "http://localhost:" + port,
                    token::get,
                    Duration.ofSeconds(5)
            );

            Map<String, Object> first = client.post("/api/v1/events", Map.of("payload", Map.of("x", 1)));
            assertNotNull(first);
            assertEquals("Bearer token-1", lastAuthHeader.get());

            token.set("token-2");
            Map<String, Object> second = client.post("/api/v1/events", Map.of("payload", Map.of("x", 2)));
            assertNotNull(second);
            assertEquals("Bearer token-2", lastAuthHeader.get());
        } finally {
            server.stop(0);
        }
    }
}

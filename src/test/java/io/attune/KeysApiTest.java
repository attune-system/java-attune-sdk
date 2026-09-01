package io.attune;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KeysApiTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String KEY_JSON = """
            {"data":{"id":42,"ref":"pack.github.api_token","local_ref":"api_token",
            "owner_type":"pack","owner":"github","owner_identity":null,"owner_pack":7,
            "owner_action":null,"owner_sensor":null,"owner_pack_ref":"github",
            "owner_action_ref":null,"owner_sensor_ref":null,"name":"GitHub token",
            "encrypted":true,"value":{"token":"secret"},
            "created":"2026-09-01T10:00:00Z","updated":"2026-09-01T10:01:00Z"}}
            """;

    @Test
    void typedWrappersUseCurrentContractAndEncodeCanonicalRefs() throws Exception {
        List<CapturedRequest> requests = new ArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/v1/keys", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requests.add(new CapturedRequest(
                    exchange.getRequestMethod(),
                    exchange.getRequestURI().getRawPath(),
                    exchange.getRequestURI().getRawQuery(),
                    body
            ));

            String response;
            int status = 200;
            if ("GET".equals(exchange.getRequestMethod())
                    && "/api/v1/keys".equals(exchange.getRequestURI().getRawPath())) {
                response = """
                        {"items":[{"id":42,"ref":"pack.github.api_token","local_ref":"api_token",
                        "owner_type":"pack","owner":"github","name":"GitHub token",
                        "encrypted":true,"created":"2026-09-01T10:00:00Z"}],
                        "pagination":{"page":2,"page_size":10,"has_previous":true,"has_next":false,
                        "total_items":11,"total_pages":2}}
                        """;
            } else if ("DELETE".equals(exchange.getRequestMethod())) {
                response = "{\"success\":true,\"message\":\"Key deleted\"}";
            } else {
                response = KEY_JSON;
                if ("POST".equals(exchange.getRequestMethod())) status = 201;
            }

            byte[] responseBytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, responseBytes.length);
            exchange.getResponseBody().write(responseBytes);
            exchange.close();
        });
        server.start();

        try {
            AttuneClient client = new AttuneClient(
                    "http://localhost:" + server.getAddress().getPort(),
                    "test-token",
                    Duration.ofSeconds(5)
            );
            KeysApi keys = client.keys();

            KeyPage page = keys.list(OwnerType.PACK, "github integration", 2, 10);
            assertEquals("api_token", page.items().get(0).localRef());
            assertEquals(11L, page.pagination().totalItems());

            CreateKeyRequest createRequest = CreateKeyRequest.pack(
                    "api_token",
                    "GitHub token",
                    Map.of("token", "secret"),
                    "github"
            ).withEncryption(true);
            KeyResponse created = keys.create(createRequest);
            assertEquals("pack.github.api_token", created.ref());
            assertEquals("api_token", created.localRef());
            assertEquals(OwnerType.PACK, created.ownerType());
            assertEquals(7L, created.ownerPack());

            String ref = "pack/github token/+?#%";
            assertEquals("pack.github.api_token", keys.get(ref).ref());
            assertEquals("GitHub token", keys.update(
                    ref,
                    new UpdateKeyRequest(null, "GitHub token", Map.of("token", "new-secret"))
            ).name());
            assertTrue(keys.delete(ref).success());
        } finally {
            server.stop(0);
        }

        assertEquals(5, requests.size());
        assertEquals("GET", requests.get(0).method());
        assertEquals("/api/v1/keys", requests.get(0).rawPath());
        assertEquals("owner_type=pack&owner=github+integration&page=2&per_page=10", requests.get(0).rawQuery());

        assertEquals("POST", requests.get(1).method());
        assertEquals("/api/v1/keys", requests.get(1).rawPath());
        JsonNode createJson = MAPPER.readTree(requests.get(1).body());
        assertEquals("api_token", createJson.get("local_ref").asText());
        assertEquals("pack", createJson.get("owner_type").asText());
        assertEquals("github", createJson.get("owner_pack_ref").asText());
        assertFalse(createJson.has("ref"));

        String encodedPath = "/api/v1/keys/pack%2Fgithub%20token%2F%2B%3F%23%25";
        assertEquals("GET", requests.get(2).method());
        assertEquals(encodedPath, requests.get(2).rawPath());
        assertNull(requests.get(2).rawQuery());

        assertEquals("PUT", requests.get(3).method());
        assertEquals(encodedPath, requests.get(3).rawPath());
        JsonNode updateJson = MAPPER.readTree(requests.get(3).body());
        assertFalse(updateJson.has("encrypted"));
        assertEquals("new-secret", updateJson.get("value").get("token").asText());

        assertEquals("DELETE", requests.get(4).method());
        assertEquals(encodedPath, requests.get(4).rawPath());
        assertNull(requests.get(4).rawQuery());
    }

    @Test
    void factoriesCreateEachValidOwnerScope() {
        CreateKeyRequest system = CreateKeyRequest.system("token", "Token", "secret");
        assertEquals(OwnerType.SYSTEM, system.ownerType());
        assertNull(system.ownerIdentityLogin());
        assertNull(system.ownerPackRef());
        assertNull(system.ownerActionRef());
        assertNull(system.ownerSensorRef());

        CreateKeyRequest identity = CreateKeyRequest.identity("token", "Token", "secret", "alice@example.com");
        assertEquals(OwnerType.IDENTITY, identity.ownerType());
        assertEquals("alice@example.com", identity.ownerIdentityLogin());

        CreateKeyRequest pack = CreateKeyRequest.pack("token", "Token", "secret", "github");
        assertEquals(OwnerType.PACK, pack.ownerType());
        assertEquals("github", pack.ownerPackRef());

        CreateKeyRequest action = CreateKeyRequest.action("token", "Token", "secret", "github.create_issue");
        assertEquals(OwnerType.ACTION, action.ownerType());
        assertEquals("github.create_issue", action.ownerActionRef());

        CreateKeyRequest sensor = CreateKeyRequest.sensor("token", "Token", "secret", "github.webhook");
        assertEquals(OwnerType.SENSOR, sensor.ownerType());
        assertEquals("github.webhook", sensor.ownerSensorRef());
    }

    @Test
    void rejectsMissingMixedAndMismatchedOwnerSelectors() {
        assertThrows(IllegalArgumentException.class, () -> request(OwnerType.PACK, null, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> request(OwnerType.PACK, null, "github", "github.run", null));
        assertThrows(IllegalArgumentException.class, () -> request(OwnerType.ACTION, null, "github", null, null));
        assertThrows(IllegalArgumentException.class, () -> request(OwnerType.SYSTEM, "alice", null, null, null));
        assertThrows(IllegalArgumentException.class, () -> CreateKeyRequest.sensor("token", "Token", "secret", " "));
    }

    private static CreateKeyRequest request(
            OwnerType ownerType,
            String identityLogin,
            String packRef,
            String actionRef,
            String sensorRef
    ) {
        return new CreateKeyRequest(
                "token",
                ownerType,
                "Token",
                "secret",
                null,
                identityLogin,
                packRef,
                actionRef,
                sensorRef
        );
    }

    private record CapturedRequest(String method, String rawPath, String rawQuery, String body) {}
}

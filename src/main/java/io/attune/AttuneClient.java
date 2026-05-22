package io.attune;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * Lightweight HTTP client for the Attune API.
 *
 * <p>Uses the execution-scoped or sensor-scoped API token. Built on {@code java.net.http.HttpClient}.
 *
 * <p>Usage:
 * <pre>{@code
 * AttuneClient client = new AttuneClient(); // reads from env
 * Map<String, Object> artifacts = client.get("/api/v1/artifacts", Map.of("execution", "42"));
 * client.post("/api/v1/artifacts/1/versions/file", Map.of("created_by", "my_action"));
 * }</pre>
 */
public class AttuneClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final String apiUrl;
    private final String apiToken;
    private final HttpClient httpClient;
    private final Duration timeout;

    /**
     * Create a client reading configuration from environment variables.
     */
    public AttuneClient() {
        this(
                envOrDefault("ATTUNE_API_URL", "http://localhost:8080"),
                envOrDefault("ATTUNE_API_TOKEN", "")
        );
    }

    /**
     * Create a client with explicit URL and token.
     */
    public AttuneClient(String apiUrl, String apiToken) {
        this(apiUrl, apiToken, Duration.ofSeconds(30));
    }

    /**
     * Create a client with explicit URL, token, and timeout.
     */
    public AttuneClient(String apiUrl, String apiToken, Duration timeout) {
        this.apiUrl = apiUrl.replaceAll("/+$", "");
        this.apiToken = apiToken != null ? apiToken : "";
        this.timeout = timeout;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .build();
    }

    private static String envOrDefault(String key, String defaultValue) {
        String value = System.getenv(key);
        return value != null ? value : defaultValue;
    }

    /**
     * Send a GET request and return the parsed JSON response.
     */
    public Map<String, Object> get(String path) throws IOException, InterruptedException {
        return get(path, null);
    }

    /**
     * Send a GET request with query parameters and return the parsed JSON response.
     */
    public Map<String, Object> get(String path, Map<String, String> params) throws IOException, InterruptedException {
        String url = buildUrl(path, params);
        HttpRequest request = newRequestBuilder(url).GET().build();
        return execute(request);
    }

    /**
     * Send a POST request with a JSON body and return the parsed JSON response.
     */
    public Map<String, Object> post(String path, Object body) throws IOException, InterruptedException {
        String url = buildUrl(path, null);
        String json = MAPPER.writeValueAsString(body);
        HttpRequest request = newRequestBuilder(url)
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
        return execute(request);
    }

    /**
     * Send a PUT request with a JSON body and return the parsed JSON response.
     */
    public Map<String, Object> put(String path, Object body) throws IOException, InterruptedException {
        String url = buildUrl(path, null);
        String json = MAPPER.writeValueAsString(body);
        HttpRequest request = newRequestBuilder(url)
                .PUT(HttpRequest.BodyPublishers.ofString(json))
                .build();
        return execute(request);
    }

    /**
     * Send a DELETE request and return the parsed JSON response (or null if empty).
     */
    public Map<String, Object> delete(String path) throws IOException, InterruptedException {
        String url = buildUrl(path, null);
        HttpRequest request = newRequestBuilder(url).DELETE().build();
        return execute(request);
    }

    private HttpRequest.Builder newRequestBuilder(String url) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(timeout)
                .header("Content-Type", "application/json");
        if (!apiToken.isEmpty()) {
            builder.header("Authorization", "Bearer " + apiToken);
        }
        return builder;
    }

    private String buildUrl(String path, Map<String, String> params) {
        StringBuilder sb = new StringBuilder(apiUrl).append(path);
        if (params != null && !params.isEmpty()) {
            sb.append('?');
            boolean first = true;
            for (Map.Entry<String, String> entry : params.entrySet()) {
                if (!first) sb.append('&');
                sb.append(java.net.URLEncoder.encode(entry.getKey(), java.nio.charset.StandardCharsets.UTF_8));
                sb.append('=');
                sb.append(java.net.URLEncoder.encode(entry.getValue(), java.nio.charset.StandardCharsets.UTF_8));
                first = false;
            }
        }
        return sb.toString();
    }

    private Map<String, Object> execute(HttpRequest request) throws IOException, InterruptedException {
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 400) {
            throw new IOException("HTTP " + response.statusCode() + ": " + response.body());
        }
        String body = response.body();
        if (body == null || body.isBlank()) {
            return null;
        }
        return MAPPER.readValue(body, MAP_TYPE);
    }

    /** Returns the configured API URL. */
    public String apiUrl() { return apiUrl; }

    /** Returns the configured API token. */
    public String apiToken() { return apiToken; }
}

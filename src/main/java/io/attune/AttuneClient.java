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
import java.util.Objects;
import java.util.function.Supplier;

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
    private final Supplier<String> apiTokenSupplier;
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
     * Create a client with explicit URL and a dynamic token supplier.
     */
    public AttuneClient(String apiUrl, Supplier<String> apiTokenSupplier) {
        this(apiUrl, apiTokenSupplier, Duration.ofSeconds(30));
    }

    /**
     * Create a client with explicit URL, token, and timeout.
     */
    public AttuneClient(String apiUrl, String apiToken, Duration timeout) {
        this(apiUrl, () -> apiToken, timeout);
    }

    /**
     * Create a client with explicit URL, dynamic token supplier, and timeout.
     */
    public AttuneClient(String apiUrl, Supplier<String> apiTokenSupplier, Duration timeout) {
        this.apiUrl = apiUrl.replaceAll("/+$", "");
        this.apiTokenSupplier = Objects.requireNonNull(apiTokenSupplier, "apiTokenSupplier cannot be null");
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

    <T> T get(String path, Map<String, String> params, Class<T> responseType)
            throws IOException, InterruptedException {
        String url = buildUrl(path, params);
        HttpRequest request = newRequestBuilder(url).GET().build();
        return execute(request, responseType);
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

    <T> T post(String path, Object body, Class<T> responseType) throws IOException, InterruptedException {
        String url = buildUrl(path, null);
        String json = MAPPER.writeValueAsString(body);
        HttpRequest request = newRequestBuilder(url)
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
        return execute(request, responseType);
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

    <T> T put(String path, Object body, Class<T> responseType) throws IOException, InterruptedException {
        String url = buildUrl(path, null);
        String json = MAPPER.writeValueAsString(body);
        HttpRequest request = newRequestBuilder(url)
                .PUT(HttpRequest.BodyPublishers.ofString(json))
                .build();
        return execute(request, responseType);
    }

    /**
     * Send a DELETE request and return the parsed JSON response (or null if empty).
     */
    public Map<String, Object> delete(String path) throws IOException, InterruptedException {
        String url = buildUrl(path, null);
        HttpRequest request = newRequestBuilder(url).DELETE().build();
        return execute(request);
    }

    <T> T delete(String path, Class<T> responseType) throws IOException, InterruptedException {
        String url = buildUrl(path, null);
        HttpRequest request = newRequestBuilder(url).DELETE().build();
        return execute(request, responseType);
    }

    /** Returns the typed Keys API backed by this client's transport and credentials. */
    public KeysApi keys() {
        return new KeysApi(this);
    }

    private HttpRequest.Builder newRequestBuilder(String url) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(timeout)
                .header("Content-Type", "application/json");
        String apiToken = resolveApiToken();
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
        return execute(request, MAP_TYPE);
    }

    private <T> T execute(HttpRequest request, Class<T> responseType) throws IOException, InterruptedException {
        HttpResponse<String> response = send(request);
        String body = response.body();
        if (body == null || body.isBlank()) {
            return null;
        }
        return MAPPER.readValue(body, responseType);
    }

    private <T> T execute(HttpRequest request, TypeReference<T> responseType)
            throws IOException, InterruptedException {
        HttpResponse<String> response = send(request);
        String body = response.body();
        if (body == null || body.isBlank()) {
            return null;
        }
        return MAPPER.readValue(body, responseType);
    }

    private HttpResponse<String> send(HttpRequest request) throws IOException, InterruptedException {
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 400) {
            throw new IOException("HTTP " + response.statusCode() + ": " + response.body());
        }
        return response;
    }

    /** Returns the configured API URL. */
    public String apiUrl() { return apiUrl; }

    /** Returns the current API token from the configured token supplier. */
    public String apiToken() { return resolveApiToken(); }

    private String resolveApiToken() {
        try {
            String token = apiTokenSupplier.get();
            return token != null ? token : "";
        } catch (RuntimeException e) {
            throw new IllegalStateException("Failed to resolve current ATTUNE_API_TOKEN", e);
        }
    }
}

package io.attune;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Typed access to the Attune Keys API. */
public final class KeysApi {

    private static final String PATH = "/api/v1/keys";

    private final AttuneClient client;

    KeysApi(AttuneClient client) {
        this.client = client;
    }

    public KeyPage list() throws IOException, InterruptedException {
        return list(null, null, null, null);
    }

    public KeyPage list(OwnerType ownerType, String owner, Integer page, Integer perPage)
            throws IOException, InterruptedException {
        Map<String, String> params = new LinkedHashMap<>();
        if (ownerType != null) params.put("owner_type", ownerType.value());
        if (owner != null) params.put("owner", owner);
        if (page != null) params.put("page", page.toString());
        if (perPage != null) params.put("per_page", perPage.toString());
        return client.get(PATH, params, KeyPage.class);
    }

    public KeyResponse create(CreateKeyRequest request) throws IOException, InterruptedException {
        KeyEnvelope response = client.post(PATH, Objects.requireNonNull(request), KeyEnvelope.class);
        return response.data();
    }

    public KeyResponse get(String ref) throws IOException, InterruptedException {
        KeyEnvelope response = client.get(path(ref), null, KeyEnvelope.class);
        return response.data();
    }

    public KeyResponse update(String ref, UpdateKeyRequest request) throws IOException, InterruptedException {
        KeyEnvelope response = client.put(path(ref), Objects.requireNonNull(request), KeyEnvelope.class);
        return response.data();
    }

    public SuccessResponse delete(String ref) throws IOException, InterruptedException {
        return client.delete(path(ref), SuccessResponse.class);
    }

    private static String path(String ref) {
        Objects.requireNonNull(ref, "ref cannot be null");
        String segment = URLEncoder.encode(ref, StandardCharsets.UTF_8).replace("+", "%20");
        return PATH + "/" + segment;
    }

    private record KeyEnvelope(KeyResponse data, String message) {}
}

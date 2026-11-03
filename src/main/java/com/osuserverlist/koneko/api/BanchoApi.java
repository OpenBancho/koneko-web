package com.osuserverlist.koneko.api;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.osuserverlist.koneko.config.Env;

import lombok.Getter;

/**
 * Thin client for the bancho.jar public API.
 *
 * <p>Only the JDK HTTP client is used, so this frontend pulls in no HTTP
 * library of its own. Every call returns the parsed JSON body, or throws
 * {@link ApiException} carrying the status the API answered with.
 */
public final class BanchoApi {

    private static final Logger logger = LoggerFactory.getLogger("BanchoApi");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpClient client;
    private final Duration timeout;
    private final String clientId;

    /** Base url of the API, without a trailing slash. */
    @Getter
    private final String baseUrl;

    public BanchoApi(Env env) {
        this(env, resolveBaseUrl(env));
    }

    /** A client for another autonomous server (lazer.jar) that speaks the same JSON-over-HTTP way. */
    public BanchoApi(Env env, String baseUrl) {
        this.baseUrl = stripTrailingSlash(baseUrl.trim());
        this.timeout = Duration.ofSeconds(Env.API_TIMEOUT_SECONDS);
        this.clientId = env.getApiClientId();

        this.client = HttpClient.newBuilder()
                .connectTimeout(this.timeout)
                // The API answers 301 for http, and follows the api. host.
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /**
     * Uses API_BASE_URL when it is set, and falls back to the api subdomain of
     * DOMAIN, which is where bancho.jar hosts its API by default.
     */
    public static String resolveBaseUrl(Env env) {
        String configured = env.getApiBaseUrl();

        if (configured != null && !configured.isBlank()) {
            return stripTrailingSlash(configured.trim());
        }

        return "https://api." + env.getDomain();
    }

    // ------------------------------------------------------------------
    // oauth
    // ------------------------------------------------------------------

    /**
     * Exchanges a username and password for a token pair.
     *
     * <p>The password is sent in the clear to the API, which hashes it itself;
     * this is server to server traffic over https, so nothing is gained by
     * pre-hashing it here.
     */
    public TokenPair passwordGrant(String username, String password, String scope) throws ApiException {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "password");
        form.put("username", username);
        form.put("password", password);
        form.put("scope", scope);
        form.put("client_id", clientId);

        return tokenPairOf(postForm("/api/v1/oauth/token", form));
    }

    /**
     * A freshly registered account, together with the token pair the API issued
     * for it. The pair is null only when the account was created but the API
     * could not log it in, in which case the player has to use the login form.
     */
    public record Registration(int id, String name, int privileges, TokenPair tokens) {
    }

    /**
     * Backwards-compatible registration signature used by older AuthRoutes.
     * Those versions only supported Turnstile and therefore did not pass the
     * captcha form field separately.
     */
    public Registration registerAccount(String username, String email, String password,
            String captchaToken, String scope) throws ApiException {
        return registerAccount(username, email, password, captchaToken,
                "cf-turnstile-response", scope);
    }

    public Registration registerAccount(String username, String email, String password,
            String captchaToken, String captchaField, String scope) throws ApiException {

        Map<String, String> form = new LinkedHashMap<>();
        form.put("username", username);
        form.put("email", email);
        form.put("password", password);
        form.put("scope", scope);
        form.put("client_id", clientId);

        if (captchaToken != null && !captchaToken.isBlank()
                && captchaField != null && !captchaField.isBlank()) {
            form.put(captchaField, captchaToken);
        }

        JsonNode body = postForm("/api/v1/users/register", form);
        JsonNode user = body.path("user");

        TokenPair tokens = body.path("access_token").asText("").isBlank()
                ? null
                : tokenPairOf(body);

        return new Registration(
                user.path("id").asInt(),
                user.path("name").asText(username),
                user.path("priv").asInt(),
                tokens);
    }

    /** Rotates a refresh token into a fresh pair. */
    public TokenPair refresh(String refreshToken) throws ApiException {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "refresh_token");
        form.put("refresh_token", refreshToken);
        form.put("client_id", clientId);

        return tokenPairOf(postForm("/api/v1/oauth/token", form));
    }

    /**
     * Revokes a refresh token, which takes the whole chain down on the API
     * side. Failures are logged and swallowed: a logout must never fail for
     * the user because the API had a bad moment.
     */
    public void revokeRefreshToken(String refreshToken) {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("token", refreshToken);
        form.put("token_type_hint", "refresh_token");

        try {
            postForm("/api/v1/oauth/revoke", form);
        } catch (ApiException e) {
            logger.warn("Could not revoke a refresh token: {}", e.getMessage());
        }
    }

    /** Reads the identity behind an access token. */
    public JsonNode userInfo(String accessToken) throws ApiException {
        return getAuthed("/api/v1/oauth/userinfo", Map.of(), accessToken);
    }

    private TokenPair tokenPairOf(JsonNode body) {
        return new TokenPair(
                body.path("access_token").asText(),
                body.path("refresh_token").asText(),
                body.path("scope").asText(""),
                body.path("expires_in").asLong(3600),
                body.path("refresh_expires_in").asLong(2592000));
    }

    // ------------------------------------------------------------------
    // plain calls
    // ------------------------------------------------------------------

    /** GET on a public endpoint. */
    public JsonNode get(String path, Map<String, String> query) throws ApiException {
        return send(HttpRequest.newBuilder(URI.create(baseUrl + path + queryString(query)))
                .timeout(timeout)
                .header("Accept", "application/json")
                .GET());
    }

    /** GET on an endpoint that wants a bearer token. */
    public JsonNode getAuthed(String path, Map<String, String> query, String accessToken) throws ApiException {
        return send(HttpRequest.newBuilder(URI.create(baseUrl + path + queryString(query)))
                .timeout(timeout)
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + accessToken)
                .GET());
    }

    /** POST of a form encoded body on an endpoint that wants a bearer token. */
    public JsonNode postFormAuthed(String path, Map<String, String> form, String accessToken) throws ApiException {
        return send(HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(timeout)
                .header("Accept", "application/json")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Authorization", "Bearer " + accessToken)
                .POST(HttpRequest.BodyPublishers.ofString(formBody(form), StandardCharsets.UTF_8)));
    }

    /**
     * Any method, any body, any content type: the escape hatch behind
     * {@code ApiClient.request} that lets a plugin reach an endpoint this class
     * knows nothing about, including ones added to bancho.jar later.
     *
     * @param method      GET, POST, PUT, PATCH or DELETE
     * @param path        path on the API, starting with a slash
     * @param query       query parameters, may be null
     * @param body        request body, may be null
     * @param contentType content type of the body, may be null
     * @param accessToken bearer token to send, may be null
     */
    public JsonNode request(String method, String path, Map<String, String> query,
            String body, String contentType, String accessToken) throws ApiException {

        String verb = method == null ? "GET" : method.trim().toUpperCase(java.util.Locale.ROOT);
        String payload = body == null ? "" : body;

        HttpRequest.Builder builder = HttpRequest
                .newBuilder(URI.create(baseUrl + path + queryString(query)))
                .timeout(timeout)
                .header("Accept", "application/json");

        if (contentType != null && !contentType.isBlank()) {
            builder.header("Content-Type", contentType);
        }

        if (accessToken != null && !accessToken.isBlank()) {
            builder.header("Authorization", "Bearer " + accessToken);
        }

        HttpRequest.BodyPublisher publisher =
                HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8);

        switch (verb) {
            case "GET" -> builder.GET();
            case "DELETE" -> builder.method("DELETE", publisher);
            case "POST", "PUT", "PATCH" -> builder.method(verb, publisher);
            default -> throw new ApiException(400, "Unsupported HTTP method: " + verb);
        }

        return send(builder);
    }

    /**
     * Sends a small binary body to an authenticated API endpoint.
     *
     * <p>This is deliberately separate from {@link #request}: converting an
     * image to a String would corrupt it, and accepting arbitrary multipart
     * data here would forward filenames and other untrusted metadata the API
     * does not need.</p>
     */
    public JsonNode requestBytes(String method, String path, byte[] body,
            String contentType, String accessToken) throws ApiException {

        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(timeout)
                .header("Accept", "application/json")
                .header("Content-Type", contentType)
                .header("Authorization", "Bearer " + accessToken)
                .method(method.trim().toUpperCase(java.util.Locale.ROOT),
                        HttpRequest.BodyPublishers.ofByteArray(body));

        return send(builder);
    }

    private JsonNode postForm(String path, Map<String, String> form) throws ApiException {
        return send(HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(timeout)
                .header("Accept", "application/json")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(formBody(form), StandardCharsets.UTF_8)));
    }

    private JsonNode send(HttpRequest.Builder builder) throws ApiException {
        HttpRequest request = builder.build();

        HttpResponse<String> response;

        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            logger.error("The API at <{}> could not be reached", baseUrl, e);
            throw new ApiException(503, "The game server API is unreachable.", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(503, "The request to the game server API was interrupted.", e);
        }

        JsonNode body = parse(response.body());

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new ApiException(response.statusCode(), messageOf(body, response.statusCode()));
        }

        return body;
    }

    private static JsonNode parse(String body) {
        if (body == null || body.isBlank()) {
            return MAPPER.createObjectNode();
        }

        try {
            return MAPPER.readTree(body);
        } catch (IOException e) {
            return MAPPER.createObjectNode();
        }
    }

    /**
     * Digs the human readable part out of an error body. The API uses
     * {@code status} for its own errors and the RFC 6749 {@code error} plus
     * {@code error_description} pair for the oauth endpoints.
     */
    private static String messageOf(JsonNode body, int status) {
        if (body.hasNonNull("error_description")) {
            return body.get("error_description").asText();
        }

        if (body.hasNonNull("error")) {
            return body.get("error").asText();
        }

        if (body.hasNonNull("status") && body.get("status").isTextual()) {
            return body.get("status").asText();
        }

        return "The game server API answered with " + status + ".";
    }

    private static String queryString(Map<String, String> query) {
        if (query == null || query.isEmpty()) {
            return "";
        }

        return "?" + formBody(query);
    }

    private static String formBody(Map<String, String> values) {
        StringBuilder body = new StringBuilder();

        for (Map.Entry<String, String> entry : values.entrySet()) {
            if (entry.getValue() == null) {
                continue;
            }

            if (body.length() > 0) {
                body.append("&");
            }

            body.append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8));
            body.append("=");
            body.append(URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8));
        }

        return body.toString();
    }

    private static String stripTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}

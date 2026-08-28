package com.osuserverlist.koneko.theme;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Manages event themes for KonekoWeb.
 *
 * <p>Themes can be fetched dynamically from any remote or localhost service,
 * previewed, activated, and applied site-wide with full support for custom CSS,
 * layout modifications, dynamic banners, interactive floating widgets, and particle effects.
 */
public final class ThemeService {

    private static final Logger logger = LoggerFactory.getLogger("ThemeService");
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Path CONFIG_PATH = Path.of(".config", "theme-settings.json");

    private static final String DEFAULT_SOURCE_URL = "http://localhost:3000/api/themes";

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(6))
            .build();

    private static boolean enabled = true;
    private static String sourceUrl = DEFAULT_SOURCE_URL;
    private static String activeThemeId = null;
    private static Map<String, Object> activeTheme = null;
    private static List<Map<String, Object>> cachedThemes = new ArrayList<>();
    private static String lastFetched = null;
    private static String lastError = null;

    private ThemeService() {
    }

    /**
     * Initializes theme service on server startup.
     */
    public static synchronized void init() {
        loadConfig();
        logger.info("ThemeService initialized. Enabled: {}, Active Theme: <{}>, Source: <{}>",
                enabled, activeThemeId != null ? activeThemeId : "none", sourceUrl);

        // Try pre-fetching available themes in background
        if (sourceUrl != null && !sourceUrl.isBlank()) {
            Thread.ofVirtual().start(() -> {
                try {
                    fetchThemes(sourceUrl);
                } catch (Exception e) {
                    logger.debug("Initial background theme fetch notice: {}", e.getMessage());
                }
            });
        }
    }

    /**
     * State passed to public frontend bootstrap (window.__koneko.theme).
     */
    public static synchronized Map<String, Object> getPublicBootstrap() {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("enabled", enabled && activeTheme != null);
        state.put("activeThemeId", activeThemeId);
        state.put("active", enabled ? activeTheme : null);
        return state;
    }

    /**
     * Full admin state for staff panel.
     */
    public static synchronized Map<String, Object> getAdminState() {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("enabled", enabled);
        state.put("sourceUrl", sourceUrl);
        state.put("activeThemeId", activeThemeId);
        state.put("activeTheme", activeTheme);
        state.put("availableThemes", cachedThemes);
        state.put("lastFetched", lastFetched);
        state.put("error", lastError);
        return state;
    }

    /**
     * Fetches themes from the given or configured source URL.
     */
    public static synchronized List<Map<String, Object>> fetchThemes(String url) throws Exception {
        String targetUrl = (url != null && !url.isBlank()) ? url.trim() : sourceUrl;
        if (targetUrl == null || targetUrl.isBlank()) {
            targetUrl = DEFAULT_SOURCE_URL;
        }

        logger.info("Fetching themes from <{}>...", targetUrl);

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(targetUrl))
                    .header("Accept", "application/json")
                    .timeout(Duration.ofSeconds(8))
                    .GET()
                    .build();

            HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IOException("Remote server returned HTTP " + response.statusCode());
            }

            JsonNode root = MAPPER.readTree(response.body());
            List<Map<String, Object>> themes = new ArrayList<>();

            if (root.isArray()) {
                for (JsonNode item : root) {
                    themes.add(MAPPER.convertValue(item, new TypeReference<Map<String, Object>>() {}));
                }
            } else if (root.has("themes") && root.get("themes").isArray()) {
                for (JsonNode item : root.get("themes")) {
                    themes.add(MAPPER.convertValue(item, new TypeReference<Map<String, Object>>() {}));
                }
            } else if (root.has("id")) {
                themes.add(MAPPER.convertValue(root, new TypeReference<Map<String, Object>>() {}));
            }

            cachedThemes = themes;
            sourceUrl = targetUrl;
            lastFetched = Instant.now().toString();
            lastError = null;

            // If active theme is set, refresh activeTheme object from fresh list if found
            if (activeThemeId != null) {
                for (Map<String, Object> t : cachedThemes) {
                    if (activeThemeId.equals(t.get("id"))) {
                        activeTheme = t;
                        break;
                    }
                }
            }

            saveConfig();
            logger.info("Successfully fetched {} themes from <{}>", cachedThemes.size(), targetUrl);
            return cachedThemes;
        } catch (Exception e) {
            lastError = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            logger.warn("Failed to fetch themes from <{}>: {}", targetUrl, lastError);
            throw e;
        }
    }

    /**
     * Activates a theme by its ID.
     */
    public static synchronized Map<String, Object> activateTheme(String themeId, Map<String, Object> directData) throws Exception {
        if (themeId == null || themeId.isBlank()) {
            throw new IllegalArgumentException("Theme ID is required.");
        }

        Map<String, Object> target = directData;

        if (target == null) {
            for (Map<String, Object> item : cachedThemes) {
                if (themeId.equals(item.get("id"))) {
                    target = item;
                    break;
                }
            }
        }

        if (target == null) {
            // Try fetching single theme from sourceUrl + "/" + themeId
            try {
                String singleUrl = sourceUrl.replaceAll("/+$", "") + "/" + themeId;
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(singleUrl))
                        .header("Accept", "application/json")
                        .timeout(Duration.ofSeconds(6))
                        .GET()
                        .build();
                HttpResponse<String> res = HTTP_CLIENT.send(req, HttpResponse.BodyHandlers.ofString());
                if (res.statusCode() == 200) {
                    JsonNode node = MAPPER.readTree(res.body());
                    if (node.has("theme")) {
                        target = MAPPER.convertValue(node.get("theme"), new TypeReference<Map<String, Object>>() {});
                    } else {
                        target = MAPPER.convertValue(node, new TypeReference<Map<String, Object>>() {});
                    }
                }
            } catch (Exception ignored) {
            }
        }

        if (target == null) {
            throw new IllegalArgumentException("Theme with ID '" + themeId + "' not found in available themes.");
        }

        activeThemeId = themeId;
        activeTheme = target;
        enabled = true;
        saveConfig();

        logger.info("Activated theme: <{}> ({})", themeId, target.get("name"));
        return activeTheme;
    }

    /**
     * Deactivates the currently active theme.
     */
    public static synchronized void deactivateTheme() {
        activeThemeId = null;
        activeTheme = null;
        saveConfig();
        logger.info("Theme deactivated. Reverted to default theme.");
    }

    /**
     * Updates settings (source URL and enabled flag).
     */
    public static synchronized void updateSettings(String newSourceUrl, Boolean isEnabled) {
        if (newSourceUrl != null) {
            sourceUrl = newSourceUrl.trim();
        }
        if (isEnabled != null) {
            enabled = isEnabled;
        }
        saveConfig();
        logger.info("Updated Theme Settings - Source: <{}>, Enabled: {}", sourceUrl, enabled);
    }

    private static synchronized void loadConfig() {
        if (!Files.exists(CONFIG_PATH)) {
            return;
        }
        try {
            String json = Files.readString(CONFIG_PATH);
            JsonNode root = MAPPER.readTree(json);

            if (root.has("enabled")) {
                enabled = root.get("enabled").asBoolean(true);
            }
            if (root.has("sourceUrl")) {
                sourceUrl = root.get("sourceUrl").asText(DEFAULT_SOURCE_URL);
            }
            if (root.has("activeThemeId")) {
                activeThemeId = root.get("activeThemeId").isNull() ? null : root.get("activeThemeId").asText(null);
            }
            if (root.has("activeTheme") && !root.get("activeTheme").isNull()) {
                activeTheme = MAPPER.convertValue(root.get("activeTheme"), new TypeReference<Map<String, Object>>() {});
            }
            if (root.has("cachedThemes") && root.get("cachedThemes").isArray()) {
                cachedThemes = MAPPER.convertValue(root.get("cachedThemes"), new TypeReference<List<Map<String, Object>>>() {});
            }
            if (root.has("lastFetched")) {
                lastFetched = root.get("lastFetched").asText(null);
            }
        } catch (Exception e) {
            logger.warn("Could not read theme settings from {}: {}", CONFIG_PATH, e.getMessage());
        }
    }

    private static synchronized void saveConfig() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("enabled", enabled);
            data.put("sourceUrl", sourceUrl);
            data.put("activeThemeId", activeThemeId);
            data.put("activeTheme", activeTheme);
            data.put("cachedThemes", cachedThemes);
            data.put("lastFetched", lastFetched);

            Files.writeString(CONFIG_PATH, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(data));
        } catch (Exception e) {
            logger.warn("Could not save theme settings to {}: {}", CONFIG_PATH, e.getMessage());
        }
    }
}

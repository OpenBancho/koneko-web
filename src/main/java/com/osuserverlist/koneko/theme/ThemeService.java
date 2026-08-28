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
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.osuserverlist.koneko.App;
import com.osuserverlist.koneko.config.Env;

/**
 * Event themes: fetched from an operator-named source, approved by a staff member, and then
 * applied to every page of the site.
 *
 * <p>A theme is not configuration. It arrives from another server, it can carry CSS and
 * JavaScript, and it ends up in front of every visitor - so it is handled as untrusted input
 * from the moment it arrives until a browser confines it. Three things divide the work:
 * {@link ThemeSecurity} decides what may be fetched and what CSS may be applied, this class
 * decides what is stored and what a browser is told, and the frontend's theme runtime confines
 * the JavaScript in a frame with an opaque origin.
 *
 * <h2>Why the approval carries a hash</h2>
 *
 * <p>Approving a URL would be approving whatever that URL serves next. The consent recorded
 * here names a payload fingerprint instead, so a source that changes its code loses the
 * approval it was given and the theme deactivates itself rather than shipping something nobody
 * looked at.
 *
 * <h2>Reading state is never blocked by fetching it</h2>
 *
 * <p>{@link #getPublicBootstrap()} runs on the render path of every page. It reads an immutable
 * snapshot out of an {@link AtomicReference} and takes no lock, so a theme source that has
 * stopped answering delays nobody: the fetch happens under a lock that the render path never
 * touches.
 */
public final class ThemeService {

    private static final Logger logger = LoggerFactory.getLogger("ThemeService");
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Path CONFIG_PATH = Path.of(Env.CONFIG_DIR, "theme-settings.json");

    /** Refuses a theme source that answers with something enormous. */
    private static final int MAX_PAYLOAD_BYTES = 2 * 1024 * 1024;

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(6))
            // A theme source that answers with a redirect to somewhere else would walk
            // straight around the host allowlist, so redirects are not followed.
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    /**
     * Everything a page needs to know, as one immutable object.
     *
     * <p>Replaced wholesale rather than mutated field by field, so a render can never observe
     * a half-applied change - an active theme whose CSS has already been swapped, for instance.
     */
    private record State(
            boolean engineEnabled,
            String sourceUrl,
            String activeThemeId,
            Map<String, Object> activeTheme,
            String activeFingerprint,
            String approvedBy,
            String approvedAt,
            boolean jsApproved,
            List<Map<String, Object>> availableThemes,
            String lastFetched,
            String lastError) {

        static State initial() {
            return new State(true, "", null, null, null, null, null, false,
                    List.of(), null, null);
        }
    }

    private static final AtomicReference<State> STATE = new AtomicReference<>(State.initial());

    /** Held by anything that writes. The render path never asks for it. */
    private static final Object WRITE_LOCK = new Object();

    private ThemeService() {
    }

    /** Reads the stored settings. Nothing is fetched here: startup does not wait on a network. */
    public static void init() {
        synchronized (WRITE_LOCK) {
            loadConfig();
        }

        State state = STATE.get();

        logger.info("ThemeService ready. Engine: {}, active theme: <{}>, custom JS allowed: {}",
                state.engineEnabled(),
                state.activeThemeId() == null ? "none" : state.activeThemeId(),
                App.env.isThemeCustomJsAllowed());

        if (state.activeThemeId() != null && !state.jsApproved()
                && state.activeTheme() != null && state.activeTheme().get("custom_js") != null) {
            logger.info("Active theme <{}> carries custom JS that was not approved; "
                    + "it will not be sent to browsers.", state.activeThemeId());
        }
    }

    /**
     * What a page is told about the current theme.
     *
     * <p>The custom JavaScript is stripped unless the deployment allows it and a staff member
     * approved this exact payload. Stripping it here rather than in the browser means the
     * decision is not one a page can be talked out of.
     */
    public static Map<String, Object> getPublicBootstrap() {
        State state = STATE.get();

        Map<String, Object> bootstrap = new LinkedHashMap<>();
        boolean live = state.engineEnabled() && state.activeTheme() != null;

        bootstrap.put("enabled", live);
        bootstrap.put("activeThemeId", live ? state.activeThemeId() : null);

        if (!live) {
            bootstrap.put("active", null);
            bootstrap.put("jsEnabled", false);
            return bootstrap;
        }

        boolean jsLive = App.env.isThemeCustomJsAllowed() && state.jsApproved();

        Map<String, Object> theme = new LinkedHashMap<>(state.activeTheme());

        if (!jsLive) {
            theme.remove("custom_js");
        }

        bootstrap.put("active", theme);
        bootstrap.put("jsEnabled", jsLive && theme.get("custom_js") != null);

        return bootstrap;
    }

    /** The full picture, for the staff panel only. */
    public static Map<String, Object> getAdminState() {
        State state = STATE.get();

        Map<String, Object> view = new LinkedHashMap<>();
        view.put("enabled", state.engineEnabled());
        view.put("sourceUrl", state.sourceUrl());
        view.put("activeThemeId", state.activeThemeId());
        view.put("activeTheme", state.activeTheme());
        view.put("activeFingerprint", state.activeFingerprint());
        view.put("approvedBy", state.approvedBy());
        view.put("approvedAt", state.approvedAt());
        view.put("jsApproved", state.jsApproved());
        view.put("availableThemes", state.availableThemes());
        view.put("lastFetched", state.lastFetched());
        view.put("error", state.lastError());

        // So the panel can explain why a JS-carrying theme is running without its JS,
        // instead of leaving a staff member to guess.
        view.put("customJsAllowedByServer", App.env.isThemeCustomJsAllowed());
        view.put("allowedHosts", App.env.getThemeSourceHosts());

        return view;
    }

    /**
     * Fetches the theme list from a source.
     *
     * @throws IllegalArgumentException when the URL is not one this deployment may fetch
     * @throws IOException when the source cannot be read
     */
    public static List<Map<String, Object>> fetchThemes(String url) throws IOException {
        String candidate = url == null || url.isBlank() ? STATE.get().sourceUrl() : url.trim();

        // Checked before the lock is taken and before anything is sent: a refused URL should
        // cost nothing and block nobody.
        ThemeSecurity.requireFetchableUrl(candidate, App.env.getThemeSourceHosts());

        String body = get(candidate, Duration.ofSeconds(8));
        List<Map<String, Object>> themes = parseThemeList(body);

        synchronized (WRITE_LOCK) {
            State current = STATE.get();

            // The active theme is refreshed from the new list only when the payload still
            // matches the approved fingerprint. A changed theme keeps running as approved
            // until somebody approves the new version.
            Map<String, Object> active = current.activeTheme();
            boolean jsApproved = current.jsApproved();

            if (current.activeThemeId() != null) {
                for (Map<String, Object> theme : themes) {
                    if (!current.activeThemeId().equals(theme.get("id"))) {
                        continue;
                    }

                    String fingerprint = fingerprintOf(theme);

                    if (fingerprint.equals(current.activeFingerprint())) {
                        active = theme;
                    } else {
                        logger.warn("Theme <{}> changed at the source. Keeping the approved "
                                + "version; a staff member has to approve the new one.",
                                current.activeThemeId());
                    }

                    break;
                }
            }

            STATE.set(new State(current.engineEnabled(), candidate, current.activeThemeId(),
                    active, current.activeFingerprint(), current.approvedBy(),
                    current.approvedAt(), jsApproved, List.copyOf(themes),
                    Instant.now().toString(), null));

            saveConfig();
        }

        logger.info("Fetched {} themes from <{}>", themes.size(), candidate);
        return themes;
    }

    /**
     * Approves a theme and puts it live.
     *
     * <p>{@code jsConsent} is the record of a staff member accepting what a theme's JavaScript
     * can still do from inside its sandbox. It is required for the JS to run and it is bound to
     * this payload's fingerprint, so it does not carry over to a different version of the same
     * theme. Without it the theme is applied without its JavaScript rather than refused.
     *
     * @param themeId  which theme, as the source names it
     * @param jsConsent whether the staff member accepted the JavaScript risk
     * @param staffName who approved it, for the log
     */
    public static Map<String, Object> activateTheme(String themeId, boolean jsConsent, String staffName) {
        if (themeId == null || themeId.isBlank()) {
            throw new IllegalArgumentException("A theme id is required.");
        }

        synchronized (WRITE_LOCK) {
            State current = STATE.get();

            Map<String, Object> target = null;

            for (Map<String, Object> theme : current.availableThemes()) {
                if (themeId.equals(theme.get("id"))) {
                    target = theme;
                    break;
                }
            }

            if (target == null) {
                // Deliberately not fetched from a guessed single-theme URL here. The old code
                // built one out of the source URL and the id, which let the id steer the
                // request; a theme has to come from the list that was fetched and checked.
                throw new IllegalArgumentException("Theme <" + themeId
                        + "> is not in the fetched list. Fetch the source again first.");
            }

            // Validated before anything is stored, so a theme with unusable CSS fails here
            // with a message rather than on every page afterwards.
            Map<String, Object> sanitised = sanitise(target);
            String fingerprint = fingerprintOf(target);
            boolean carriesJs = sanitised.get("custom_js") != null;
            boolean jsApproved = carriesJs && jsConsent;

            STATE.set(new State(true, current.sourceUrl(), themeId, sanitised, fingerprint,
                    staffName, Instant.now().toString(), jsApproved,
                    current.availableThemes(), current.lastFetched(), null));

            saveConfig();

            if (carriesJs) {
                // Worth a line of its own: this is the moment a deployment starts serving
                // third party code, and the log is where that has to be visible afterwards.
                logger.warn("Staff <{}> activated theme <{}> carrying custom JS. "
                        + "Consent: {}. Server allows JS: {}. Fingerprint: {}",
                        staffName, themeId, jsConsent, App.env.isThemeCustomJsAllowed(), fingerprint);
            } else {
                logger.info("Staff <{}> activated theme <{}>. Fingerprint: {}",
                        staffName, themeId, fingerprint);
            }

            return sanitised;
        }
    }

    /** Applies a theme pasted straight into the panel, bypassing the source entirely. */
    public static Map<String, Object> activateDirect(Map<String, Object> theme, boolean jsConsent,
            String staffName) {
        if (theme == null || theme.get("id") == null) {
            throw new IllegalArgumentException("The pasted theme needs an id.");
        }

        String themeId = String.valueOf(theme.get("id"));

        synchronized (WRITE_LOCK) {
            State current = STATE.get();

            Map<String, Object> sanitised = sanitise(theme);
            String fingerprint = fingerprintOf(theme);
            boolean carriesJs = sanitised.get("custom_js") != null;

            STATE.set(new State(true, current.sourceUrl(), themeId, sanitised, fingerprint,
                    staffName, Instant.now().toString(), carriesJs && jsConsent,
                    current.availableThemes(), current.lastFetched(), null));

            saveConfig();

            logger.warn("Staff <{}> imported theme <{}> directly. Carries JS: {}, consent: {}. "
                    + "Fingerprint: {}", staffName, themeId, carriesJs, jsConsent, fingerprint);

            return sanitised;
        }
    }

    public static void deactivateTheme(String staffName) {
        synchronized (WRITE_LOCK) {
            State current = STATE.get();

            STATE.set(new State(current.engineEnabled(), current.sourceUrl(), null, null, null,
                    null, null, false, current.availableThemes(), current.lastFetched(), null));

            saveConfig();
        }

        logger.info("Staff <{}> deactivated the event theme.", staffName);
    }

    /**
     * Changes the source URL, the engine switch, or both.
     *
     * <p>A new source URL is checked here rather than at the next fetch, so an address this
     * deployment may not reach is refused while somebody is looking at the form.
     */
    public static void updateSettings(String newSourceUrl, Boolean engineEnabled, String staffName) {
        // Checked before the lock, because the check resolves a host name: a slow or hanging
        // DNS answer would otherwise hold the lock and stall every other theme write behind it.
        if (newSourceUrl != null && !newSourceUrl.isBlank()) {
            ThemeSecurity.requireFetchableUrl(newSourceUrl, App.env.getThemeSourceHosts());
        }

        synchronized (WRITE_LOCK) {
            State current = STATE.get();
            String sourceUrl = current.sourceUrl();

            if (newSourceUrl != null && !newSourceUrl.isBlank()) {
                sourceUrl = newSourceUrl.trim();
            }

            boolean enabled = engineEnabled == null ? current.engineEnabled() : engineEnabled;

            STATE.set(new State(enabled, sourceUrl, current.activeThemeId(),
                    current.activeTheme(), current.activeFingerprint(), current.approvedBy(),
                    current.approvedAt(), current.jsApproved(), current.availableThemes(),
                    current.lastFetched(), null));

            saveConfig();
        }

        logger.info("Staff <{}> updated theme settings. Source: <{}>, engine enabled: {}",
                staffName, STATE.get().sourceUrl(), STATE.get().engineEnabled());
    }

    /** Records why the last fetch failed, so the panel can show it after a reload. */
    public static void recordError(String message) {
        synchronized (WRITE_LOCK) {
            State current = STATE.get();

            STATE.set(new State(current.engineEnabled(), current.sourceUrl(),
                    current.activeThemeId(), current.activeTheme(), current.activeFingerprint(),
                    current.approvedBy(), current.approvedAt(), current.jsApproved(),
                    current.availableThemes(), current.lastFetched(), message));
        }
    }

    private static String get(String url, Duration timeout) throws IOException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Accept", "application/json")
                .timeout(timeout)
                .GET()
                .build();

        HttpResponse<String> response;

        try {
            response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            // Restored rather than swallowed: whoever interrupted this thread meant it.
            Thread.currentThread().interrupt();
            throw new IOException("The theme fetch was interrupted.", e);
        }

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("The theme source answered HTTP " + response.statusCode() + ".");
        }

        String body = response.body();

        if (body == null || body.isBlank()) {
            throw new IOException("The theme source answered with an empty body.");
        }

        if (body.length() > MAX_PAYLOAD_BYTES) {
            throw new IOException("The theme source answered with more than 2 MB.");
        }

        return body;
    }

    private static List<Map<String, Object>> parseThemeList(String body) throws IOException {
        JsonNode root;

        try {
            root = MAPPER.readTree(body);
        } catch (Exception e) {
            throw new IOException("The theme source did not answer with valid JSON.", e);
        }

        JsonNode array = root.isArray() ? root
                : root.path("themes").isArray() ? root.get("themes")
                : null;

        List<Map<String, Object>> themes = new ArrayList<>();

        if (array != null) {
            for (JsonNode item : array) {
                if (item.isObject() && item.hasNonNull("id")) {
                    themes.add(MAPPER.convertValue(item, new TypeReference<>() {}));
                }
            }
        } else if (root.isObject() && root.hasNonNull("id")) {
            themes.add(MAPPER.convertValue(root, new TypeReference<>() {}));
        }

        if (themes.isEmpty()) {
            throw new IOException("The theme source returned no theme with an id.");
        }

        return themes;
    }

    /**
     * Drops what a theme may not carry and refuses what it may not contain.
     *
     * <p>The CSS is checked, the custom properties are filtered name by name, and the particle
     * counts are clamped - an unbounded count is a hung browser tab, which is the one thing a
     * sandbox cannot prevent.
     */
    private static Map<String, Object> sanitise(Map<String, Object> theme) {
        Map<String, Object> clean = new LinkedHashMap<>(theme);

        Object css = clean.get("custom_css");
        Object legacyCss = clean.get("css");

        if (css instanceof String text) {
            clean.put("custom_css", ThemeSecurity.requireSafeCss(text));
        }

        if (legacyCss instanceof String text) {
            clean.put("css", ThemeSecurity.requireSafeCss(text));
        }

        if (clean.get("css_variables") instanceof Map<?, ?> raw) {
            Map<String, String> variables = new LinkedHashMap<>();

            raw.forEach((key, value) -> {
                String name = String.valueOf(key);
                String text = String.valueOf(value);

                if (ThemeSecurity.isSafeVariableName(name)
                        && ThemeSecurity.isSafeVariableValue(text)) {
                    variables.put(name, text);
                } else {
                    logger.debug("Dropped theme variable <{}>: not an allowed name or value.", name);
                }
            });

            clean.put("css_variables", variables);
        }

        if (clean.get("particles") instanceof Map<?, ?> raw) {
            Map<String, Object> particles = new LinkedHashMap<>();
            raw.forEach((key, value) -> particles.put(String.valueOf(key), value));

            particles.put("count", clamp(particles.get("count"), 1, 300, 40));
            particles.put("speed", clamp(particles.get("speed"), 0, 10, 1));

            clean.put("particles", particles);
        }

        return clean;
    }

    private static double clamp(Object raw, double min, double max, double fallback) {
        if (!(raw instanceof Number number)) {
            return fallback;
        }

        return Math.min(max, Math.max(min, number.doubleValue()));
    }

    /** The fingerprint of a theme as the source wrote it, before any of it is dropped. */
    private static String fingerprintOf(Map<String, Object> theme) {
        try {
            // Sorted keys, so the same theme hashes the same way whatever order it arrived in.
            return ThemeSecurity.fingerprint(MAPPER.writer()
                    .with(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                    .writeValueAsString(theme));
        } catch (Exception e) {
            throw new IllegalArgumentException("The theme could not be fingerprinted.", e);
        }
    }

    private static void loadConfig() {
        if (!Files.exists(CONFIG_PATH)) {
            return;
        }

        try {
            JsonNode root = MAPPER.readTree(Files.readString(CONFIG_PATH));

            Map<String, Object> activeTheme = root.path("activeTheme").isObject()
                    ? MAPPER.convertValue(root.get("activeTheme"), new TypeReference<>() {})
                    : null;

            List<Map<String, Object>> available = root.path("cachedThemes").isArray()
                    ? MAPPER.convertValue(root.get("cachedThemes"), new TypeReference<>() {})
                    : List.of();

            String storedFingerprint = root.path("activeFingerprint").asText(null);
            boolean jsApproved = root.path("jsApproved").asBoolean(false);

            // A stored theme is re-checked on the way in, because the file it came from is
            // editable and because the rules may have tightened since it was written.
            if (activeTheme != null) {
                try {
                    activeTheme = sanitise(activeTheme);
                } catch (RuntimeException e) {
                    logger.warn("The stored theme no longer passes validation ({}). "
                            + "Starting without it.", e.getMessage());
                    activeTheme = null;
                    jsApproved = false;
                }
            }

            // An approval belongs to a payload. If the stored theme does not hash to the
            // stored fingerprint the file was edited by hand, and the approval is void.
            if (activeTheme != null && storedFingerprint != null
                    && !storedFingerprint.equals(fingerprintOf(activeTheme))) {
                logger.warn("The stored theme does not match its recorded fingerprint. "
                        + "Its custom JS will not run until it is approved again.");
                jsApproved = false;
            }

            STATE.set(new State(
                    root.path("enabled").asBoolean(true),
                    root.path("sourceUrl").asText(""),
                    activeTheme == null ? null : root.path("activeThemeId").asText(null),
                    activeTheme,
                    storedFingerprint,
                    root.path("approvedBy").asText(null),
                    root.path("approvedAt").asText(null),
                    jsApproved,
                    available,
                    root.path("lastFetched").asText(null),
                    null));
        } catch (Exception e) {
            logger.warn("Could not read theme settings from {}: {}", CONFIG_PATH, e.getMessage());
        }
    }

    /**
     * Writes the settings to disk and records the outcome in the state.
     *
     * <p>A failure here is not cosmetic: the change is already live in memory, so a staff
     * member who is told nothing sees the theme they asked for and then finds it gone after the
     * next restart. The message therefore goes into {@code lastError}, which the panel shows,
     * as well as into the log with its cause attached - a full disk and a read-only mount are
     * different problems and the stack trace is what tells them apart.
     *
     * <p>Called with {@link #WRITE_LOCK} held, and it updates the state itself - setting
     * {@code lastError} on failure and clearing it on success - so the caller must not
     * overwrite the state afterwards.
     */
    private static void saveConfig() {
        State state = STATE.get();

        try {
            Files.createDirectories(CONFIG_PATH.getParent());

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("enabled", state.engineEnabled());
            data.put("sourceUrl", state.sourceUrl());
            data.put("activeThemeId", state.activeThemeId());
            data.put("activeTheme", state.activeTheme());
            data.put("activeFingerprint", state.activeFingerprint());
            data.put("approvedBy", state.approvedBy());
            data.put("approvedAt", state.approvedAt());
            data.put("jsApproved", state.jsApproved());
            data.put("cachedThemes", state.availableThemes());
            data.put("lastFetched", state.lastFetched());

            Files.writeString(CONFIG_PATH,
                    MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(data));

            // A successful write clears a previous failure. Done here rather than left to the
            // callers so that the banner cannot outlive the problem it describes if a future
            // caller forgets to reset it.
            if (state.lastError() != null) {
                State latest = STATE.get();

                STATE.set(new State(latest.engineEnabled(), latest.sourceUrl(),
                        latest.activeThemeId(), latest.activeTheme(), latest.activeFingerprint(),
                        latest.approvedBy(), latest.approvedAt(), latest.jsApproved(),
                        latest.availableThemes(), latest.lastFetched(), null));
            }
        } catch (Exception e) {
            logger.error("Could not save theme settings to {}. The change is live now but will "
                    + "be lost when the service restarts.", CONFIG_PATH, e);

            State latest = STATE.get();

            STATE.set(new State(latest.engineEnabled(), latest.sourceUrl(), latest.activeThemeId(),
                    latest.activeTheme(), latest.activeFingerprint(), latest.approvedBy(),
                    latest.approvedAt(), latest.jsApproved(), latest.availableThemes(),
                    latest.lastFetched(),
                    "This change is live but could not be written to " + CONFIG_PATH
                            + " (" + e.getClass().getSimpleName() + ": " + e.getMessage()
                            + "), so it will be lost when the service restarts."));
        }
    }
}

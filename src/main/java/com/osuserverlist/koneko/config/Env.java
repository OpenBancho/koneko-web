package com.osuserverlist.koneko.config;

import java.util.Arrays;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.github.cdimascio.dotenv.Dotenv;
import lombok.Getter;

/**
 * Deployment settings, read from {@code .env} exactly like bancho.jar does.
 *
 * <p>Only the handful of values that really differ between deployments live
 * here; everything else is a constant below. Anything that describes the site
 * itself (names, texts, links) lives in {@link SiteConfig} instead, which is
 * read from {@code .config/config.yml}.
 */
@Getter
public final class Env {

    private static final Logger logger = LoggerFactory.getLogger("Env");

    /** Directory that holds every configuration file of the site. */
    public static final String CONFIG_DIR = ".config";

    /** The site texts. Not configurable: the location is part of the layout. */
    public static final String CONFIG_PATH = CONFIG_DIR + "/config.yml";

    /**
     * Scopes asked for at login. identify = read own profile, profile = edit it, which is what
     * the settings page needs; moderation, beatmaps and admin are what the staff panel needs.
     *
     * <p>They are requested for everyone because a scope is only permission to ask: the API
     * still refuses the call unless the account holds the matching privilege, so a player
     * carrying an admin scope can do exactly nothing with it. Asking only for staff would mean
     * knowing who is staff before they have logged in.
     *
     * <p>Overridable through {@code API_SCOPES} in {@code .env}.
     */
    public static final String DEFAULT_API_SCOPES = "identify profile moderation beatmaps admin";

    /** Timeout of a single call to the bancho.jar API. */
    public static final int API_TIMEOUT_SECONDS = 10;

    /** How long a logged in browser survives without any request. */
    public static final int SESSION_TIMEOUT_MINUTES = 1440;

    private final int port;
    private final String domain;
    private final String apiBaseUrl;
    private final String level;
    private final String apiClientId;

    /** Whether /register is served at all. */
    private final boolean registrationEnabled;

    private final CaptchaProvider captchaProvider;
    private final String captchaSiteKey;

    private final int fastLoadTtlSeconds;
    private final int fastLoadStaleSeconds;
    private final int fastLoadClientTtlSeconds;

    /**
     * Hosts a theme may be fetched from. Empty means the theme engine cannot fetch anything,
     * which is the default: an operator who has not named a source has not chosen one.
     */
    private final List<String> themeSourceHosts;

    /**
     * Whether a theme's custom JavaScript is executed at all.
     *
     * <p>Lives here, in a file on the server, and not in the panel on purpose. Turning it on
     * means accepting that a theme source can run code in every visitor's browser, and that
     * decision belongs to whoever administers the deployment rather than to whoever happens
     * to hold a staff bit today. Reaching this file means shell access; reaching the panel
     * only means a session.
     *
     * <p>Even when on, the code runs in a sandboxed frame with an opaque origin - it cannot
     * read the page, the session cookie, or call the API as the visitor.
     */
    private final boolean themeCustomJsAllowed;

    private Env(Dotenv dotenv) {
        this.port = intOf(dotenv, "PORT", 8300);
        this.domain = stringOf(dotenv, "DOMAIN", "localhost");
        this.apiBaseUrl = stringOf(dotenv, "API_BASE_URL", "");
        this.level = stringOf(dotenv, "LEVEL", "PROD");
        this.apiClientId = stringOf(dotenv, "API_CLIENT_ID", "koneko-web");
        this.registrationEnabled = booleanOf(dotenv, "REGISTRATION_ENABLED", true);
        String legacySiteKey = stringOf(dotenv, "TURNSTILE_SITE_KEY", "");
        String provider = stringOf(dotenv, "CAPTCHA_PROVIDER", "");

        this.captchaSiteKey = stringOf(dotenv, "CAPTCHA_SITE_KEY", legacySiteKey);
        this.captchaProvider = provider.isEmpty() && !legacySiteKey.isEmpty()
                ? CaptchaProvider.TURNSTILE
                : CaptchaProvider.of(provider);
        this.fastLoadTtlSeconds = intOf(dotenv, "FASTLOAD_TTL_SECONDS", 15);
        this.fastLoadStaleSeconds = intOf(dotenv, "FASTLOAD_STALE_SECONDS", 120);
        this.fastLoadClientTtlSeconds = intOf(dotenv, "FASTLOAD_CLIENT_TTL_SECONDS", 600);
        this.themeSourceHosts = listOf(dotenv, "THEME_SOURCE_HOSTS");
        this.themeCustomJsAllowed = booleanOf(dotenv, "THEME_ALLOW_CUSTOM_JS", false);
    }

    /** Loads {@code .env} from the working directory; a missing file is fine. */
    public static Env load() {
        Dotenv dotenv = Dotenv.configure().systemProperties().ignoreIfMissing().load();
        return new Env(dotenv);
    }

    /** FastLoad is off entirely when the server side window is zero. */
    public boolean isFastLoadEnabled() {
        return fastLoadTtlSeconds > 0 || fastLoadClientTtlSeconds > 0;
    }

    /** True while {@code LEVEL=DEV}: hot reloaded views, no Secure cookies. */
    public boolean isDev() {
        return "DEV".equalsIgnoreCase(level);
    }

    /** Session cookies are only marked Secure outside of DEV. */
    public boolean useSecureCookies() {
        return !isDev();
    }

    public boolean isCaptchaEnabled() {
        return captchaProvider != CaptchaProvider.NONE && !captchaSiteKey.isEmpty();
    }

    public String getCaptchaField() {
        return isCaptchaEnabled() ? captchaProvider.getField() : "";
    }

    private static String stringOf(Dotenv dotenv, String key, String fallback) {
        String value = System.getenv(key);

        if (value == null || value.isBlank()) {
            value = dotenv.get(key);
        }

        return value == null || value.isBlank() ? fallback : value.trim();
    }

    /** A comma separated list, trimmed and without the empties. */
    private static List<String> listOf(Dotenv dotenv, String key) {
        String raw = stringOf(dotenv, key, "");

        if (raw.isBlank()) {
            return List.of();
        }

        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(entry -> !entry.isEmpty())
                .toList();
    }

    private static boolean booleanOf(Dotenv dotenv, String key, boolean fallback) {
        String raw = System.getenv(key);

        if (raw == null || raw.isBlank()) {
            raw = dotenv.get(key);
        }

        if (raw == null || raw.isBlank()) {
            return fallback;
        }

        return Boolean.parseBoolean(raw.trim());
    }

    private static int intOf(Dotenv dotenv, String key, int fallback) {
        String raw = System.getenv(key);

        if (raw == null || raw.isBlank()) {
            raw = dotenv.get(key);
        }

        if (raw == null || raw.isBlank()) {
            return fallback;
        }

        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            logger.warn("<{}> is not a number, falling back to <{}>", key, fallback);
            return fallback;
        }
    }
}

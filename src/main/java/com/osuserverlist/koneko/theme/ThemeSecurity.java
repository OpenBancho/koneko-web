package com.osuserverlist.koneko.theme;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The checks a theme has to pass before it reaches a browser.
 *
 * <p>A theme is data fetched from somewhere else, so everything here treats it as hostile
 * input rather than as configuration. Three separate questions are asked, and they are
 * separate because they fail for different reasons: may this URL be fetched at all, is this
 * payload still the one somebody approved, and is this CSS safe to put on a real page.
 *
 * <p>The one thing deliberately not here is the custom JavaScript. It is not sanitised,
 * because sanitising a language is not a thing that works; it is confined instead, in a
 * sandboxed frame with an opaque origin, which is the frontend's job.
 */
public final class ThemeSecurity {

    /**
     * CSS at-rules that pull in a second stylesheet or reach the network. A theme's CSS lands
     * on the real page, so an {@code @import} is an unreviewed payload arriving after the
     * approved one, and it would carry the visitor's IP to whoever serves it.
     */
    private static final Pattern FORBIDDEN_AT_RULES =
            Pattern.compile("@(?:import|namespace|document)\\b", Pattern.CASE_INSENSITIVE);

    /**
     * Remote {@code url()} targets. Local ones - data: and blob: - stay allowed, because a
     * theme that cannot carry its own background image is not much of a theme.
     *
     * <p>The reason this matters beyond privacy: a selector like
     * {@code input[value^="a"] { background: url(//host/a) }} turns a stylesheet into a
     * keylogger, one request per character.
     */
    private static final Pattern REMOTE_URL = Pattern.compile(
            "url\\s*\\(\\s*['\"]?\\s*(?:https?:)?//", Pattern.CASE_INSENSITIVE);

    /** Legacy IE construct that executes script from a stylesheet. Cheap to refuse. */
    private static final Pattern CSS_EXPRESSION =
            Pattern.compile("expression\\s*\\(|behaviou?r\\s*:|-moz-binding", Pattern.CASE_INSENSITIVE);

    /**
     * The characters a CSS custom property may contain: colours, lengths, keywords and simple
     * lists - the vocabulary of a colour scheme. Notably absent are the colon and the
     * semicolon, so a value cannot close its own declaration and start another one.
     *
     * <p>Parentheses are allowed, because {@code rgb()} and {@code calc()} are the point of a
     * colour scheme, which is why this pattern alone is not enough - see the two below.
     */
    private static final Pattern SAFE_VARIABLE_VALUE =
            Pattern.compile("^[#\\w\\s.,%()/-]{1,120}$");

    /** Finds every function call in a value, so each one can be checked by name. */
    private static final Pattern VARIABLE_FUNCTION_CALL = Pattern.compile("([a-zA-Z-]+)\\s*\\(");

    /**
     * The functions a value may call. An allowlist rather than a denylist because the
     * dangerous ones are the ones that reach the network - {@code url()}, {@code image-set()},
     * {@code attr()} - and new ones keep being added to CSS.
     */
    private static final Pattern VARIABLE_FUNCTION_ALLOWLIST = Pattern.compile(
            "^(?:rgb|rgba|hsl|hsla|calc|var|min|max|clamp|linear-gradient|radial-gradient)$",
            Pattern.CASE_INSENSITIVE);

    private ThemeSecurity() {
    }

    /**
     * Whether a theme source URL may be fetched.
     *
     * <p>Two rules, and the second is the one that matters. The scheme has to be https, so a
     * payload cannot be rewritten in transit by anybody on the path - the theme is code, and
     * plain http would mean the approval covers whatever a network hop decides to substitute.
     * The host has to be on the operator's allowlist, because the alternative is a form in the
     * staff panel that fetches any address the server can reach, including everything behind
     * it that has no authentication precisely because it is not reachable from outside.
     *
     * <p>Localhost is allowed only when the operator names it, which keeps the documented
     * development workflow working without leaving the door open by default.
     *
     * @throws IllegalArgumentException with a message meant for a staff member to read
     */
    public static void requireFetchableUrl(String rawUrl, List<String> allowedHosts) {
        if (rawUrl == null || rawUrl.isBlank()) {
            throw new IllegalArgumentException("A theme source URL is required.");
        }

        URI uri;

        try {
            uri = URI.create(rawUrl.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("That is not a valid URL.");
        }

        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);

        if (host.isEmpty()) {
            throw new IllegalArgumentException("The theme source URL needs a host name.");
        }

        boolean loopbackName = host.equals("localhost") || host.equals("127.0.0.1") || host.equals("[::1]");

        // http is tolerated for a named loopback host only: there is no network hop to
        // intercept, and refusing it would break local theme development for no gain.
        if (!scheme.equals("https") && !(scheme.equals("http") && loopbackName)) {
            throw new IllegalArgumentException("Theme sources must use https.");
        }

        if (allowedHosts == null || allowedHosts.isEmpty()) {
            throw new IllegalArgumentException(
                    "No theme source hosts are allowed. Set THEME_SOURCE_HOSTS in .env first.");
        }

        boolean allowed = allowedHosts.stream()
                .map(entry -> entry.trim().toLowerCase(Locale.ROOT))
                .anyMatch(entry -> !entry.isEmpty() && host.equals(entry));

        if (!allowed) {
            throw new IllegalArgumentException(
                    "Host <" + host + "> is not in THEME_SOURCE_HOSTS.");
        }

        // The allowlist is by name, so a name that resolves inward still has to be refused:
        // a host somebody controls can point at 169.254.169.254 or at a database on the same
        // machine. Checked here rather than trusted from the name alone.
        if (!loopbackName) {
            requirePublicAddress(host);
        }
    }

    /**
     * Refuses a host name that resolves to an address inside the deployment.
     *
     * <p>This is checked before the request and cannot be airtight: the name may resolve
     * differently a moment later, which is the classic rebinding race. It removes the easy
     * case, and the allowlist above is what actually carries the weight.
     */
    private static void requirePublicAddress(String host) {
        InetAddress[] addresses;

        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("Host <" + host + "> does not resolve.");
        }

        for (InetAddress address : addresses) {
            if (address.isLoopbackAddress() || address.isAnyLocalAddress()
                    || address.isLinkLocalAddress() || address.isSiteLocalAddress()
                    || address.isMulticastAddress()) {
                throw new IllegalArgumentException(
                        "Host <" + host + "> resolves inside the network and cannot be fetched.");
            }
        }
    }

    /**
     * The fingerprint an approval is attached to.
     *
     * <p>Consent is given for a payload, not for an address. A source that keeps serving new
     * code under the same URL would otherwise inherit an approval given once, months ago, for
     * something else entirely. The hash is what makes "I trust this source" expire by itself.
     */
    public static String fingerprint(String canonicalJson) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(canonicalJson.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);

            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }

            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is required of every JVM; if it is missing the platform is not one
            // this code can make any promises on.
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    /**
     * Whether a stylesheet may be applied to a real page.
     *
     * <p>CSS cannot be confined the way the JavaScript is: styling the actual page is the whole
     * point of it, so there is no frame to put it in. What is left is refusing the constructs
     * that turn a stylesheet into something other than styling - a second payload, a network
     * side channel, or script execution.
     *
     * @return the CSS unchanged when it passes
     * @throws IllegalArgumentException naming the construct that failed
     */
    public static String requireSafeCss(String css) {
        if (css == null || css.isBlank()) {
            return "";
        }

        if (css.length() > 200_000) {
            throw new IllegalArgumentException("Theme CSS is larger than 200 KB.");
        }

        if (FORBIDDEN_AT_RULES.matcher(css).find()) {
            throw new IllegalArgumentException(
                    "Theme CSS may not use @import, @namespace or @document.");
        }

        if (REMOTE_URL.matcher(css).find()) {
            throw new IllegalArgumentException(
                    "Theme CSS may not reference remote URLs. Inline images as data: URIs.");
        }

        if (CSS_EXPRESSION.matcher(css).find()) {
            throw new IllegalArgumentException(
                    "Theme CSS may not use expression(), behavior or -moz-binding.");
        }

        return css;
    }

    /** Whether a custom property name is one a theme may set. */
    public static boolean isSafeVariableName(String name) {
        return name != null && name.startsWith("--") && name.length() <= 64
                && name.matches("^--[a-zA-Z0-9-]+$");
    }

    /**
     * Whether a custom property value is within the vocabulary of a colour scheme.
     *
     * <p>Functions are allowed by name rather than by shape, so {@code rgb(...)} passes and
     * {@code url(...)} does not, however it is spelled.
     */
    public static boolean isSafeVariableValue(String value) {
        if (value == null || value.isBlank() || !SAFE_VARIABLE_VALUE.matcher(value).matches()) {
            return false;
        }

        // Any function call in the value has to be one of the known-harmless ones.
        Matcher functions = VARIABLE_FUNCTION_CALL.matcher(value);

        while (functions.find()) {
            if (!VARIABLE_FUNCTION_ALLOWLIST.matcher(functions.group(1)).matches()) {
                return false;
            }
        }

        return true;
    }
}

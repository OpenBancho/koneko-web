package com.osuserverlist.koneko.routes;

import java.util.LinkedHashMap;
import java.util.Map;
import java.net.URI;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.osuserverlist.koneko.App;
import com.osuserverlist.koneko.api.ApiException;
import com.osuserverlist.koneko.auth.Auth;
import com.osuserverlist.koneko.auth.UserSession;
import com.osuserverlist.koneko.auth.Verification;

import io.javalin.config.JavalinConfig;
import io.javalin.http.Context;

/**
 * The account of the logged in player: reading it, and changing it.
 *
 * <p>Public data is fetched by the browser straight from the API, but none of
 * this is public. The access token lives in the session here and never reaches
 * the browser, so every call that needs one has to go through this service.
 * These routes are that path and nothing more: the body is handed to the API
 * unchanged and its answer comes back unchanged.
 *
 * <p>The API asks for the current password again on the three dangerous
 * changes, so a stolen session cookie alone cannot take an account over.
 */
public final class AccountRoutes {

    private static final Logger logger = LoggerFactory.getLogger("AccountRoutes");

    /** Cropped 512px PNGs are normally below 1 MB; leave room for noisy images. */
    private static final int MAX_AVATAR_BYTES = 4 * 1024 * 1024;

    /** A badge picture is shown at 48x24, so it never needs to be a big file. */
    private static final int MAX_BADGE_BYTES = 2 * 1024 * 1024;

    /** A cover spans the whole profile card, so it is allowed as much as an avatar. */
    private static final int MAX_BANNER_BYTES = 4 * 1024 * 1024;

    /** What the browser may post, and where it goes on the API. */
    private static final Map<String, String> ACTIONS = Map.of(
            "update", "/api/v1/me/update",
            "name", "/api/v1/me/name",
            "email", "/api/v1/me/email",
            "password", "/api/v1/me/password",
            "delete", "/api/v1/me/delete",
            // Setting up, confirming and removing an authenticator. Which of the three a call
            // means is in the body, so one action covers all of it.
            "2fa", "/api/v1/me/2fa",
            // Friending: adding, accepting, declining, cancelling and removing share one
            // action, which one a call means is in the body - the same arrangement as 2fa.
            "friends", "/api/v1/me/friends");

    private AccountRoutes() {
    }

    public static void register(JavalinConfig config) {
        config.routes.get("/account/me", AccountRoutes::me);
        config.routes.get("/account/2fa", AccountRoutes::twoFactor);
        config.routes.get("/account/friends", AccountRoutes::friends);
        config.routes.get("/account/friends/status", AccountRoutes::friendStatus);
        config.routes.post("/account/avatar", AccountRoutes::avatar);
        config.routes.post("/account/badge-icon", AccountRoutes::badgeIcon);
        config.routes.post("/account/banner", AccountRoutes::banner);
        config.routes.post("/account/{action}", AccountRoutes::action);
    }

    /**
     * Proxies the cropped image without ever exposing the API token to the browser.
     * The API performs the authoritative decode/re-encode checks; the checks here
     * reject oversized and cross-site requests before they consume backend work.
     */
    private static void avatar(Context ctx) {
        UserSession session = Auth.current(ctx);

        if (session == null) {
            deny(ctx);
            return;
        }
        if (Verification.blocksApi(ctx, session)) {
            return;
        }
        if (!sameOrigin(ctx)) {
            ctx.status(403).json(Map.of("status", "Cross-site avatar uploads are not allowed."));
            return;
        }

        String contentType = ctx.contentType();
        if (contentType == null || !contentType.equalsIgnoreCase("image/png")) {
            ctx.status(415).json(Map.of("status", "The cropped avatar must be a PNG image."));
            return;
        }

        long declared = ctx.contentLength();
        if (declared <= 0 || declared > MAX_AVATAR_BYTES) {
            ctx.status(413).json(Map.of("status", "The avatar is too large (4 MB maximum)."));
            return;
        }

        byte[] image = ctx.bodyAsBytes();
        if (image.length == 0 || image.length > MAX_AVATAR_BYTES) {
            ctx.status(413).json(Map.of("status", "The avatar is too large (4 MB maximum)."));
            return;
        }

        try {
            JsonNode body = App.api.requestBytes("POST", "/api/v1/me/avatar", image,
                    "image/png", session.getTokens().getAccessToken());
            ctx.header("Cache-Control", "private, no-store");
            ctx.json(body);
        } catch (ApiException e) {
            fail(ctx, e, "avatar");
        }
    }

    /**
     * The same path for the picture of a custom badge: bytes in, the answer of
     * the API out, and the token never leaves this service. The API decides
     * whether the account may have a badge at all and re-encodes the image.
     */
    private static void badgeIcon(Context ctx) {
        UserSession session = Auth.current(ctx);

        if (session == null) {
            deny(ctx);
            return;
        }
        if (Verification.blocksApi(ctx, session)) {
            return;
        }
        if (!sameOrigin(ctx)) {
            ctx.status(403).json(Map.of("status", "Cross-site badge uploads are not allowed."));
            return;
        }

        String contentType = ctx.contentType();
        if (contentType == null || !contentType.equalsIgnoreCase("image/png")) {
            ctx.status(415).json(Map.of("status", "The badge picture must be a PNG image."));
            return;
        }

        long declared = ctx.contentLength();
        if (declared <= 0 || declared > MAX_BADGE_BYTES) {
            ctx.status(413).json(Map.of("status", "The badge picture is too large (2 MB maximum)."));
            return;
        }

        byte[] image = ctx.bodyAsBytes();
        if (image.length == 0 || image.length > MAX_BADGE_BYTES) {
            ctx.status(413).json(Map.of("status", "The badge picture is too large (2 MB maximum)."));
            return;
        }

        try {
            JsonNode body = App.api.requestBytes("POST", "/api/v1/me/badge", image,
                    "image/png", session.getTokens().getAccessToken());
            ctx.header("Cache-Control", "private, no-store");
            ctx.json(body);
        } catch (ApiException e) {
            fail(ctx, e, "badge");
        }
    }

    /**
     * And the same again for the profile cover. The API decides whether the
     * account may have a banner at all, re-encodes the image and stores it.
     */
    private static void banner(Context ctx) {
        UserSession session = Auth.current(ctx);

        if (session == null) {
            deny(ctx);
            return;
        }
        if (Verification.blocksApi(ctx, session)) {
            return;
        }
        if (!sameOrigin(ctx)) {
            ctx.status(403).json(Map.of("status", "Cross-site banner uploads are not allowed."));
            return;
        }

        String contentType = ctx.contentType();
        if (contentType == null || !contentType.equalsIgnoreCase("image/png")) {
            ctx.status(415).json(Map.of("status", "The banner must be a PNG image."));
            return;
        }

        long declared = ctx.contentLength();
        if (declared <= 0 || declared > MAX_BANNER_BYTES) {
            ctx.status(413).json(Map.of("status", "The banner is too large (4 MB maximum)."));
            return;
        }

        byte[] image = ctx.bodyAsBytes();
        if (image.length == 0 || image.length > MAX_BANNER_BYTES) {
            ctx.status(413).json(Map.of("status", "The banner is too large (4 MB maximum)."));
            return;
        }

        try {
            JsonNode body = App.api.requestBytes("POST", "/api/v1/me/banner", image,
                    "image/png", session.getTokens().getAccessToken());
            ctx.header("Cache-Control", "private, no-store");
            ctx.json(body);
        } catch (ApiException e) {
            fail(ctx, e, "banner");
        }
    }

    private static boolean sameOrigin(Context ctx) {
        String fetchSite = ctx.header("Sec-Fetch-Site");
        if (fetchSite != null && fetchSite.equalsIgnoreCase("cross-site")) {
            return false;
        }

        String origin = ctx.header("Origin");
        if (origin == null || origin.isBlank()) {
            return true;
        }

        try {
            URI uri = URI.create(origin);
            String host = ctx.header("Host");
            return host != null && host.equalsIgnoreCase(uri.getAuthority());
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * The own profile, private fields included: the email, the userpage and the
     * silence and supporter timestamps no one else may see.
     */
    private static void me(Context ctx) {
        UserSession session = Auth.current(ctx);

        if (session == null) {
            deny(ctx);
            return;
        }

        // An account waiting for its first in-game login has nothing to read or change here
        // yet, and the API would refuse the token anyway.
        if (Verification.blocksApi(ctx, session)) {
            return;
        }

        try {
            JsonNode body = App.api.getAuthed("/api/v1/me", Map.of(),
                    session.getTokens().getAccessToken());

            ctx.header("Cache-Control", "private, no-store");
            ctx.json(body);
        } catch (ApiException e) {
            fail(ctx, e, "me");
        }
    }

    /**
     * Whether the account has an authenticator set up.
     *
     * <p>Separate from {@link #me(Context)} because it is read again after every step of the
     * setup, and reloading the whole account for one boolean would be wasteful.
     */
    private static void twoFactor(Context ctx) {
        UserSession session = Auth.current(ctx);

        if (session == null) {
            deny(ctx);
            return;
        }

        if (Verification.blocksApi(ctx, session)) {
            return;
        }

        try {
            JsonNode body = App.api.getAuthed("/api/v1/me/2fa", Map.of(),
                    session.getTokens().getAccessToken());

            ctx.header("Cache-Control", "private, no-store");
            ctx.json(body);
        } catch (ApiException e) {
            fail(ctx, e, "2fa");
        }
    }

    /**
     * The three lists behind the friends page: the mutual friends, and the
     * pending requests in both directions.
     */
    private static void friends(Context ctx) {
        UserSession session = Auth.current(ctx);

        if (session == null) {
            deny(ctx);
            return;
        }

        if (Verification.blocksApi(ctx, session)) {
            return;
        }

        try {
            JsonNode body = App.api.getAuthed("/api/v1/me/friends", Map.of(),
                    session.getTokens().getAccessToken());

            ctx.header("Cache-Control", "private, no-store");
            ctx.json(body);
        } catch (ApiException e) {
            fail(ctx, e, "friends");
        }
    }

    /**
     * How the session's account stands with one other player - nothing, a
     * pending request in either direction, or friends. This is what the
     * button on a profile page is drawn from.
     */
    private static void friendStatus(Context ctx) {
        UserSession session = Auth.current(ctx);

        if (session == null) {
            deny(ctx);
            return;
        }

        if (Verification.blocksApi(ctx, session)) {
            return;
        }

        Map<String, String> query = new LinkedHashMap<>();
        String id = ctx.queryParam("id");

        if (id != null && !id.isBlank()) {
            query.put("id", id.trim());
        }

        try {
            JsonNode body = App.api.getAuthed("/api/v1/me/friends/status", query,
                    session.getTokens().getAccessToken());

            ctx.header("Cache-Control", "private, no-store");
            ctx.json(body);
        } catch (ApiException e) {
            fail(ctx, e, "friendStatus");
        }
    }

    private static void action(Context ctx) {
        String action = ctx.pathParam("action");
        String path = ACTIONS.get(action);

        if (path == null) {
            ctx.status(404).json(Map.of("status", "Unknown account action."));
            return;
        }

        UserSession session = Auth.current(ctx);

        if (session == null) {
            deny(ctx);
            return;
        }

        // An account waiting for its first in-game login has nothing to read or change here
        // yet, and the API would refuse the token anyway.
        if (Verification.blocksApi(ctx, session)) {
            return;
        }

        boolean endsSession = "password".equals(action) || "delete".equals(action);

        try {
            JsonNode body = App.api.request("POST", path, null, ctx.body(),
                    "application/json", session.getTokens().getAccessToken());

            // A new password revokes every other session of the account on the
            // API side, and a deleted account has none left at all. Either way
            // the tokens behind this browser are done, so the session goes with
            // them rather than lingering as a login that can no longer call
            // anything.
            if (endsSession) {
                Auth.destroy(ctx);
            }

            Map<String, Object> answer = new LinkedHashMap<>();
            answer.put("status", "success");
            answer.put("body", body);
            answer.put("loggedOut", endsSession);

            ctx.json(answer);
        } catch (ApiException e) {
            fail(ctx, e, action);
        }
    }

    private static void deny(Context ctx) {
        ctx.status(401).json(Map.of("status", "Log in to change your account."));
    }

    /**
     * Hands the API's own status and message on. A wrong password is the
     * player's business and not worth a log line; anything unexpected is.
     */
    private static void fail(Context ctx, ApiException e, String action) {
        if (e.getStatus() != 400 && e.getStatus() != 401 && e.getStatus() != 403) {
            logger.warn("Account action <{}> failed with {}: {}",
                    action, e.getStatus(), e.getMessage());
        }

        ctx.status(e.getStatus()).json(Map.of("status", e.getMessage()));
    }
}

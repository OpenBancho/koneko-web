package com.osuserverlist.koneko.routes;

import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.osuserverlist.koneko.App;
import com.osuserverlist.koneko.api.ApiException;
import com.osuserverlist.koneko.auth.Auth;
import com.osuserverlist.koneko.auth.StaffTwoFactor;
import com.osuserverlist.koneko.auth.UserSession;
import com.osuserverlist.koneko.auth.Verification;

import io.javalin.config.JavalinConfig;
import io.javalin.http.Context;

/**
 * The staff panel's path to the API.
 *
 * <p>The panel needs an access token for every call it makes, and that token lives in the
 * session on this side and must never reach the browser. So the panel talks to these routes
 * and these routes talk to the API, passing the body and the answer through unchanged.
 *
 * <p>The gate here is deliberately coarse: it only asks "is this account staff at all", which
 * is enough to keep the public out. It does not try to decide whether a nominator may wipe an
 * account, because the API already decides that, per endpoint, from the token's scopes and the
 * account's privileges. Duplicating those rules here would create a second copy to keep in
 * step, and the copy that drifts is the one that lets someone through.
 */
public final class AdminRoutes {

    private static final Logger logger = LoggerFactory.getLogger("AdminRoutes");

    /** Reads the one field the gate takes; the rest of what a browser posts there is ignored. */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    // The four staff bits, mirrored from the API's Privileges. Used only to decide
    // whether the panel opens and whether the navigation shows a link.
    private static final int NOMINATOR = 1 << 11;
    private static final int MODERATOR = 1 << 12;
    private static final int ADMINISTRATOR = 1 << 13;
    private static final int DEVELOPER = 1 << 14;

    private static final int STAFF_MASK = NOMINATOR | MODERATOR | ADMINISTRATOR | DEVELOPER;

    /** What the panel may read, and where it comes from. */
    private static final Map<String, String> READS = Map.of(
            "access", "/api/v1/admin/access",
            "players", "/api/v1/admin/players",
            "player", "/api/v1/admin/player",
            "logs", "/api/v1/admin/logs",
            "requests", "/api/v1/admin/requests",
            "system", "/api/v1/admin/system",
            "groups", "/api/v1/admin/groups",
            "group-members", "/api/v1/admin/groups/members");

    /**
     * What the panel may do, and where it goes.
     *
     * <p>An allowlist rather than a prefix rewrite: a panel that can forward any path it likes
     * to the admin API is a panel that will eventually forward one nobody meant it to.
     */
    private static final Map<String, String> WRITES = Map.ofEntries(
            Map.entry("restrict", "/api/v1/admin/restrict"),
            Map.entry("unrestrict", "/api/v1/admin/unrestrict"),
            Map.entry("silence", "/api/v1/admin/silence"),
            Map.entry("unsilence", "/api/v1/admin/unsilence"),
            Map.entry("note", "/api/v1/admin/note"),
            Map.entry("wipe", "/api/v1/admin/wipe"),
            Map.entry("donator", "/api/v1/admin/donator"),
            Map.entry("privileges-add", "/api/v1/admin/privileges/add"),
            Map.entry("privileges-remove", "/api/v1/admin/privileges/remove"),
            Map.entry("name", "/api/v1/admin/user/name"),
            // Answers with a reset ticket rather than changing anything. The API keeps it
            // behind the developer privilege; the coarse gate here is unchanged on purpose.
            Map.entry("password-reset", "/api/v1/admin/user/password-reset"),
            Map.entry("country", "/api/v1/admin/user/country"),
            Map.entry("alert", "/api/v1/admin/alert"),
            Map.entry("beatmap-status", "/api/v1/admin/beatmap/status"),
            Map.entry("requests-resolve", "/api/v1/admin/requests/resolve"),
            // Groups: the page writes through one endpoint, membership through another.
            Map.entry("groups", "/api/v1/admin/groups"),
            Map.entry("group-members", "/api/v1/admin/groups/members"));

    private AdminRoutes() {
    }

    public static void register(JavalinConfig config) {
        config.routes.get("/admin/api/{action}", AdminRoutes::read);
        config.routes.post("/admin/api/{action}", AdminRoutes::write);
        // The gate itself, which is the one thing here that works before the gate is open.
        config.routes.post(StaffTwoFactor.PATH, AdminRoutes::stepUp);
    }

    /**
     * Whether an account may open the panel at all.
     *
     * <p>Shared with the view routes and the navigation. Being a bitmask, an administrator is
     * not implicitly a moderator: an account holds exactly the bits it was given, and the
     * sections it sees follow from those.
     */
    public static boolean isStaff(UserSession session) {
        return session != null && (session.getPrivileges() & STAFF_MASK) != 0;
    }

    private static void read(Context ctx) {
        String action = ctx.pathParam("action");
        String path = READS.get(action);

        if (path == null) {
            ctx.status(404).json(Map.of("status", "Unknown panel request."));
            return;
        }

        UserSession session = Auth.current(ctx);

        if (!isStaff(session)) {
            deny(ctx);
            return;
        }

        // Staff or not, an account that has never logged into the game is not usable yet.
        if (Verification.blocksApi(ctx, session)) {
            return;
        }

        // Being staff is not enough: the session has to have answered a code as well.
        if (StaffTwoFactor.blocksApi(ctx, session)) {
            return;
        }

        try {
            JsonNode body = App.api.getAuthed(path, query(ctx),
                    session.getTokens().getAccessToken());

            // Staff data is never cacheable: a shared browser must not keep a
            // player list from someone else's session.
            ctx.header("Cache-Control", "private, no-store");
            ctx.json(body);
        } catch (ApiException e) {
            fail(ctx, e, action);
        }
    }

    private static void write(Context ctx) {
        String action = ctx.pathParam("action");
        String path = WRITES.get(action);

        if (path == null) {
            ctx.status(404).json(Map.of("status", "Unknown panel action."));
            return;
        }

        UserSession session = Auth.current(ctx);

        if (!isStaff(session)) {
            deny(ctx);
            return;
        }

        // Staff or not, an account that has never logged into the game is not usable yet.
        if (Verification.blocksApi(ctx, session)) {
            return;
        }

        if (StaffTwoFactor.blocksApi(ctx, session)) {
            return;
        }

        try {
            JsonNode body = App.api.request("POST", path, null, ctx.body(),
                    "application/json", session.getTokens().getAccessToken());

            // The API keeps the real audit trail. This line is for the web log, so
            // that a panel misbehaving is visible from this side too.
            logger.info("Staff <{}> performed <{}>", session.getUsername(), action);

            Map<String, Object> answer = new LinkedHashMap<>();
            answer.put("status", "success");
            answer.put("body", body);

            ctx.header("Cache-Control", "private, no-store");
            ctx.json(answer);
        } catch (ApiException e) {
            fail(ctx, e, action);
        }
    }

    /**
     * Answers the gate: takes a code from the authenticator and, if the API accepts it, opens
     * the panel for this session.
     *
     * <p>The body is not forwarded as it arrived. The API endpoint behind this takes an action,
     * and one of its actions removes the authenticator - so the code is pulled out and a new
     * body is built around {@code verify} here. A route that passed the browser's own body on
     * would be a route that lets a page turn 2FA off by asking nicely.
     */
    private static void stepUp(Context ctx) {
        UserSession session = Auth.current(ctx);

        if (!isStaff(session)) {
            deny(ctx);
            return;
        }

        if (Verification.blocksApi(ctx, session)) {
            return;
        }

        String code;

        try {
            code = MAPPER.readTree(ctx.body()).path("code").asText("").replaceAll("[^0-9]", "");
        } catch (Exception e) {
            ctx.status(400).json(Map.of("status", "The code is six digits."));
            return;
        }

        if (code.length() != 6) {
            ctx.status(400).json(Map.of("status", "The code is six digits."));
            return;
        }

        // Digits only by now, so there is nothing in it that could escape the quotes.
        String body = "{\"action\":\"verify\",\"code\":\"" + code + "\"}";

        try {
            App.api.request("POST", "/api/v1/me/2fa", null, body,
                    "application/json", session.getTokens().getAccessToken());

            StaffTwoFactor.accept(session);

            ctx.header("Cache-Control", "private, no-store");
            ctx.json(Map.of("status", "success"));
        } catch (ApiException e) {
            // A wrong code is the caller's business; the API has already logged the attempt.
            fail(ctx, e, "verify");
        }
    }

    /** The browser's query string, flattened: the API takes no repeated parameters. */
    private static Map<String, String> query(Context ctx) {
        Map<String, String> params = new LinkedHashMap<>();

        ctx.queryParamMap().forEach((key, values) -> {
            if (values != null && !values.isEmpty()) {
                params.put(key, values.get(0));
            }
        });

        return params;
    }

    private static void deny(Context ctx) {
        ctx.status(403).json(Map.of("status", "This page is for staff."));
    }

    /**
     * Hands the API's own status and message on, so the panel can show what actually went
     * wrong: a reason too short, a name already taken, a privilege the caller does not have.
     * Those are ordinary answers and not worth a log line; anything else is.
     */
    private static void fail(Context ctx, ApiException e, String action) {
        int status = e.getStatus();

        if (status != 400 && status != 401 && status != 403 && status != 404) {
            logger.warn("Panel action <{}> failed with {}: {}", action, status, e.getMessage());
        }

        ctx.status(status).json(Map.of("status", e.getMessage()));
    }
}

package com.osuserverlist.koneko.routes;

import java.util.LinkedHashMap;
import java.util.Map;

import com.osuserverlist.koneko.App;
import com.osuserverlist.koneko.api.ApiException;
import com.osuserverlist.koneko.auth.Auth;
import com.osuserverlist.koneko.auth.StaffTwoFactor;
import com.osuserverlist.koneko.auth.UserSession;
import com.osuserverlist.koneko.auth.Verification;
import com.osuserverlist.koneko.vue.KonekoVue;

import io.javalin.config.JavalinConfig;
import io.javalin.http.Context;
import io.javalin.http.Handler;

/**
 * The pages. Every one of them is a single Vue component, so the routing table
 * stays a table and all markup lives in the .vue files.
 *
 * <p>Every page except the verification page itself goes through
 * {@link Verification}: an account that registered on the website but has never logged into
 * the game is sent to {@code /verify} whatever it asks for. The check is per route rather than
 * one filter in front of everything, so which pages are open is readable from this table
 * instead of from a list of exceptions somewhere else.
 */
public final class ViewRoutes {

    private ViewRoutes() {
    }

    public static void register(JavalinConfig config) {
        config.routes.get("/", page("home-view"));
        config.routes.get("/login", page("login-view"));

        // Only served while REGISTRATION_ENABLED is on, so a closed server
        // answers a plain 404 instead of a form nothing will accept.
        if (App.env.isRegistrationEnabled()) {
            config.routes.get("/register", page("register-view"));
        }

        // The one page an unverified account may open, and the only one that is not gated:
        // this is where the gate explains itself.
        Handler verifyPage = KonekoVue.component("verify-view");

        config.routes.get(Verification.PATH, ctx -> verify(ctx, verifyPage));

        config.routes.get("/u/{identifier}", profilePage());
        config.routes.get("/leaderboard", page("leaderboard-view"));
        // Open to everybody, including an account that is restricted: it is
        // the page that explains what a restriction is.
        config.routes.get("/restrictions", KonekoVue.component("restrictions-view"));
        // Redeeming a password reset link staff handed out. Ungated for the same reason the
        // login form is: whoever opens it cannot log in, which is why they were given a link.
        config.routes.get("/reset-password", KonekoVue.component("reset-password-view"));
        // Where the game client sends a player whose account asks for a code: the client opens
        // it by itself and the person looking at it is, by definition, not logged in anywhere -
        // so this one is ungated too. The client hash in the address is what identifies the
        // login being answered, and a code from the authenticator is what settles it.
        config.routes.get("/verify-client", KonekoVue.component("verify-client-view"));

        config.routes.get("/beatmaps", page("beatmaps-view"));
        config.routes.get("/beatmapsets/{setId}", page("beatmapset-view"));
        // Not in the navigation: reached through the search box in the bar.
        config.routes.get("/players", page("players-view"));
        config.routes.get("/scores/{scoreId}", page("score-view"));

        // Only renders forms; what may actually be changed is decided by the
        // API when the form is submitted.
        config.routes.get("/settings", page("settings-view"));

        // Convenience route so the navigation bar can link to a profile
        // without knowing the id up front.
        config.routes.get("/me", ctx -> {
            UserSession session = Auth.current(ctx);

            if (session == null) {
                ctx.redirect("/login");
                return;
            }

            if (Verification.blocksPage(ctx)) {
                return;
            }

            ctx.redirect("/u/" + session.getUserId());
        });

        // The staff panel. Which sections a page actually shows is decided by the
        // API; these routes only keep the pages away from players entirely.
        // Every panel address serves the same component, which reads the path and
        // renders the section that belongs to it. These routes still exist so a
        // typed or bookmarked address is checked for staff rights before anything
        // is served, and so an unknown /admin path is still a 404.
        Handler panel = staffPage("admin-panel-view");

        config.routes.get("/admin", panel);
        config.routes.get("/admin/requests", panel);
        config.routes.get("/admin/moderation", panel);
        config.routes.get("/admin/moderation/{userId}", panel);
        config.routes.get("/admin/logs", panel);
        config.routes.get("/admin/server", panel);

        // The staff gate, and the one panel address it does not guard - the same arrangement
        // /verify has with the verification gate: this is the page that explains itself.
        Handler staffGate = KonekoVue.component("admin-verify-view");

        config.routes.get(StaffTwoFactor.PATH, ctx -> adminVerify(ctx, staffGate));

        // Unknown pages render inside the same shell instead of a bare Jetty
        // error page.
        config.routes.error(404, "html", KonekoVue.component("not-found-view", 404));
    }

    /**
     * An ordinary page: rendered for everyone, except an account that still owes the server an
     * in-game login.
     *
     * <p>Visitors who are not logged in are not affected. Being unverified is a property of an
     * account, and someone browsing without one simply sees the public site.
     */
    private static Handler page(String component) {
        Handler rendered = KonekoVue.component(component);

        return ctx -> {
            if (Verification.blocksPage(ctx)) {
                return;
            }

            rendered.handle(ctx);
        };
    }

    /**
     * A profile.
     *
     * <p>A restricted account is hidden from the whole public site, its own page included, so
     * this asks the API whether the account behind the address may be shown to whoever is
     * asking before rendering anything. When it may not, the not-found page is served with a
     * real {@code 404} rather than a profile that would fill itself with errors, and the answer
     * is the same one an address nobody ever registered gets: nothing here says whether the
     * account exists.
     *
     * <p>Staff are the exception, and they get the page. The decision is the API's; this only
     * carries the session's token over to it, because the browser has no token of its own.
     */
    private static Handler profilePage() {
        Handler page = KonekoVue.component("profile-view");
        Handler missing = KonekoVue.component("not-found-view", 404);

        return ctx -> {
            if (Verification.blocksPage(ctx)) {
                return;
            }

            if (visibleProfile(ctx, ctx.pathParam("identifier"))) {
                page.handle(ctx);
                return;
            }

            missing.handle(ctx);
        };
    }

    /**
     * Whether the API is willing to describe this player to this visitor.
     *
     * <p>Only a {@code 404} is taken as an answer about the account. Anything else means the
     * API is having a bad moment, and a page that would have rendered is not turned into a
     * not-found because of it: the profile is served and its own error handling takes over.
     */
    private static boolean visibleProfile(Context ctx, String identifier) {
        if (identifier == null || identifier.isBlank()) {
            return false;
        }

        Map<String, String> query = new LinkedHashMap<>();

        // A numeric segment is an id, anything else a name, the way the API expects it.
        query.put(identifier.matches("[0-9]+") ? "id" : "name", identifier);
        // Only asking whether it may be seen at all; the page fetches the rest itself.
        query.put("scope", "info");

        UserSession session = Auth.current(ctx);

        try {
            if (session == null) {
                App.api.get("/api/v1/get_player_details", query);
            } else {
                App.api.getAuthed("/api/v1/get_player_details", query,
                        session.getTokens().getAccessToken());
            }

            return true;
        } catch (ApiException e) {
            return e.getStatus() != 404;
        }
    }

    /**
     * The verification page.
     *
     * <p>Only reachable while it is needed: a visitor with no session is sent to the login
     * form, and an account that has already logged into the game is sent to its profile, so
     * the page cannot be bookmarked into a dead end. It also re-checks with the API on every
     * load, which is what makes the redirect happen by itself once the player connects.
     */
    private static void verify(Context ctx, Handler page) throws Exception {
        UserSession session = Auth.current(ctx);

        if (session == null) {
            ctx.redirect("/login");
            return;
        }

        if (Verification.verified(session)) {
            ctx.redirect("/u/" + session.getUserId());
            return;
        }

        page.handle(ctx);
    }

    /**
     * A page only staff may open.
     *
     * <p>A visitor who is not logged in is sent to the login form, because that is probably what
     * they need. A logged in player is sent home instead of being told a staff panel exists.
     *
     * <p>This only decides whether the shell is rendered. The data behind it is fetched
     * separately and authorised again by the API, so a hand written URL gets an empty page at
     * worst, never someone else's data.
     */
    private static Handler staffPage(String component) {
        Handler page = KonekoVue.component(component);

        return ctx -> {
            UserSession session = Auth.current(ctx);

            if (session == null) {
                ctx.redirect("/login");
                return;
            }

            // Checked before the staff bits, because an unverified account has no business
            // reaching the panel even if someone handed it a moderator bit early.
            if (Verification.blocksPage(ctx)) {
                return;
            }

            if (!AdminRoutes.isStaff(session)) {
                ctx.redirect("/");
                return;
            }

            // The staff bits say who this account is; the code says it is really them at this
            // browser. The panel wants both.
            if (StaffTwoFactor.blocksPage(ctx, session)) {
                return;
            }

            page.handle(ctx);
        };
    }

    /**
     * The staff gate.
     *
     * <p>Reachable only while it is needed, the way the verification page is: a visitor with no
     * session goes to the login form, a player who is not staff goes home rather than being told
     * a panel exists, and a session that has already answered a code is sent on to the panel so
     * the page cannot be sat on as a dead end.
     */
    private static void adminVerify(Context ctx, Handler page) throws Exception {
        UserSession session = Auth.current(ctx);

        if (session == null) {
            ctx.redirect("/login");
            return;
        }

        if (Verification.blocksPage(ctx)) {
            return;
        }

        if (!AdminRoutes.isStaff(session)) {
            ctx.redirect("/");
            return;
        }

        if (StaffTwoFactor.satisfied(session)) {
            ctx.redirect("/admin");
            return;
        }

        page.handle(ctx);
    }
}

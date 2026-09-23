package com.osuserverlist.koneko.vue;

import java.util.LinkedHashMap;
import java.util.Map;

import com.osuserverlist.koneko.App;
import com.osuserverlist.koneko.auth.Auth;
import com.osuserverlist.koneko.auth.UserSession;
import com.osuserverlist.koneko.auth.Verification;
import com.osuserverlist.koneko.config.CaptchaProvider;
import com.osuserverlist.koneko.config.SiteConfig;
import com.osuserverlist.koneko.plugin.PluginBootstrap;
import com.osuserverlist.koneko.plugin.PluginService;
import com.osuserverlist.koneko.plugin.api.Events;
import com.osuserverlist.koneko.plugin.api.PluginUser;

import io.javalin.http.Context;

/**
 * Builds the object every page needs before it can render anything: the site
 * texts and who is looking at it.
 *
 * <p>It reaches the browser as a plain blocking script from
 * {@code /data/bootstrap.js}, so the data is on the page before Vue starts and
 * no component has to wait for a fetch to render the shell.
 */
public final class VueState {

    private VueState() {
    }

    /** The state of the current request, ready to be serialised to JSON. */
    public static Map<String, Object> of(Context ctx) {
        Map<String, Object> state = new LinkedHashMap<>();

        state.put("site", site());
        state.put("domain", App.env.getDomain());
        state.put("user", user(ctx));
        state.put("fastload", fastload());
        state.put("registration", registration());

        // What the plugins contribute to every page: their slots, their
        // navigation entries and their own state keys.
        UserSession session = Auth.current(ctx);

        PluginUser user = session == null ? null : new PluginUser(session.getUserId(),
                session.getUsername(), session.getPrivileges(), null);

        state.put("plugins", PluginBootstrap.state(ctx, user));
        state.put("theme", com.osuserverlist.koneko.theme.ThemeService.getPublicBootstrap());

        // Last, so a plugin may still add or replace a top level key.
        if (PluginService.enabled()) {
            PluginService.events().publish(new Events.Bootstrap(ctx, state, user));
        }

        return state;
    }

    private static Map<String, Object> registration() {
        boolean captcha = App.env.isCaptchaEnabled();
        CaptchaProvider provider = captcha ? App.env.getCaptchaProvider() : CaptchaProvider.NONE;

        Map<String, Object> registration = new LinkedHashMap<>();
        registration.put("enabled", App.env.isRegistrationEnabled());
        registration.put("captcha", captcha);
        registration.put("captchaProvider", provider.name());
        registration.put("captchaSiteKey", captcha ? App.env.getCaptchaSiteKey() : "");
        registration.put("captchaField", provider.getField());
        registration.put("captchaScript", provider.getScriptUrl());
        registration.put("captchaGlobal", provider.getGlobalName());

        return registration;
    }

    /** FastLoad settings the browser needs to know about. */
    private static Map<String, Object> fastload() {
        Map<String, Object> fastload = new LinkedHashMap<>();
        fastload.put("enabled", App.env.getFastLoadClientTtlSeconds() > 0);
        fastload.put("clientTtl", App.env.getFastLoadClientTtlSeconds());

        return fastload;
    }

    private static Map<String, Object> site() {
        SiteConfig config = App.site;

        Map<String, Object> site = new LinkedHashMap<>();
        site.put("name", config.getServer().getName());
        site.put("tagline", config.getServer().getTagline());
        site.put("description", config.getServer().getDescription());

        Map<String, Object> links = new LinkedHashMap<>();
        links.put("discord", config.getLinks().getDiscord());
        links.put("github", config.getLinks().getGithub());
        links.put("apiDocs", config.getLinks().getApiDocs());
        site.put("links", links);

        Map<String, Object> home = new LinkedHashMap<>();
        home.put("showStats", config.getHome().isShowStats());
        home.put("showLeaderboard", config.getHome().isShowLeaderboard());
        home.put("leaderboardSize", config.getHome().getLeaderboardSize());
        home.put("leaderboardMode", config.getHome().getLeaderboardMode());
        home.put("showNewPlayers", config.getHome().isShowNewPlayers());
        home.put("newPlayersSize", config.getHome().getNewPlayersSize());
        home.put("showTopScores", config.getHome().isShowTopScores());
        home.put("topScoresSize", config.getHome().getTopScoresSize());
        home.put("showConnectGuide", config.getHome().isShowConnectGuide());
        home.put("heroImage", config.getHome().getHeroImage());
        home.put("logoImage", config.getHome().getLogoImage());
        home.put("announcement", config.getHome().getAnnouncement());
        home.put("announcementLink", config.getHome().getAnnouncementLink());
        site.put("home", home);

        return site;
    }

    /** The logged in player, or null. Never the tokens. */
    public static Map<String, Object> user(Context ctx) {
        UserSession session = Auth.current(ctx);

        if (session == null) {
            return null;
        }

        // Re-checked here rather than read from the session, because this runs on every page
        // load: it is what turns the verification page back into an ordinary site the moment
        // the player has logged into the game.
        boolean verified = Verification.verified(session);

        Map<String, Object> user = new LinkedHashMap<>();
        user.put("id", session.getUserId());
        user.put("name", session.getUsername());
        user.put("priv", session.getPrivileges());
        user.put("verified", verified);
        // Read straight off the privileges, so the banner appears on the next
        // page load after a restriction and disappears the same way.
        user.put("restricted", session.isRestricted());

        if (!verified) {
            user.put("verifyPath", Verification.PATH);
            user.put("verifyMessage", Verification.MESSAGE);
        }

        return user;
    }
}

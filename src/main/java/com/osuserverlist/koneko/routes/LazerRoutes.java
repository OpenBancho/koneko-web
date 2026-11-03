package com.osuserverlist.koneko.routes;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.osuserverlist.koneko.App;
import com.osuserverlist.koneko.api.ApiException;

import io.javalin.config.JavalinConfig;
import io.javalin.http.Context;

/**
 * The part of the site fed by lazer.jar.
 *
 * <p>lazer.jar is a server of its own, separate from bancho.jar, so it has its own client ({@link App#lazer}), its own
 * setting ({@code LAZER_API_URL}) and its own routes here. Nothing on these routes touches bancho.jar, and when
 * lazer.jar is not linked every route answers {@code {"enabled": false}} so the pages simply leave the lazer cards out.
 *
 * <p>The browser never talks to lazer.jar directly and never gets its raw answers: this class asks for the few public
 * profile reads it needs and hands the page a small, flat shape. Free text from players (the about-me page) goes out as
 * plain text, never as HTML.
 */
public final class LazerRoutes {

    private static final Logger logger = LoggerFactory.getLogger("LazerRoutes");

    /** How the ruleset numbers of this site (plain 0-3, +4 relax, 8 autopilot) are spelled to lazer.jar. */
    private static final String[] MODE_NAMES = { "osu", "taiko", "fruits", "mania", "rx", "rx!taiko", "rx!catch", "rx!mania", "ap" };

    private LazerRoutes() {
    }

    public static void register(JavalinConfig config) {
        config.routes.get("/data/lazer/status", LazerRoutes::status);
        config.routes.get("/data/lazer/profile/{id}", LazerRoutes::profile);
    }

    private static void status(Context ctx) {
        ctx.header("Cache-Control", "public, max-age=60");
        ctx.json(Map.of("enabled", App.lazer != null));
    }

    private static void profile(Context ctx) {
        Map<String, Object> out = new LinkedHashMap<>();

        if (App.lazer == null) {
            out.put("enabled", false);
            ctx.json(out);
            return;
        }

        String id = ctx.pathParam("id");

        if (!id.matches("\\d{1,9}")) {
            ctx.status(400).json(Map.of("status", "Invalid player id."));
            return;
        }

        int modeId = 0;

        try {
            modeId = Math.max(0, Math.min(Integer.parseInt(ctx.queryParam("mode")), MODE_NAMES.length - 1));
        } catch (NumberFormatException ignored) {
            // no or unreadable mode: plain osu!
        }

        String mode = MODE_NAMES[modeId];
        out.put("enabled", true);

        // The user card carries the about-me page and the beatmap counters. A 404 means lazer.jar does not know the
        // player; the other cards are then pointless.
        try {
            JsonNode user = App.lazer.get("/api/v2/users/" + id + "/" + mode, Map.of());
            out.put("found", true);
            out.put("page", text(user.path("page").path("raw")));
            out.put("counts", counts(user));
        } catch (ApiException e) {
            if (e.getStatus() == 404) {
                out.put("found", false);
                ctx.header("Cache-Control", "public, max-age=30");
                ctx.json(out);
                return;
            }

            logger.warn("lazer.jar profile of {} unavailable: {}", id, e.getMessage());
            out.put("found", false);
            out.put("error", "lazer.jar could not be reached.");
            ctx.status(502).json(out);
            return;
        }

        out.put("pinned", pinned(id, mode));
        out.put("activity", activity(id));

        ctx.header("Cache-Control", "public, max-age=30");
        ctx.json(out);
    }

    private static Map<String, Object> counts(JsonNode user) {
        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("favourites", user.path("favourite_beatmapset_count").asInt(0));
        counts.put("ranked", user.path("ranked_beatmapset_count").asInt(0));
        counts.put("loved", user.path("loved_beatmapset_count").asInt(0));
        counts.put("pending", user.path("pending_beatmapset_count").asInt(0));
        counts.put("graveyard", user.path("graveyard_beatmapset_count").asInt(0));
        counts.put("comments", user.path("comments_count").asInt(0));
        return counts;
    }

    private static List<Map<String, Object>> pinned(String id, String mode) {
        List<Map<String, Object>> list = new ArrayList<>();

        try {
            JsonNode scores = App.lazer.get("/api/v2/users/" + id + "/scores/pinned",
                    Map.of("mode", mode, "limit", "10"));

            for (JsonNode s : scores) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", s.path("id").asLong());
                row.put("rank", s.path("rank").asText(""));
                row.put("pp", s.path("pp").isNumber() ? s.path("pp").asDouble() : null);
                row.put("accuracy", s.path("accuracy").asDouble(0));
                row.put("max_combo", s.path("max_combo").asInt(0));
                row.put("total_score", s.path("total_score").asLong(0));
                row.put("ended_at", text(s.path("ended_at")));
                row.put("has_replay", s.path("has_replay").asBoolean(false));
                row.put("set_id", s.path("beatmapset").path("id").asInt(0));
                row.put("artist", s.path("beatmapset").path("artist").asText(""));
                row.put("title", s.path("beatmapset").path("title").asText(""));
                row.put("version", s.path("beatmap").path("version").asText(""));
                row.put("stars", s.path("beatmap").path("difficulty_rating").asDouble(0));

                List<String> mods = new ArrayList<>();

                for (JsonNode m : s.path("mods")) {
                    String acronym = m.path("acronym").asText("");

                    if (!acronym.isBlank()) {
                        mods.add(acronym);
                    }
                }

                row.put("mods", mods);
                list.add(row);
            }
        } catch (ApiException e) {
            logger.debug("lazer.jar pinned scores of {} unavailable: {}", id, e.getMessage());
        }

        return list;
    }

    private static List<Map<String, Object>> activity(String id) {
        List<Map<String, Object>> list = new ArrayList<>();

        try {
            JsonNode events = App.lazer.get("/api/v2/users/" + id + "/recent_activity", Map.of("limit", "10"));

            for (JsonNode e : events) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", e.path("id").asLong());
                row.put("type", e.path("type").asText(""));
                row.put("created_at", text(e.path("created_at")));
                row.put("grade", e.path("scoreRank").asText(""));
                row.put("position", e.path("rank").asInt(0));
                row.put("mode", e.path("mode").asText(""));
                row.put("title", e.path("beatmap").path("title").asText(""));
                row.put("beatmap_url", e.path("beatmap").path("url").asText(""));
                list.add(row);
            }
        } catch (ApiException e) {
            logger.debug("lazer.jar activity of {} unavailable: {}", id, e.getMessage());
        }

        return list;
    }

    private static String text(JsonNode node) {
        return node == null || node.isMissingNode() || node.isNull() ? null : node.asText();
    }
}

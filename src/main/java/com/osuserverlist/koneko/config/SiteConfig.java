package com.osuserverlist.koneko.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.Getter;
import lombok.Setter;

/**
 * The texts of the site, read from {@code config.yml}.
 *
 * <p>Every field has a usable default, so an empty or partial yml file still
 * renders a complete page. Unknown keys are ignored on purpose: a newer
 * config file never breaks an older build.
 */
@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public final class SiteConfig {

    private ServerSection server = new ServerSection();
    private LinksSection links = new LinksSection();
    private HomeSection home = new HomeSection();
    private PluginsSection plugins = new PluginsSection();

    /** Identity of the osu! server this frontend belongs to. */
    @Getter
    @Setter
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class ServerSection {
        /** Shown in the navigation bar, the page titles and the footer. */
        private String name = "Koneko";

        /** Optional one-liner under the name in the navigation bar. */
        private String tagline = "";

        /** The block of text on the front page. Line breaks are kept. */
        private String description = "Set your description in config.yml.";
    }

    /** Outgoing links. Empty values are hidden by the frontend. */
    @Getter
    @Setter
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class LinksSection {
        private String discord = "";
        private String github = "";

        @JsonProperty("api_docs")
        private String apiDocs = "";
    }

    /**
     * The .jar plugins loaded from the plugins directory.
     *
     * <p>Plugin specific settings live under {@code settings}, keyed by plugin
     * id, and a plugin may also ship its own {@code .config/plugins/{id}.yml},
     * which wins over this block.
     */
    @Getter
    @Setter
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class PluginsSection {
        /** Turns the whole plugin host off; .env can override it. */
        private boolean enabled = true;

        /** Plugin ids that are loaded but never started. */
        private List<String> disabled = new ArrayList<>();

        /** Per plugin settings, keyed by plugin id. */
        private Map<String, Map<String, Object>> settings = new LinkedHashMap<>();

        /** The settings of one plugin, or an empty map. */
        public Map<String, Object> settingsOf(String pluginId) {
            if (settings == null || pluginId == null) {
                return Map.of();
            }

            Map<String, Object> values = settings.get(pluginId);

            return values == null ? Map.of() : values;
        }
    }

    /** What the front page shows. */
    @Getter
    @Setter
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class HomeSection {
        @JsonProperty("show_stats")
        private boolean showStats = true;

        @JsonProperty("show_leaderboard")
        private boolean showLeaderboard = true;

        @JsonProperty("leaderboard_size")
        private int leaderboardSize = 5;

        @JsonProperty("leaderboard_mode")
        private int leaderboardMode = 0;

        /** The "new players" panel: who registered most recently. */
        @JsonProperty("show_new_players")
        private boolean showNewPlayers = true;

        @JsonProperty("new_players_size")
        private int newPlayersSize = 5;

        /** The "best scores" panel: the highest pp plays on the server. */
        @JsonProperty("show_top_scores")
        private boolean showTopScores = true;

        @JsonProperty("top_scores_size")
        private int topScoresSize = 5;

        /** The looping replay video of the best osu!standard score on the server, rendered by bancho.jar. */
        @JsonProperty("show_best_replay")
        private boolean showBestReplay = true;

        @JsonProperty("show_connect_guide")
        private boolean showConnectGuide = true;

        /**
         * Background of the welcome band. Any image URL; empty leaves the
         * plain gradient, which is a complete look on its own.
         */
        @JsonProperty("hero_image")
        private String heroImage = "";

        /** Server logo. Empty falls back to the initials of the name. */
        @JsonProperty("logo_image")
        private String logoImage = "";

        /** One line strip above the welcome band. Empty hides it. */
        private String announcement = "";

        /** Optional target for the link at the end of the strip. */
        @JsonProperty("announcement_link")
        private String announcementLink = "";
    }
}

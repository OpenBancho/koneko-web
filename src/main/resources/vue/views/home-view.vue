<template id="home-view">
    <div class="page">
        <site-nav></site-nav>

        <koneko-slot name="home.top"></koneko-slot>
        <koneko-slot name="page.top"></koneko-slot>

        <div class="announcement" v-if="home.announcement">
            <span class="announcement-icon" aria-hidden="true">i</span>
            <span>{{ home.announcement }}</span>
            <a v-if="home.announcementLink" :href="home.announcementLink"
               target="_blank" rel="noopener">here!</a>
        </div>

        <section class="hero-band" :style="heroStyle">
            <div class="hero-inner">
                <div class="hero-logo">
                    <img v-if="home.logoImage" :src="home.logoImage" :alt="site.name">
                    <span v-else>{{ initials }}</span>
                </div>

                <h1 class="hero-title">Play osu! on {{ site.name }}</h1>
                <p class="hero-tagline" v-if="site.tagline">{{ site.tagline }}</p>
                <p class="hero-tagline" v-else>Your own ranking, your own beatmaps, your own scores.</p>

                <div class="hero-actions">
                    <a class="button" v-if="!user" href="/login">Log in and play</a>
                    <a class="button" v-else :href="'/u/' + user.id">My profile</a>

                    <a class="button button-ghost" v-if="home.showConnectGuide" href="#connect">Set up the game</a>
                    <a class="button button-ghost" v-else-if="links.discord"
                       :href="links.discord" target="_blank" rel="noopener">Join the Discord</a>
                </div>
            </div>
        </section>

        <section class="stat-strip" v-if="home.showStats">
            <div class="stat-strip-item">
                <span class="stat-strip-value">{{ fmtNumber(stat('players')) }}</span>
                <span class="stat-strip-label">Registered players</span>
            </div>
            <div class="stat-strip-item">
                <span class="stat-strip-value">{{ fmtNumber(stat('online')) }}</span>
                <span class="stat-strip-label">Online now</span>
            </div>
            <div class="stat-strip-item">
                <span class="stat-strip-value">{{ fmtNumber(stat('beatmaps')) }}</span>
                <span class="stat-strip-label">Beatmaps</span>
            </div>
            <div class="stat-strip-item">
                <span class="stat-strip-value">{{ fmtNumber(stat('scores')) }}</span>
                <span class="stat-strip-label">Total scores</span>
            </div>
        </section>

        <section class="tiles">
            <a class="tile" :href="home.showConnectGuide ? '#connect' : '/login'">
                <span class="tile-icon" aria-hidden="true">
                    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6">
                        <path d="M8 5l11 7-11 7z" stroke-linejoin="round"></path>
                    </svg>
                </span>
                <span class="tile-title">Play</span>
                <span class="tile-text">Connect to {{ site.name }} and start playing today.</span>
            </a>

            <a class="tile" href="/leaderboard">
                <span class="tile-icon" aria-hidden="true">
                    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6">
                        <path d="M8 4h8v5a4 4 0 01-8 0z" stroke-linejoin="round"></path>
                        <path d="M16 5h3v2a3 3 0 01-3 3M8 5H5v2a3 3 0 003 3"></path>
                        <path d="M12 13v4M9 20h6" stroke-linecap="round"></path>
                    </svg>
                </span>
                <span class="tile-title">Leaderboard</span>
                <span class="tile-text">Compare with other players and climb the rankings.</span>
            </a>

            <a class="tile" href="/beatmaps">
                <span class="tile-icon" aria-hidden="true">
                    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6">
                        <circle cx="12" cy="12" r="8"></circle>
                        <circle cx="12" cy="12" r="2.5"></circle>
                    </svg>
                </span>
                <span class="tile-title">Beatmaps</span>
                <span class="tile-text">Browse every map on the server, ranked and submitted.</span>
            </a>

            <a class="tile" v-if="links.discord" :href="links.discord" target="_blank" rel="noopener">
                <span class="tile-icon" aria-hidden="true">
                    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6">
                        <circle cx="9" cy="9" r="3"></circle>
                        <path d="M3.5 19a5.5 5.5 0 0111 0" stroke-linecap="round"></path>
                        <path d="M16 7a3 3 0 010 6M17 19a5.5 5.5 0 00-1.6-3.9" stroke-linecap="round"></path>
                    </svg>
                </span>
                <span class="tile-title">Community</span>
                <span class="tile-text">Meet the team and join our growing community.</span>
            </a>
        </section>

        <!-- The two front page panels: who just joined, and the best plays on
             the server. Either can be turned off in config.yml, and each one
             greys out on its own when the API cannot answer, so a missing list
             never takes the front page down with it. -->
        <section class="home-panels" v-if="home.showNewPlayers || home.showTopScores">
            <div class="card home-panel" v-if="home.showNewPlayers">
                <div class="home-panel-head">
                    <h2>New players</h2>
                </div>

                <p class="muted" v-if="loading && !newPlayers.length">Loading...</p>
                <p class="muted" v-else-if="!newPlayers.length">Nobody has registered yet.</p>

                <ul class="home-list" v-else>
                    <li class="home-list-row" v-for="player in newPlayers" :key="player.id">
                        <img class="home-list-avatar" :src="avatarUrl(player.id)" :alt="player.name">

                        <div class="home-list-main">
                            <a class="home-list-name" :href="'/u/' + player.id">
                                <span class="country-tag" v-if="flagClass(player.country)"
                                    :class="flagClass(player.country)"
                                    :title="player.country.toUpperCase()"></span>
                                {{ player.name }}
                            </a>
                            <span class="home-list-sub">joined {{ fmtRelative(player.creation_time) }}</span>
                        </div>
                    </li>
                </ul>
            </div>

            <div class="card home-panel" v-if="home.showTopScores">
                <div class="home-panel-head">
                    <h2>Best scores</h2>
                    <a class="home-panel-more" href="/leaderboard">Leaderboard</a>
                </div>

                <p class="muted" v-if="loading && !topScores.length">Loading...</p>
                <p class="muted" v-else-if="!topScores.length">No score has been set yet.</p>

                <ul class="home-list" v-else>
                    <li class="home-list-row" v-for="(score, index) in topScores"
                        :key="score.id || index">
                        <span class="home-list-rank">{{ index + 1 }}</span>

                        <div class="home-list-main">
                            <a class="home-list-name" v-if="score.beatmap"
                                :href="'/scores/' + score.id">
                                {{ score.beatmap.artist }} - {{ score.beatmap.title }}
                                <span class="muted" v-if="score.beatmap.version">[{{ score.beatmap.version }}]</span>
                            </a>
                            <a class="home-list-name" v-else :href="'/scores/' + score.id">Unknown beatmap</a>

                            <span class="home-list-sub">
                                <a v-if="score.player" :href="'/u/' + score.player.id">{{ score.player.name }}</a>
                                <span v-else>Unknown player</span>
                                &middot; {{ fmtAccuracy(score.acc) }}
                                <span v-if="scoreMods(score).length">&middot; {{ scoreMods(score).join(", ") }}</span>
                            </span>
                        </div>

                        <span class="home-list-pp">{{ fmtDecimal(score.pp, 0) }}<span class="unit">pp</span></span>
                    </li>
                </ul>
            </div>
        </section>

        <section class="card" id="connect" v-if="home.showConnectGuide">
            <h2>How to connect</h2>

            <p class="hero-description">{{ site.description }}</p>

            <ol class="guide">
                <li>Create an account with <code>!register</code> in game, or ask on the Discord.</li>
                <li>Start osu! with <code>osu!.exe -devserver {{ domain }}</code>.</li>
                <li>Log in with your {{ site.name }} username and password.</li>
            </ol>
        </section>

        <section class="card" v-else>
            <h2>About {{ site.name }}</h2>
            <p class="hero-description">{{ site.description }}</p>
        </section>

                <koneko-slot name="page.bottom"></koneko-slot>
        <koneko-slot name="home.bottom"></koneko-slot>

        <site-footer></site-footer>
    </div>
</template>

<script>
    app.component("home-view", {
        template: "#home-view",
        data: () => ({
            loading: true,
            stats: null,
            newPlayers: [],
            topScores: []
        }),
        computed: {
            site() {
                return this.$koneko.site || {};
            },
            home() {
                return this.site.home || {};
            },
            links() {
                return this.site.links || {};
            },
            user() {
                return this.$koneko.user;
            },
            domain() {
                return this.$koneko.domain;
            },
            initials() {
                const name = String(this.site.name || "").trim();

                if (!name) return "?";

                return name.split(/\s+/).slice(0, 2)
                    .map(word => word.charAt(0).toUpperCase()).join("");
            },
            // The band keeps its gradient when no image is configured, so the
            // front page looks finished out of the box.
            heroStyle() {
                const image = this.home.heroImage;

                return image ? { backgroundImage: "url('" + image + "')" } : {};
            }
        },
        methods: {
            stat(name) {
                return this.stats ? this.stats[name] : null;
            },
            // Avatars come from the avatar host of this server, not from the
            // site itself, so the domain of the deployment decides the address.
            avatarUrl(id) {
                return "https://a." + this.$koneko.domain + "/a/" + id;
            },
            scoreMods(score) {
                return this.fmtMods(score.mods);
            },
            // Both panels answer with the standard paginated envelope.
            rowsOf(body) {
                return (body && body.results) || [];
            },
            // The API has spelled its counters more than one way, so the names
            // the strip uses are resolved here. DataRoutes used to do this.
            counters(stats) {
                if (!stats) return null;

                const pick = (...names) => {
                    for (const name of names) {
                        if (typeof stats[name] === "number") return stats[name];
                    }

                    return null;
                };

                return {
                    online: pick("onlinePlayers", "online_players", "online"),
                    players: pick("totalPlayers", "total_players", "players"),
                    beatmaps: pick("maps", "beatmaps", "totalMaps"),
                    scores: pick("scores", "totalScores")
                };
            },
            apply(body) {
                this.stats = body.stats || null;
                this.newPlayers = body.newPlayers || [];
                this.topScores = body.topScores || [];
            }
        },
        async created() {
            this.setTitle(null);

            // FastLoad: paint the numbers of the last visit right away, so
            // the page is never empty while the API answer is on its way.
            const cached = this.fastLoad("home");

            if (cached) {
                this.apply(cached);
                this.loading = false;
            }

            try {
                // The counters come from the API on this origin, so the front
                // page is no longer proxied through /data/home.
                // The three of them are independent, so they are asked for at
                // once instead of one after the other. The panels use the
                // quiet call: a panel that fails greys out, the page does not.
                const [stats, players, scores] = await Promise.all([
                    this.home.showStats
                        ? this.api("get_server_stats")
                        : Promise.resolve(null),
                    this.home.showNewPlayers
                        ? this.apiQuietly("get_recent_players",
                            { limit: this.home.newPlayersSize || 5 })
                        : Promise.resolve(null),
                    this.home.showTopScores
                        ? this.apiQuietly("get_top_scores",
                            { limit: this.home.topScoresSize || 5 })
                        : Promise.resolve(null)
                ]);

                const body = {
                    stats: this.home.showStats ? this.counters(stats) : null,
                    newPlayers: this.rowsOf(players),
                    topScores: this.rowsOf(scores)
                };

                this.apply(body);
                this.fastSave("home", body);
            } catch (e) {
                // The cached page stays on screen when the API is down.
            } finally {
                this.loading = false;
            }
        }
    });
</script>

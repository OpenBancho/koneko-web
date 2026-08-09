<template id="score-view">
    <div class="page page-wide">
        <site-nav></site-nav>

        <koneko-slot name="score.top"></koneko-slot>
        <koneko-slot name="page.top"></koneko-slot>

        <section class="card" v-if="loading">
            <div class="skeleton skeleton-title"></div>
            <div class="skeleton-lines">
                <div class="skeleton skeleton-line" v-for="n in 5" :key="n"></div>
            </div>
        </section>

        <section class="card" v-else-if="error">
            <h2>Score not found</h2>
            <p class="muted">{{ error }}</p>
            <a class="button" href="/leaderboard">Back to the leaderboard</a>
        </section>

        <template v-else>
            <header class="listing-head">
                <h1>Score</h1>
            </header>

            <!-- The whole play is one panel: the map it was set on across the
                 top, the play itself on the cover of the set, and the player
                 with the numbers along the bottom. The game's own score page is
                 arranged this way, and the reason it is one panel rather than
                 three cards is that all of it is one thing. -->
            <section class="score-panel">
                <div class="score-panel-head">
                    <h2 class="score-map-title" v-if="map">
                        <a :href="'/beatmapsets/' + map.set_id">{{ map.title }}</a>
                        <span class="muted">by {{ map.artist }}</span>
                    </h2>
                    <h2 class="score-map-title" v-else>Score #{{ score.id }}</h2>

                    <p class="score-map-sub" v-if="map">
                        <span class="score-mode-orb" :title="modeName(score.mode)">
                            {{ modeInitial }}
                        </span>

                        <span class="star-pill" v-if="map.diff > 0"
                            :style="{ background: starColor(map.diff) }">
                            &#9733; {{ fmtDecimal(map.diff) }}
                        </span>
                        <span class="star-pill star-pill-unrated" v-else
                            title="The star rating of this difficulty has not been calculated">
                            &#9733; not rated
                        </span>

                        <span class="score-version">{{ map.version }}</span>
                        <span class="muted">mapped by {{ map.creator }}</span>
                    </p>
                </div>

                <div class="score-banner">
                    <div class="score-banner-cover" v-if="coverStyle" :style="coverStyle"></div>

                    <div class="score-banner-inner">
                        <!-- The grades in a column, with the one that was earned
                             lit. Which grade a play got only means something
                             next to the ones it did not get. -->
                        <div class="grade-ladder">
                            <span class="grade-step" v-for="step in ladder" :key="step"
                                :class="[ 'grade-' + step.toLowerCase(),
                                          { active: step === ladderGrade } ]">{{ step }}</span>
                        </div>

                        <div class="score-banner-main">
                            <div class="score-mod-chips" v-if="mods.length">
                                <span class="mod-chip" v-for="mod in mods" :key="mod">{{ mod }}</span>
                            </div>

                            <div class="score-banner-row">
                                <div class="score-grade-huge" :class="'grade-' + gradeClass">
                                    {{ score.grade }}
                                </div>

                                <div class="score-total-block">
                                    <div class="score-total">{{ fmtNumber(score.score) }}</div>
                                </div>

                                <dl class="score-facts">
                                    <div class="score-fact">
                                        <dt>Player</dt>
                                        <dd>
                                            <a v-if="player" :href="playerUrl">{{ player.name }}</a>
                                            <span v-else class="muted">unknown</span>
                                        </dd>
                                    </div>
                                    <div class="score-fact">
                                        <dt>Set</dt>
                                        <dd>{{ fmtDateTime(score.play_time) }}</dd>
                                    </div>
                                    <div class="score-fact">
                                        <dt>Mode</dt>
                                        <dd>{{ modeName(score.mode) }}</dd>
                                    </div>
                                    <div class="score-fact" v-if="map">
                                        <dt>Status</dt>
                                        <dd>{{ statusName(map.status) }}</dd>
                                    </div>
                                </dl>
                            </div>

                            <div class="score-banner-foot">
                                <!-- Only a submitted best holds a place on the
                                     board, so an overwritten play shows none
                                     rather than a rank it never held. -->
                                <div class="world-ranking" v-if="score.rank">
                                    <span class="world-ranking-label">World ranking</span>
                                    <span class="world-ranking-value">#{{ fmtNumber(score.rank) }}</span>
                                </div>
                                <div v-else></div>

                                <!-- Offered only when a file was actually kept,
                                     so the button never leads to a 404. -->
                                <a class="button" v-if="score.replay_available"
                                    :href="'/api/v1/get_replay?id=' + score.id">Download replay</a>
                            </div>
                        </div>
                    </div>
                </div>

                <div class="score-panel-foot">
                    <a class="score-player-card" v-if="player" :href="playerUrl">
                        <img class="score-player-avatar" :src="avatarUrl" :alt="player.name"
                            loading="lazy">
                        <span class="score-player-text">
                            <span class="score-player-name">{{ player.name }}</span>
                            <span class="score-player-meta">
                                <span class="country-tag" v-if="flagClass(player.country)"
                                    :class="flagClass(player.country)"
                                    :title="String(player.country).toUpperCase()"></span>
                                <span class="muted small">{{ String(player.country || "").toUpperCase() }}</span>
                            </span>
                        </span>
                    </a>
                    <div v-else></div>

                    <div class="score-metrics">
                        <div class="metric-row">
                            <div class="metric">
                                <span class="metric-label">Accuracy</span>
                                <span class="metric-value">{{ fmtAccuracy(score.acc) }}</span>
                            </div>
                            <div class="metric">
                                <span class="metric-label">Max combo</span>
                                <span class="metric-value">
                                    {{ fmtNumber(score.max_combo) }}x<template
                                        v-if="map && map.max_combo"><span class="muted">/{{ fmtNumber(map.max_combo) }}</span></template>
                                </span>
                            </div>
                            <div class="metric">
                                <span class="metric-label">pp</span>
                                <span class="metric-value">{{ fmtDecimal(score.pp, 0) }}</span>
                            </div>
                        </div>

                        <!-- The judgements, in the order the client counts them.
                             Geki and katu only exist in the modes that have
                             them, so they appear only when the play carries
                             any. -->
                        <div class="metric-row">
                            <div class="metric judgement j-300">
                                <span class="metric-label">Great</span>
                                <span class="metric-value">{{ fmtNumber(score.n300) }}</span>
                            </div>
                            <div class="metric judgement j-geki" v-if="score.ngeki">
                                <span class="metric-label">Geki</span>
                                <span class="metric-value">{{ fmtNumber(score.ngeki) }}</span>
                            </div>
                            <div class="metric judgement j-100">
                                <span class="metric-label">Ok</span>
                                <span class="metric-value">{{ fmtNumber(score.n100) }}</span>
                            </div>
                            <div class="metric judgement j-katu" v-if="score.nkatu">
                                <span class="metric-label">Katu</span>
                                <span class="metric-value">{{ fmtNumber(score.nkatu) }}</span>
                            </div>
                            <div class="metric judgement j-50">
                                <span class="metric-label">Meh</span>
                                <span class="metric-value">{{ fmtNumber(score.n50) }}</span>
                            </div>
                            <div class="metric judgement j-miss">
                                <span class="metric-label">Miss</span>
                                <span class="metric-value">{{ fmtNumber(score.nmiss) }}</span>
                            </div>
                        </div>
                    </div>
                </div>
            </section>

            <section class="card" v-if="map">
                <h3>Beatmap</h3>

                <div class="map-stats">
                    <span><b>CS</b> {{ fmtDecimal(map.cs) }}</span>
                    <span><b>AR</b> {{ fmtDecimal(map.ar) }}</span>
                    <span><b>OD</b> {{ fmtDecimal(map.od) }}</span>
                    <span><b>HP</b> {{ fmtDecimal(map.hp) }}</span>
                    <span><b>Stars</b> {{ fmtDecimal(map.diff) }}</span>
                    <span><b>BPM</b> {{ fmtNumber(map.bpm) }}</span>
                    <span><b>Length</b> {{ fmtLength(map.total_length) }}</span>
                </div>

                <p class="muted small">
                    {{ fmtNumber(map.plays) }} plays, {{ fmtNumber(map.passes) }} passes<template
                        v-if="score.time_elapsed">, this play took
                        {{ fmtLength(Math.round(score.time_elapsed / 1000)) }}</template>
                </p>

                <div class="score-links">
                    <a class="button" :href="'/beatmapsets/' + map.set_id">Beatmap page</a>
                    <a class="button button-quiet" :href="'osu://b/' + map.id">Open in osu!</a>
                </div>
            </section>
        </template>

        <koneko-slot name="page.bottom"></koneko-slot>
        <site-footer></site-footer>
    </div>
</template>

<script>
    /**
     * One score in full. Every score row on the site links here, which is what
     * makes a play something that can be linked to at all - the leaderboard row
     * only has room for the numbers, not for the judgements, the rank or the
     * map it was set on.
     *
     * The score answer already carries its beatmap and its player, so this is a
     * single call.
     */
    app.component("score-view", {
        template: "#score-view",
        data: () => ({
            loading: true,
            error: "",
            score: null,
            // Highest first, the way the column is read.
            ladder: ["SS", "S", "A", "B", "C", "D"]
        }),
        computed: {
            map() {
                return (this.score && this.score.beatmap) || null;
            },
            player() {
                const player = this.score && this.score.player;

                return player && player.id ? player : null;
            },
            playerUrl() {
                return this.player
                    ? "/u/" + this.player.id + "?mode=" + this.score.mode
                    : "";
            },
            avatarUrl() {
                return this.player
                    ? "https://a." + this.$koneko.domain + "/" + this.player.id
                    : "";
            },
            coverStyle() {
                const setId = this.map && this.map.set_id;

                if (!setId) return null;

                return { backgroundImage: 'url("' + this.coverUrl(setId, "cover") + '")' };
            },
            mods() {
                return this.fmtMods(this.score && this.score.mods);
            },
            gradeClass() {
                return String((this.score && this.score.grade) || "f")
                    .toLowerCase()
                    .replace("+", "plus");
            },
            /**
             * The rung of the ladder this play lit.
             *
             * The server writes the hidden grades as XH and SH and the plain
             * ones as X and S; the column shows one rung for both, because the
             * silver variant is the same grade earned with a mod on.
             */
            ladderGrade() {
                const grade = String((this.score && this.score.grade) || "").toUpperCase();

                if (grade === "X" || grade === "XH" || grade === "SS" || grade === "SSH") {
                    return "SS";
                }

                if (grade === "SH") return "S";

                return grade;
            },
            modeInitial() {
                return ["o", "t", "c", "m"][Number((this.score && this.score.mode) || 0) % 4] || "o";
            },
            scoreId() {
                const parts = window.location.pathname.split("/").filter(Boolean);

                return parts[parts.length - 1] || "";
            }
        },
        methods: {
            // The star rating colours of the client, so the pill reads the same
            // way the song select does.
            starColor(stars) {
                const value = Number(stars || 0);

                if (value <= 0) return "#5a5a63";

                if (value < 2) return "#4fc0ff";
                if (value < 2.7) return "#4fffd5";
                if (value < 4) return "#7cff4e";
                if (value < 5.3) return "#f2f261";
                if (value < 6.5) return "#ff8068";
                if (value < 8) return "#ff4e6f";

                return "#a653ff";
            },
            // A play happened at a minute, not on a day: the date alone would
            // lose which of the evening's attempts this was.
            fmtDateTime(value) {
                const date = this.asDate(value);

                return date ? date.toLocaleString() : "-";
            },
            async load() {
                const key = "score:" + this.scoreId;
                const cached = this.fastLoad(key);

                if (cached) {
                    this.score = cached;
                    this.loading = false;
                }

                try {
                    const answer = await this.api("get_score_details", { id: this.scoreId });

                    this.score = (answer && answer.score) || null;

                    if (!this.score) {
                        this.error = "This score does not exist any more.";
                    } else {
                        this.fastSave(key, this.score);

                        // The tab says whose play it is, so a handful of open
                        // score pages can be told apart.
                        const map = this.map
                            ? this.map.artist + " - " + this.map.title
                            : "Score";

                        this.setTitle(this.player
                            ? this.player.name + " on " + map
                            : map);
                    }
                } catch (e) {
                    if (!cached) {
                        this.error = e && e.status === 404
                            ? "This score does not exist."
                            : "The score could not be loaded.";
                    }
                } finally {
                    this.loading = false;
                }
            }
        },
        created() {
            this.setTitle("Score");
            this.load();
        }
    });
</script>

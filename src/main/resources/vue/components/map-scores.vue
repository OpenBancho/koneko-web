<template id="map-scores">
    <section class="card map-scores">
        <div class="map-scores-head">
            <h2>Top scores</h2>
            <span class="badge" v-if="beatmap">{{ beatmap.version }}</span>
            <span class="muted" v-if="total">{{ fmtNumber(total) }} submitted</span>

            <!-- Both pickers sit in the corner of the card: the mode first,
                 then the variant, the way the ranking page orders them. -->
            <div class="score-pickers">
                <div class="variant-picker">
                    <button class="variant-chip" v-for="option in modes" :key="option.id"
                        :class="{ active: base === option.id }"
                        :disabled="!option.allowed"
                        :title="option.title"
                        @click="pickMode(option.id)">{{ option.name }}</button>
                </div>

                <div class="variant-picker">
                    <button class="variant-chip" v-for="option in variants" :key="option.id"
                        :class="{ active: variant === option.id }"
                        :disabled="!option.allowed"
                        :title="option.title"
                        @click="pickVariant(option.id)">{{ option.name }}</button>
                </div>
            </div>
        </div>

        <!-- Plugins can put anything of their own above the scoreboard. -->
        <koneko-slot name="beatmapset.scores" :context="{ beatmap: beatmap, scores: rows }"></koneko-slot>

        <p class="muted" v-if="loading && !rows.length">Loading the scores...</p>
        <p class="muted" v-else-if="error">{{ error }}</p>
        <p class="muted" v-else-if="!rows.length">Nobody has played this difficulty on {{ boardName }} yet.</p>

        <table class="table" v-else>
            <thead>
                <tr>
                    <th>#</th>
                    <th>Player</th>
                    <th class="numeric">Score</th>
                    <th class="numeric">Accuracy</th>
                    <th class="numeric">Combo</th>
                    <th>Mods</th>
                    <th class="numeric">pp</th>
                    <th>When</th>
                    <!-- The arrow column: the row is clickable, and an empty
                         header would not say so. -->
                    <th></th>
                </tr>
            </thead>
            <tbody>
                <!-- The reader's own score is highlighted, the way the in-game
                     leaderboard does it.

                     The whole row opens the play: a row is a score, so clicking
                     it goes to the score. The player name inside it keeps
                     leading to the profile, which is why the handler ignores
                     clicks that landed on a link. -->
                <tr v-for="(score, index) in rows" :key="score.id"
                    class="score-clickable"
                    :class="{ 'score-mine': isMine(score) }"
                    :title="score.id ? 'Open this score' : null"
                    @click="open(score, $event)">
                    <td class="score-place">{{ index + 1 }}</td>
                    <td>
                        <span class="country-tag" v-if="flagClass(country(score))"
                            :class="flagClass(country(score))"
                            :title="country(score).toUpperCase()"></span>
                        <a v-if="score.player && score.player.id"
                            :href="'/u/' + score.player.id + (mode ? '?mode=' + mode : '')">{{ score.player.name }}</a>
                        <span v-else class="muted">unknown</span>
                    </td>
                    <td class="numeric">{{ fmtNumber(score.score) }}</td>
                    <td class="numeric">{{ fmtAccuracy(score.acc) }}</td>
                    <td class="numeric">
                        {{ fmtNumber(score.max_combo) }}x
                        <span class="muted" v-if="score.perfect">FC</span>
                    </td>
                    <td>
                        <span class="score-grade-cell" :class="'grade-' + gradeClass(score)">{{ score.grade }}</span>
                        <span class="mods" v-if="fmtMods(score.mods).length">{{ fmtMods(score.mods).join(", ") }}</span>
                    </td>
                    <td class="numeric">{{ fmtDecimal(score.pp, 0) }}</td>
                    <td class="muted">{{ fmtRelative(score.play_time) }}</td>
                    <td class="score-open">
                        <a v-if="score.id" :href="'/scores/' + score.id"
                            title="Score details">&rsaquo;</a>
                    </td>
                </tr>
            </tbody>
        </table>

        <div class="pager" v-if="hasMore">
            <button class="button button-ghost" :disabled="loading" @click="more">
                {{ loading ? "Loading..." : "Show more" }}
            </button>
        </div>
    </section>
</template>

<script>
    /**
     * The leaderboard of one difficulty.
     *
     * Scores belong to a beatmap, not to a set, so this is fetched per
     * difficulty and refetched whenever another one, another mode or another
     * variant is picked. Every answer is kept, so going back and forth costs
     * one request per board and nothing after that.
     */
    app.component("map-scores", {
        template: "#map-scores",
        props: {
            // The selected difficulty, as it arrives in the set payload.
            beatmap: { type: Object, default: null }
        },
        data: () => ({
            loading: false,
            error: "",
            rows: [],
            total: 0,
            offset: 0,
            limit: 50,
            // The mode board being looked at, 0-3. Null until a difficulty is
            // known, then it follows the difficulty.
            base: null,
            // "", "relax" or "autopilot", empty meaning vanilla.
            variant: "",
            // "beatmapId:mode" -> { rows, total }, so a board is fetched once.
            cache: {}
        }),
        computed: {
            // The mode the difficulty was made for.
            nativeMode() {
                return Number((this.beatmap && this.beatmap.mode) || 0);
            },
            // The same numbering the server uses: plain modes are 0-3, relax
            // shifts by four and autopilot is standard only.
            mode() {
                const base = this.base === null ? this.nativeMode : this.base;

                if (this.variant === "autopilot") return 8;
                if (this.variant === "relax") return base + 4;

                return base;
            },
            /**
             * Standard difficulties are converted by the client, so they also
             * carry taiko, catch and mania boards. A difficulty made for one of
             * those three has no converts, so only its own board exists.
             */
            modes() {
                const convertible = this.nativeMode === 0;

                return [
                    { id: 0, name: "STD", title: "osu!" },
                    { id: 1, name: "TAIKO", title: "osu!taiko" },
                    { id: 2, name: "CTB", title: "osu!catch" },
                    { id: 3, name: "MANIA", title: "osu!mania" }
                ].map(option => ({
                    ...option,
                    allowed: convertible || option.id === this.nativeMode
                }));
            },
            variants() {
                const base = this.base === null ? this.nativeMode : this.base;

                return [
                    { id: "", name: "VN", title: "Vanilla", allowed: true },
                    // Mania has no relax, and autopilot is standard only.
                    { id: "relax", name: "RX", title: "Relax", allowed: base !== 3 },
                    { id: "autopilot", name: "AP", title: "Autopilot", allowed: base === 0 }
                ];
            },
            // What the empty state calls the board, e.g. "osu!taiko Relax".
            boardName() {
                const mode = this.modes.find(option => option.id === this.mode % 4);
                const variant = this.variants.find(option => option.id === this.variant);
                const name = this.variant === "autopilot" ? "osu!" : (mode ? mode.title : "osu!");

                return this.variant ? name + " " + variant.title : name;
            },
            hasMore() {
                return this.rows.length > 0 && this.rows.length < this.total;
            }
        },
        watch: {
            // Picking another difficulty in the bar lands here.
            beatmap: {
                immediate: true,
                handler(next, previous) {
                    const id = next && next.id;

                    if (!id || (previous && previous.id === id)) return;

                    // The board follows the difficulty: a taiko difficulty
                    // opens on its own board, and a picker the new difficulty
                    // cannot offer falls back instead of asking for a board
                    // that cannot exist.
                    this.base = this.nativeMode;

                    const picked = this.variants.find(option => option.id === this.variant);

                    if (picked && !picked.allowed) {
                        this.variant = "";
                    }

                    this.show();
                }
            }
        },
        methods: {
            country(score) {
                return (score.player && score.player.country) || "";
            },
            gradeClass(score) {
                return String(score.grade || "f").toLowerCase().replace("+", "plus");
            },
            /**
             * Opens the score behind a row.
             *
             * A click that landed on a link is left alone - that is the profile
             * link, and it means the profile. A modified click is left alone as
             * well, so opening a play in a new tab keeps working.
             */
            open(score, event) {
                if (!score || !score.id) return;
                if (event.target.closest("a")) return;
                if (event.metaKey || event.ctrlKey || event.shiftKey || event.button !== 0) return;

                window.location.href = "/scores/" + score.id;
            },
            isMine(score) {
                const user = this.$koneko.user;

                return !!(user && score.player && Number(user.id) === Number(score.player.id));
            },
            pickMode(id) {
                if (this.base === id) return;

                this.base = id;

                // Autopilot exists on standard only, relax on everything but
                // mania, so switching the mode may drop the variant.
                if (this.variant === "autopilot" && id !== 0) this.variant = "";
                if (this.variant === "relax" && id === 3) this.variant = "";

                this.show();
            },
            pickVariant(id) {
                if (this.variant === id) return;

                this.variant = id;

                // Autopilot is a standard board, so picking it moves the mode
                // along with it.
                if (id === "autopilot") this.base = 0;

                this.show();
            },
            // One cache entry per difficulty and board.
            cacheKey(beatmapId, mode) {
                return beatmapId + ":" + mode;
            },
            // Shows what is already known for the current difficulty and board,
            // and fetches the first page when it is the first visit.
            show() {
                const beatmapId = this.beatmap && this.beatmap.id;

                if (!beatmapId) return;

                const mode = this.mode;
                const known = this.cache[this.cacheKey(beatmapId, mode)];

                this.error = "";
                this.offset = 0;

                if (known) {
                    this.rows = known.rows;
                    this.total = known.total;
                    return;
                }

                this.rows = [];
                this.total = 0;
                this.load(beatmapId, mode, 0);
            },
            more() {
                const id = this.beatmap && this.beatmap.id;

                if (id) {
                    this.load(id, this.mode, this.rows.length);
                }
            },
            async load(beatmapId, mode, offset) {
                this.loading = true;

                try {
                    // Straight to the API on this origin. This used to go
                    // through /data/map-scores, which turned one board into a
                    // request on the frontend and another on the backend.
                    const envelope = (await this.api("get_map_scores", {
                        id: beatmapId,
                        mode: mode,
                        offset: offset,
                        limit: this.limit
                    })) || {};

                    const page = envelope.results || [];

                    // The reader may have switched difficulty or board while
                    // this was in flight, in which case the answer is not
                    // wanted any more.
                    if (!this.beatmap || String(this.beatmap.id) !== String(beatmapId)) return;
                    if (mode !== this.mode) return;

                    this.rows = offset ? this.rows.concat(page) : page;
                    this.total = Number(envelope.count || this.rows.length);
                    this.cache[this.cacheKey(beatmapId, mode)] = {
                        rows: this.rows,
                        total: this.total
                    };
                } catch (e) {
                    if (!this.rows.length) {
                        this.error = "The scores could not be loaded.";
                    }
                } finally {
                    this.loading = false;
                }
            }
        }
    });
</script>

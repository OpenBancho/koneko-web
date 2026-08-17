<template id="leaderboard-view">
    <div class="page">
        <site-nav></site-nav>

        <koneko-slot name="leaderboard.top"></koneko-slot>
        <koneko-slot name="page.top"></koneko-slot>

        <section class="mode-bar">
            <div class="mode-tabs">
                <button class="mode-tab" v-for="tab in modes" :key="tab.id"
                    :class="{ active: base === tab.id }" @click="pickBase(tab.id)">
                    {{ tab.label }}
                </button>
            </div>

            <div class="mode-variants">
                <button class="mode-variant" :class="{ active: !variant }"
                    @click="pickVariant('')">Vanilla</button>
                <button class="mode-variant" :class="{ active: variant === 'relax' }"
                    :disabled="!relaxAllowed" @click="pickVariant('relax')">Relax</button>
                <button class="mode-variant" :class="{ active: variant === 'autopilot' }"
                    :disabled="!autopilotAllowed" @click="pickVariant('autopilot')">Autopilot</button>
            </div>
        </section>

        <section class="card">
            <div class="filters">
                <!--
                    A native select cannot show images in its options, so
                    the country filter is a row of flags instead.
                -->
                <div class="field field-wide" v-if="countries.length">
                    <span class="field-label">Country</span>
                    <div class="flag-filter">
                        <button class="flag-chip" :class="{ active: !country }"
                            @click="pickCountry('')">All</button>

                        <button class="flag-chip" v-for="entry in countries" :key="entry.country"
                            :class="{ active: country === entry.country }"
                            :title="entry.country.toUpperCase() + ' - ' + fmtNumber(entry.players) + ' players'"
                            @click="pickCountry(entry.country)">
                            <span :class="flagClass(entry.country)"></span>
                            <span class="flag-count">{{ fmtNumber(entry.players) }}</span>
                        </button>
                    </div>
                </div>

                <label class="field">
                    <span class="field-label">Sort by</span>
                    <select v-model="sort" @change="reset">
                        <option value="pp">Performance</option>
                        <option value="score">Ranked score</option>
                        <option value="acc">Accuracy</option>
                        <option value="plays">Playcount</option>
                    </select>
                </label>

                <button class="button button-ghost" v-if="country || sort !== 'pp'" @click="clear">
                    Reset filters
                </button>
            </div>

            <p class="muted" v-if="loading">Loading the ranking...</p>
            <p class="muted" v-else-if="!rows.length">Nobody has set a score in this mode yet.
                Be the first.</p>

            <table class="table" v-else>
                <thead>
                    <tr>
                        <th>#</th>
                        <th>Player</th>
                        <th class="numeric">pp</th>
                        <th class="numeric">Accuracy</th>
                        <th class="numeric">Playcount</th>
                        <th class="numeric">Ranked score</th>
                        <th class="numeric">Max combo</th>
                    </tr>
                </thead>
                <tbody>
                    <tr v-for="(row, index) in rows" :key="row.id || index">
                        <td class="rank-cell">{{ fmtNumber(row.rank || offset + index + 1) }}</td>
                        <td>
                            <span class="country-tag" v-if="flagClass(row.country)"
                                :class="flagClass(row.country)"
                                :title="row.country.toUpperCase()"></span>
                            <a :href="'/u/' + row.id + (mode ? '?mode=' + mode : '')">{{ row.name }}</a>
                            <span class="group-badge group-badge-small" v-for="group in row.groups || []"
                                :key="group.id" :style="groupStyle(group)" :title="group.name">
                                <span v-if="group.icon">{{ group.icon }}</span><span>{{ group.name }}</span>
                            </span>
                        </td>
                        <td class="numeric">{{ fmtNumber(row.pp) }}</td>
                        <td class="numeric">{{ fmtAccuracy(row.acc) }}</td>
                        <td class="numeric">{{ fmtNumber(row.plays) }}</td>
                        <td class="numeric">{{ fmtNumber(row.rscore) }}</td>
                        <td class="numeric">{{ fmtNumber(row.max_combo) }}x</td>
                    </tr>
                </tbody>
            </table>

            <div class="pager" v-if="rows.length">
                <button class="button button-ghost" :disabled="offset === 0" @click="page(-1)">Previous</button>
                <span class="muted">{{ rangeText }}</span>
                <button class="button button-ghost" :disabled="!hasNext" @click="page(1)">Next</button>
            </div>
        </section>

                <koneko-slot name="page.bottom"></koneko-slot>
        <koneko-slot name="leaderboard.bottom"></koneko-slot>

        <site-footer></site-footer>
    </div>
</template>

<script>
    app.component("leaderboard-view", {
        template: "#leaderboard-view",
        data: () => ({
            loading: true,
            rows: [],
            countries: [],
            total: 0,
            offset: 0,
            limit: 50,
            base: 0,
            variant: "",
            country: "",
            sort: "pp",
            modes: [
                { id: 0, label: "osu!" },
                { id: 1, label: "osu!taiko" },
                { id: 2, label: "osu!catch" },
                { id: 3, label: "osu!mania" }
            ]
        }),
        computed: {
            // Same numbering as the server uses: plain modes are 0-3, relax
            // shifts by four and autopilot is standard only.
            mode() {
                if (this.variant === "autopilot") return 8;
                if (this.variant === "relax") return this.base + 4;

                return this.base;
            },
            relaxAllowed() {
                return this.base !== 3;
            },
            autopilotAllowed() {
                return this.base === 0;
            },
            hasNext() {
                return this.offset + this.rows.length < this.total;
            },
            rangeText() {
                const first = this.offset + 1;
                const last = this.offset + this.rows.length;

                return first + "-" + last + " of " + this.fmtNumber(this.total);
            }
        },
        methods: {
            pickBase(id) {
                if (this.base === id) return;

                this.base = id;

                if (this.variant === "relax" && !this.relaxAllowed) this.variant = "";
                if (this.variant === "autopilot" && !this.autopilotAllowed) this.variant = "";

                this.reset();
            },
            pickVariant(name) {
                this.variant = this.variant === name ? "" : name;
                this.reset();
            },
            pickCountry(code) {
                if (this.country === code) return;

                this.country = code;
                this.reset();
            },
            clear() {
                this.country = "";
                this.sort = "pp";
                this.reset();
            },
            // Any filter change puts the reader back on the first page.
            reset() {
                this.offset = 0;
                this.load();
            },
            page(direction) {
                const next = this.offset + direction * this.limit;

                this.offset = Math.max(0, next);
                this.load();
                window.scrollTo({ top: 0, behavior: "smooth" });
            },
            query() {
                const query = new URLSearchParams();

                if (this.mode) query.set("mode", String(this.mode));
                if (this.country) query.set("country", this.country);
                if (this.sort !== "pp") query.set("sort", this.sort);
                if (this.offset) query.set("offset", String(this.offset));

                return query;
            },
            apply(body) {
                const envelope = body.leaderboard || {};

                this.rows = envelope.results || [];
                this.total = Number(envelope.count || this.rows.length);
                this.limit = Number(envelope.limit || this.limit);

                const countries = (body.countries && body.countries.countries) || [];

                // Keeping the old list on an empty answer avoids a filter that
                // wipes itself the moment it is used.
                if (countries.length) {
                    this.countries = countries;
                }
            },
            async load() {
                const query = this.query();

                // The filters live in the address bar, so the page can be
                // linked and the back button keeps working.
                history.replaceState(null, "", query.toString()
                    ? "/leaderboard?" + query.toString()
                    : "/leaderboard");

                const key = "leaderboard:" + query.toString();
                const cached = this.fastLoad(key);

                if (cached) {
                    this.apply(cached);
                    this.loading = false;
                } else {
                    this.loading = true;
                }

                try {
                    // Rows and the country filter both come from the API on
                    // this origin, asked for at the same time. This used to be
                    // one /data/leaderboard call that fanned out on the
                    // backend.
                    const [leaderboard, countries] = await Promise.all([
                        this.api("get_leaderboard", {
                            mode: this.mode,
                            sort: this.sort,
                            country: this.country,
                            offset: this.offset,
                            limit: this.limit
                        }),
                        this.apiQuietly("get_countries", { mode: this.mode })
                    ]);

                    const body = { leaderboard: leaderboard, countries: countries };

                    this.apply(body);
                    this.fastSave(key, body);
                } catch (e) {
                    // The cached page stays up when the API is unreachable.
                } finally {
                    this.loading = false;
                }
            }
        },
        created() {
            this.setTitle("Leaderboard");

            const query = new URLSearchParams(window.location.search);
            const mode = Number(query.get("mode") || 0);

            if (mode === 8) {
                this.variant = "autopilot";
            } else if (mode >= 4 && mode <= 6) {
                this.base = mode - 4;
                this.variant = "relax";
            } else if (mode >= 1 && mode <= 3) {
                this.base = mode;
            }

            const country = (query.get("country") || "").toLowerCase();

            if (/^[a-z]{2}$/.test(country)) {
                this.country = country;
            }

            const sort = query.get("sort");

            if (["pp", "score", "acc", "plays"].includes(sort)) {
                this.sort = sort;
            }

            this.offset = Math.max(0, Number(query.get("offset") || 0));

            this.load();
        }
    });
</script>
